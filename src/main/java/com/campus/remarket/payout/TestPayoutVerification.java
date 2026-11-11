package com.campus.remarket.payout;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

@Service
public class TestPayoutVerification {
    private final JdbcTemplate db;
    private final TestPayoutGateway gateway;
    private static final Set<String> STATES = Set.of("REQUESTED","IN_PROGRESS","COMPLETED","FAILED","CANCELED","DELETED","REJECTED");
    private static final Set<String> TERMINAL = Set.of("COMPLETED","FAILED","CANCELED","DELETED","REJECTED");

    public TestPayoutVerification(JdbcTemplate db, TestPayoutGateway gateway) {
        this.db = db;
        this.gateway = gateway;
    }

    public record View(boolean linked, String status, Integer amount, LocalDateTime verifiedAt) {}
    private record Seller(String id, String reference) {}
    private record Link(Long member, String payout, String status, Integer amount, LocalDateTime verifiedAt) {}

    @Transactional
    public void registerSeller(long memberId, String sellerId, String reference) {
        if (reference == null || reference.isBlank() || reference.length() > 64) throw conflict("상점의 refSellerId를 확인해 주세요.");
        if (db.queryForList("SELECT unisno FROM student WHERE member_id=? FOR UPDATE", String.class, memberId).isEmpty())
            throw conflict("해당 학생 회원의 판매자 계정을 찾을 수 없습니다.");
        var remote = gateway.seller(sellerId);
        if (!"CORPORATE".equals(value(remote,"businessType")) ||
                !Set.of("APPROVED","PARTIALLY_APPROVED").contains(value(remote,"status")) ||
                !reference.equals(value(remote,"refSellerId"))) throw conflict("등록한 법인 테스트 셀러의 유형, 승인 상태, 상점 참조번호를 확인해 주세요.");
        var existing = seller(memberId);
        if (existing != null) {
            if (!existing.equals(new Seller(sellerId,reference))) throw conflict("해당 회원에는 다른 테스트 셀러가 연결되어 있습니다.");
            return;
        }
        try {
            db.update("INSERT INTO toss_test_seller_links(member_id,seller_id,ref_seller_id) VALUES(?,?,?)",memberId,sellerId,reference);
        } catch (DuplicateKeyException ex) { throw conflict("이미 다른 회원에 연결된 테스트 셀러입니다."); }
    }

    @Transactional
    public View link(String orderId, String payoutId) {
        lock(orderId);
        var existing = existing(orderId);
        if (existing != null && !Objects.equals(existing.payout(),payoutId)) throw conflict("이 주문에 이미 다른 지급 내역이 연결되어 있습니다.");
        return reconcile(orderId, payoutId, existing);
    }

    @Transactional(readOnly = true)
    public View view(String orderId, long actorId) {
        requireSeller(orderId, actorId);
        var link = existing(orderId);
        if (link != null && actorId != link.member()) throw forbidden();
        return view(link);
    }

    @Transactional
    public View refresh(String orderId, long actorId) {
        lock(orderId);
        requireSeller(orderId, actorId);
        var link = existing(orderId);
        if (link == null) throw conflict("관리자가 외부 테스트 지급 내역을 먼저 연결해야 합니다.");
        if (actorId != link.member()) throw forbidden();
        return reconcile(orderId, link.payout(), link);
    }

