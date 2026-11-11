package com.campus.remarket.escrow;

import com.campus.remarket.dao.TradeDao;
import com.campus.remarket.dto.TradeDto;
import com.campus.remarket.service.TradeService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EscrowSimulationService {
    private final TradeDao orders;
    private final TradeService trades;
    private final JdbcTemplate db;

    public EscrowSimulationService(TradeDao orders, TradeService trades, JdbcTemplate db) {
        this.orders = orders;
        this.trades = trades;
        this.db = db;
    }

    public record Ledger(String state, int amount, int balance, LocalDateTime heldAt,
                         LocalDateTime confirmedAt, LocalDateTime settledAt) {}

    private record Receipt(String mode, String status, String key, int total, int balance) {
        boolean eligible(TradeDto order) {
            return "TEST".equals(mode) && "DONE".equals(status) && key != null
                && key.equals(order.getPaymentKey()) && total > 0 && total == order.getAgreedPrice()
                && balance == total && ("PAID".equals(order.getStatus()) || order.isCompleted());
        }
    }

    @EventListener
    @Transactional
    public void paymentAdjusted(PaymentBalanceChanged event) {
        TradeDto order = locked(event.orderId());
        if (official(order.getOrderId())) return;
        Receipt receipt = receipt(order.getOrderId());
        if (receipt == null || !"TEST".equals(receipt.mode())) return;
        Ledger existing = ledger(order.getOrderId());
        if (existing == null && receipt.eligible(order)) {
            db.update("""
                INSERT INTO escrow_simulations (order_id, seller, amount, balance, state)
                VALUES (?, ?, ?, ?, 'HELD')
                """, order.getOrderId(), order.getSeller(), receipt.total(), receipt.balance());
        } else if (existing != null && ("CANCELED".equals(receipt.status()) || "PARTIAL_CANCELED".equals(receipt.status()))) {
            int remaining = receipt.balance();
            // Replayed or older notices may never increase the balance or reopen a refund.
            if (remaining >= 0 && remaining < existing.balance()) {
                db.update("UPDATE escrow_simulations SET balance=?, state=? WHERE order_id=?",
                    remaining, remaining == 0 ? "REFUNDED" : "PARTIALLY_REFUNDED", order.getOrderId());
            }
        }
    }

    @Transactional(readOnly = true)
    public Ledger view(String id, String actor) {
        TradeDto order = trades.selectOne(id);
        if (!Objects.equals(actor, order.getBuyer()) && !Objects.equals(actor, order.getSeller())) forbidden();
        Ledger item = ledger(id);
        return item == null ? new Ledger("UNFUNDED", 0, 0, null, null, null) : item;
    }

    @Transactional
    public Ledger confirm(String id, String buyer) {
        TradeDto order = locked(id);
        if (!Objects.equals(order.getBuyer(), buyer)) forbidden();
        Ledger current = fullyFunded(order);
        if (current.confirmedAt() != null) return current;
        if (!order.isCompleted()) trades.complete(id);
        db.update("""
            UPDATE escrow_simulations SET state='CONFIRMED', confirmed_at=CURRENT_TIMESTAMP
            WHERE order_id=? AND state='HELD'
            """, id);
        return ledger(id);
    }

    @Transactional
    public Ledger settle(String id, String seller) {
        TradeDto order = locked(id);
        if (!Objects.equals(order.getSeller(), seller)) forbidden();
        Ledger current = fullyFunded(order);
        if (current.settledAt() != null) return current;
        if (current.confirmedAt() == null || !order.isCompleted()) {
            throw conflict("구매자가 수령을 확정한 뒤 정산할 수 있습니다.");
        }
        int changed = db.update("""
            UPDATE escrow_simulations SET state='SETTLED', settled_at=CURRENT_TIMESTAMP
            WHERE order_id=? AND state='CONFIRMED' AND seller=?
            """, id, seller);
        if (changed != 1) throw conflict("정산 상태를 다시 확인해 주세요.");
        return ledger(id);
    }

    private Ledger fullyFunded(TradeDto order) {
        if (official(order.getOrderId())) throw conflict("토스 에스크로를 선택한 거래입니다.");
        Receipt receipt = receipt(order.getOrderId());
        Ledger item = ledger(order.getOrderId());
        if (receipt == null || !receipt.eligible(order) || item == null || item.balance() != item.amount()) {
            throw conflict("예약 금액 전액을 승인받은 테스트 결제만 모의 정산할 수 있습니다.");
        }
        return item;
    }

    private boolean official(String id) {
        return db.queryForObject("SELECT COUNT(*) FROM toss_escrow_orders WHERE order_id=?", Integer.class, id)>0;
    }

    private TradeDto locked(String id) {
        if (orders.lockOne(id) == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "거래가 없습니다.");
        return orders.selectOne(id);
    }

    private Receipt receipt(String id) {
        return db.query("""
            SELECT payment_mode, status, payment_key, total_amount, balance_amount
            FROM payment_receipts WHERE order_id=?
            """, (r, n) -> new Receipt(r.getString(1), r.getString(2), r.getString(3), r.getInt(4), r.getInt(5)), id)
            .stream().findFirst().orElse(null);
    }

    private Ledger ledger(String id) {
        return db.query("""
            SELECT state, amount, balance, held_at, confirmed_at, settled_at
            FROM escrow_simulations WHERE order_id=?
            """, (r, n) -> new Ledger(r.getString(1), r.getInt(2), r.getInt(3),
                time(r, 4), time(r, 5), time(r, 6)), id).stream().findFirst().orElse(null);
    }

    private static LocalDateTime time(ResultSet r, int column) throws SQLException {
        var timestamp = r.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static void forbidden() {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 거래 참여자만 이 작업을 진행할 수 있습니다.");
    }
}
