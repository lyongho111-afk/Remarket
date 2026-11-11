package com.campus.remarket.service;

import com.campus.remarket.dao.SalesDao;
import com.campus.remarket.dao.TradeDao;
import com.campus.remarket.dto.TradeDto;
import com.campus.remarket.escrow.PaymentBalanceChanged;
import com.campus.remarket.escrow.TossTestEscrowGateway;
import com.campus.remarket.service.TossPaymentGateway.Payment;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {
    private record Receipt(String key, String status, String mode, int balance) {}
    private static final Set<String> FINAL = Set.of("CANCELED", "EXPIRED", "ABORTED");
    private static final Set<String> ACTIVE = Set.of("READY", "IN_PROGRESS", "WAITING_FOR_DEPOSIT");
    private final TradeDao trades;
    private final SalesDao sales;
    private final TossPaymentGateway toss;
    private final JdbcTemplate db;
    private final ApplicationEventPublisher events;
    private final TossTestEscrowGateway official;

    public PaymentService(TradeDao trades, SalesDao sales, TossPaymentGateway toss,
                          JdbcTemplate db, ApplicationEventPublisher events,TossTestEscrowGateway official) {
        this.trades = trades;
        this.sales = sales;
        this.toss = toss;
        this.db = db;
        this.events = events;
        this.official = official;
    }
    public boolean ready() { return toss.ready(); }
    public String clientKey() { return toss.clientKey(); }
    public String mode() { return toss.mode(); }
    public boolean escrowEnabled() { return official.enabled(); }
    private boolean escrowOrder(String id) {
        return db.queryForObject("SELECT COUNT(*) FROM toss_escrow_orders WHERE order_id=?",Integer.class,id)>0;
    }
    private void checkOrderMode(String id) {
        if (!"TEST".equals(mode()) && escrowOrder(id))
            throw new IllegalStateException("테스트 에스크로 주문을 운영 키로 처리할 수 없습니다.");
    }

    @Transactional(readOnly=true)
    public Map<String,Object> status(String id) {
        Receipt r=receipt(id);
        if (r==null) return Map.of("status","UNCONFIRMED","paymentMode",mode(),"useEscrow",escrowOrder(id));
        return Map.of("status",r.status(),"balanceAmount",r.balance(),"paymentMode",r.mode());
    }

    @Transactional
    public Map<String,Object> prepare(String id) {
        return prepare(id,false);
    }
    @Transactional
    public Map<String,Object> prepare(String id, boolean wantEscrow) {
        toss.requireReady();
        TradeDto order = requireOrder(id);
        if (!Set.of("RESERVED","PAYMENT_READY").contains(order.getStatus()) || receipt(id) != null)
            throw new IllegalStateException("결제를 새로 시작할 수 없는 거래입니다.");
        if (order.getAgreedPrice() < 1) throw new IllegalArgumentException("무료 나눔에는 결제가 필요하지 않습니다.");
        boolean escrowSelected=escrowOrder(id);
        if ((wantEscrow || escrowSelected) && (!"TEST".equals(mode()) || !escrowEnabled()))
            throw new IllegalStateException("토스 테스트 에스크로가 설정되지 않았습니다.");
        if (wantEscrow && !escrowSelected)
            db.update("INSERT INTO toss_escrow_orders(order_id,status) VALUES(?,'SELECTED')",id);
        // The student record also serializes concurrent checkouts for the same buyer on different orders.
        db.queryForObject("SELECT unisno FROM student WHERE unisno=? FOR UPDATE",String.class,order.getBuyer());
        String customerKey=db.query("SELECT customer_key FROM toss_customer_keys WHERE buyer=?",
                (r,n)->r.getString(1),order.getBuyer()).stream().findFirst().orElse(null);
        if (customerKey==null) {
            customerKey=java.util.UUID.randomUUID().toString();
            db.update("INSERT INTO toss_customer_keys(buyer,customer_key) VALUES(?,?)",order.getBuyer(),customerKey);
        }
        trades.paymentReady(id);
        return Map.of("orderId",id,"amount",order.getAgreedPrice(),"orderName",trades.selectOne(id).getSaleName(),
                      "paymentMode",mode(),"customerKey",customerKey,"useEscrow",wantEscrow||escrowSelected);
    }

    @Transactional
    public Map<String,Object> confirm(String id, String key, Integer amount) {
        toss.requireReady();
        TradeDto order = requireOrder(id);
        checkOrderMode(id);
        if (!validKey(key) || !order.getAgreedPrice().equals(amount))
            throw new IllegalArgumentException("주문 금액 또는 결제 정보를 확인해 주세요.");
        Receipt previous = receipt(id);
        if (previous != null) {
            ensureSamePayment(previous, key);
            return result(id, previous.status());
        }
        boolean previouslyPaid = ("PAID".equals(order.getStatus()) || order.isCompleted())
                && key.equals(order.getPaymentKey());
        if (!previouslyPaid && !"PAYMENT_READY".equals(order.getStatus()))
            throw new IllegalStateException("결제 준비 상태가 아닙니다.");
        Payment verified = previouslyPaid ? toss.getByOrder(id) : toss.confirm(id, key, amount);
        validate(order, key, verified);
        if (!"DONE".equals(verified.status()) && !FINAL.contains(verified.status())
                && !"PARTIAL_CANCELED".equals(verified.status()))
            throw new IllegalStateException("결제가 아직 승인되지 않았습니다.");
        record(order, verified, null);
        return result(id, verified.status());
    }

    @Transactional
    public void reconcile(String id, String key) {
        if (id == null || id.length() > 64 || !validKey(key))
            throw new IllegalArgumentException("결제 웹훅의 주문 정보가 올바르지 않습니다.");
        TradeDto order = trades.lockOne(id);
        if (order == null) return;  // the merchant may share a webhook across applications
        checkOrderMode(id);
        Receipt previous = receipt(id);
        if (previous == null && !"PAYMENT_READY".equals(order.getStatus())
                && !key.equals(order.getPaymentKey())) return;
        if (previous != null) ensureSamePayment(previous,key);
        Payment verified = toss.getByOrder(id);
        validate(order,key,verified);
        if (ACTIVE.contains(verified.status())) return;
        record(order,verified,previous);
    }

    private void record(TradeDto order, Payment p, Receipt previous) {
        String id = order.getOrderId();
        if ("TEST".equals(mode()) && "DONE".equals(p.status())) {
            if (Boolean.TRUE.equals(p.useEscrow())) {
                if (!TossTestEscrowGateway.validProviderId(p.escrowKey()))
                    throw new IllegalStateException("토스 에스크로 거래 식별자를 확인할 수 없습니다.");
                var rows=db.query("SELECT escrow_id FROM toss_escrow_orders WHERE order_id=?",(r,n)->r.getString(1),id);
                if (rows.isEmpty()) db.update("INSERT INTO toss_escrow_orders(order_id,escrow_id,status) VALUES(?,?,'AWAITING_LOOKUP')",id,p.escrowKey());
                else if (rows.getFirst()==null) db.update("UPDATE toss_escrow_orders SET escrow_id=?,status='AWAITING_LOOKUP' WHERE order_id=?",p.escrowKey(),id);
                else if (!p.escrowKey().equals(rows.getFirst())) throw new IllegalStateException("에스크로 거래 식별자가 다릅니다.");
            } else {
                // Approval remains recorded even if the merchant was not enabled for escrow.
                db.update("UPDATE toss_escrow_orders SET status='NOT_APPLIED' WHERE order_id=? AND status='SELECTED'",id);
            }
        }
        String snapshot = fingerprint(p);
        int seen = db.queryForObject("SELECT COUNT(*) FROM payment_events WHERE snapshot_id=?",Integer.class,snapshot);
        if (seen != 0) return;
        String next = p.status();
        if (!"DONE".equals(next) && !"PARTIAL_CANCELED".equals(next) && !FINAL.contains(next))
            throw new IllegalArgumentException("지원하지 않는 결제 상태입니다.");
        if (previous != null && (FINAL.contains(previous.status()) && !previous.status().equals(next)
                || Set.of("DONE","PARTIAL_CANCELED").contains(previous.status()) && Set.of("ABORTED","EXPIRED").contains(next)
                || p.balanceAmount() > previous.balance()))
            throw new IllegalStateException("이미 반영된 결제 또는 환불 상태와 일치하지 않습니다.");
        if (previous == null) {
            db.update("INSERT INTO payment_receipts(order_id,payment_key,payment_mode,total_amount,balance_amount,status) VALUES(?,?,?,?,?,?)",
                    id,p.paymentKey(),mode(),p.totalAmount(),p.balanceAmount(),next);
        } else {
            db.update("UPDATE payment_receipts SET balance_amount=?,status=?,updated_at=CURRENT_TIMESTAMP WHERE order_id=?",
                    p.balanceAmount(),next,id);
        }
        if ("DONE".equals(next) && "PAYMENT_READY".equals(order.getStatus())) {
            if (trades.paymentConfirmed(id,p.paymentKey()) != 1) throw new IllegalStateException("결제 상태가 변경되었습니다.");
        } else if (!order.isCompleted() && !"CANCELED".equals(order.getStatus()) && FINAL.contains(next)) {
            sales.lockSale(order.getSelno());
            db.update("UPDATE trade_orders SET status='CANCELED',payment_key=? WHERE order_id=?",p.paymentKey(),id);
            sales.statusUpdate(order.getSelno(),"판매중");
        } else if (!order.isCompleted() && "PARTIAL_CANCELED".equals(next)) {
            // Leave trade fulfillment blocked; this order already has a receipt and cannot be charged again.
            db.update("UPDATE trade_orders SET status='PAYMENT_READY',payment_key=? WHERE order_id=?",p.paymentKey(),id);
        }
        db.update("INSERT INTO payment_events(snapshot_id,order_id,status,balance_amount) VALUES(?,?,?,?)",
                snapshot,id,next,p.balanceAmount());
        events.publishEvent(new PaymentBalanceChanged(id));
    }

    private TradeDto requireOrder(String id) {
        if(id == null || id.isBlank() || id.length() > 64) throw new IllegalArgumentException("주문번호를 확인하세요.");
        TradeDto order = trades.lockOne(id);
        if(order == null) throw new IllegalArgumentException("주문을 찾을 수 없습니다.");
        return order;
    }
    private Map<String,Object> result(String id, String status) {
        return Map.of("orderId",id,"status",status,"paymentMode",mode());
    }
    private Receipt receipt(String id) {
        return db.query("SELECT payment_key,status,payment_mode,balance_amount FROM payment_receipts WHERE order_id=?",
                (rs,n)->new Receipt(rs.getString(1),rs.getString(2),rs.getString(3),rs.getInt(4)),id)
                .stream().findFirst().orElse(null);
    }
    private void ensureSamePayment(Receipt previous, String key) {
        if (!previous.mode().equals(mode()) || !previous.key().equals(key))
            throw new IllegalStateException("기존 주문의 결제 정보가 다릅니다.");
    }
    private static boolean validKey(String key) { return key != null && !key.isBlank() && key.length() <= 200; }
    private static void validate(TradeDto order, String key, Payment p) {
        if (p == null || !order.getOrderId().equals(p.orderId()) || !key.equals(p.paymentKey())
                || (order.getPaymentKey() != null && !order.getPaymentKey().equals(key))
                || !order.getAgreedPrice().equals(p.totalAmount()) || !"KRW".equals(p.currency())
                || p.status()==null || p.balanceAmount()==null || p.balanceAmount()<0 || p.balanceAmount()>p.totalAmount()
                || ("DONE".equals(p.status()) && !p.balanceAmount().equals(p.totalAmount()))
                || ("CANCELED".equals(p.status()) && p.balanceAmount()!=0)
                || ("PARTIAL_CANCELED".equals(p.status()) && (p.balanceAmount()==0 || p.balanceAmount().equals(p.totalAmount()))))
            throw new IllegalStateException("토스에서 조회한 결제 내역과 주문이 일치하지 않습니다.");
    }
    private String fingerprint(Payment p) {
        try {
            String value = String.join("\u0000", mode(),p.orderId(),p.paymentKey(),p.status(),
                    String.valueOf(p.balanceAmount()),Objects.toString(p.lastTransactionKey(),""));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