    private void lock(String id) {
        if (id == null || id.isBlank() || id.length() > 64 ||
            db.queryForList("SELECT order_id FROM trade_orders WHERE order_id=? FOR UPDATE", String.class, id).isEmpty())
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"주문을 찾을 수 없습니다.");
    }

    private long saleOwner(String orderId) {
        var ids = db.queryForList("""
            SELECT s.seller_member_id FROM trade_orders o JOIN sales s ON s.selno=o.selno
            JOIN student st ON st.member_id=s.seller_member_id AND st.unisno=s.seller
            WHERE o.order_id=?
            """, Long.class, orderId);
        if (ids.isEmpty()) throw conflict("판매자 회원과 연결된 주문만 조회할 수 있습니다.");
        return ids.getFirst();
    }

    private void requireSeller(String orderId, long actorId) {
        if (saleOwner(orderId) != actorId) throw forbidden();
    }

    private View reconcile(String orderId, String payoutId, Link prior) {
        long memberId = saleOwner(orderId);
        Seller destination = seller(memberId);
        if (destination == null || (prior != null && prior.member() != memberId))
            throw conflict("이 주문의 테스트 셀러 연결을 확인해 주세요.");
        var amounts = db.queryForList("""
            SELECT r.total_amount FROM payment_receipts r JOIN trade_orders o ON o.order_id=r.order_id
            JOIN toss_escrow_orders e ON e.order_id=o.order_id
            WHERE r.order_id=? AND r.payment_mode='TEST' AND r.status='DONE' AND r.payment_key=o.payment_key
            AND r.total_amount=o.agreed_price AND r.total_amount=r.balance_amount
            AND e.escrow_id IS NOT NULL AND e.product_id IS NOT NULL AND e.verified_at IS NOT NULL AND e.status='CONFIRMED'
            """, Integer.class, orderId);
        if (amounts.isEmpty()) throw conflict("전액 테스트 승인과 토스 에스크로 구매확정을 먼저 확인해 주세요.");
        int amount = amounts.getFirst();
        var remote = gateway.payout(payoutId);
        String status = value(remote,"status");
        var money = remote.path("amount");
        if (!orderId.equals(value(remote,"refPayoutId")) || !destination.id().equals(value(remote,"destination"))
                || !"KRW".equals(value(money,"currency")) || !money.path("value").isNumber()
                || money.get("value").decimalValue().compareTo(BigDecimal.valueOf(amount)) != 0 || !STATES.contains(status))
            throw conflict("토스 지급 내역의 주문번호, 수취 셀러, 원화 금액 또는 상태가 일치하지 않습니다.");
        if (prior == null) {
            try {
                db.update("""
                    INSERT INTO toss_test_payout_links(order_id,member_id,payout_id,amount,status,verified_at)
                    VALUES(?,?,?,?,?,CURRENT_TIMESTAMP)
                    """, orderId, memberId, payoutId, amount, status);
            } catch (DuplicateKeyException ex) { throw conflict("이미 연결된 지급 내역입니다."); }
        } else {
            if (prior.amount() != amount || (TERMINAL.contains(prior.status()) && !prior.status().equals(status)) ||
                ("IN_PROGRESS".equals(prior.status()) && "REQUESTED".equals(status)))
                throw conflict("기존 조회 결과와 일치하지 않아 지급 기록을 변경하지 않았습니다.");
            db.update("UPDATE toss_test_payout_links SET status=?,verified_at=CURRENT_TIMESTAMP WHERE order_id=?",status,orderId);
        }
        return view(existing(orderId));
    }

    private Seller seller(long member) {
        return db.query("SELECT seller_id,ref_seller_id FROM toss_test_seller_links WHERE member_id=?",
            (r,n)->new Seller(r.getString(1),r.getString(2)),member).stream().findFirst().orElse(null);
    }

    private Link existing(String id) {
        return db.query("SELECT member_id,payout_id,status,amount,verified_at FROM toss_test_payout_links WHERE order_id=?",
            (r,n)->new Link(r.getLong(1),r.getString(2),r.getString(3),r.getInt(4),r.getTimestamp(5).toLocalDateTime()),id)
            .stream().findFirst().orElse(null);
    }
    private static View view(Link l) { return l == null ? new View(false,null,null,null) : new View(true,l.status(),l.amount(),l.verifiedAt()); }
    private static String value(JsonNode node, String field) { return node.path(field).asString(""); }
    private static ResponseStatusException conflict(String m) { return new ResponseStatusException(HttpStatus.CONFLICT,m); }
    private static ResponseStatusException forbidden() { return new ResponseStatusException(HttpStatus.FORBIDDEN,"이 주문의 판매자만 조회할 수 있습니다."); }
}
