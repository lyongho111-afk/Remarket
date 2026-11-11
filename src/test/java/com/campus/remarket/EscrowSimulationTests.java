package com.campus.remarket;

import com.campus.remarket.escrow.EscrowSimulationService;
import com.campus.remarket.escrow.PaymentBalanceChanged;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class EscrowSimulationTests {
    @Autowired JdbcTemplate db;
    @Autowired EscrowSimulationService escrow;
    @Autowired ApplicationEventPublisher events;

    private record Order(String id, String buyer, String seller) {}

    private Order reserved() {
        String id = "escrow_" + UUID.randomUUID();
        db.update("UPDATE sales SET school_id=(SELECT id FROM school WHERE campus_code='INHA') WHERE selno=1");
        String seller = db.queryForObject("SELECT seller FROM sales WHERE selno=1", String.class);
        db.update("INSERT INTO trade_orders(order_id,selno,buyer,agreed_price,status) VALUES (?,1,?,10000,'RESERVED')", id, "demo-buyer");
        return new Order(id, "demo-buyer", seller);
    }

    private void paid(Order order, String mode, int amount) {
        String key = "test_" + order.id();
        db.update("UPDATE trade_orders SET status='PAID', payment_key=? WHERE order_id=?", key, order.id());
        db.update("""
            INSERT INTO payment_receipts(order_id,payment_key,payment_mode,total_amount,balance_amount,status)
            VALUES (?,?,?,?,?,'DONE')
            """, order.id(), key, mode, amount, amount);
        events.publishEvent(new PaymentBalanceChanged(order.id()));
    }

    @Test void purchaseConfirmationAndPayoutAreRoleBoundAndReplaySafe() {
        var order = reserved();
        paid(order, "TEST", 10000);
        events.publishEvent(new PaymentBalanceChanged(order.id()));
        assertEquals("HELD", escrow.view(order.id(), order.seller()).state());
        assertThrows(ResponseStatusException.class, () -> escrow.confirm(order.id(), order.seller()));
        assertThrows(ResponseStatusException.class, () -> escrow.settle(order.id(), order.seller()));
        assertThrows(ResponseStatusException.class, () -> escrow.view(order.id(), "stranger"));
        var confirmed = escrow.confirm(order.id(), order.buyer());
        assertEquals("CONFIRMED", confirmed.state());
        assertEquals(confirmed, escrow.confirm(order.id(), order.buyer()));
        assertThrows(ResponseStatusException.class, () -> escrow.settle(order.id(), order.buyer()));
        var settled = escrow.settle(order.id(), order.seller());
        assertEquals("SETTLED", settled.state());
        assertEquals(settled, escrow.settle(order.id(), order.seller()));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM escrow_simulations WHERE order_id=?", Integer.class, order.id()));
    }

    @Test void offlineCompletionLivePaymentAndWrongAmountCannotFundSimulation() {
        var offline = reserved();
        db.update("UPDATE trade_orders SET status='COMPLETED' WHERE order_id=?", offline.id());
        assertThrows(ResponseStatusException.class, () -> escrow.confirm(offline.id(), offline.buyer()));
        var live = reserved();
        paid(live, "LIVE", 10000);
        assertEquals("UNFUNDED", escrow.view(live.id(), live.buyer()).state());
        var mismatch = reserved();
        paid(mismatch, "TEST", 9999);
        assertEquals("UNFUNDED", escrow.view(mismatch.id(), mismatch.buyer()).state());
    }

    @Test void refundedPaymentAdjustsSettledBalanceAndCannotReleaseAgain() {
        var order = reserved();
        paid(order, "TEST", 10000);
        escrow.confirm(order.id(), order.buyer());
        var paidAt = escrow.settle(order.id(), order.seller()).settledAt();
        db.update("UPDATE payment_receipts SET status='PARTIAL_CANCELED', balance_amount=7000 WHERE order_id=?", order.id());
        events.publishEvent(new PaymentBalanceChanged(order.id()));
        var partial = escrow.view(order.id(), order.seller());
        assertEquals("PARTIALLY_REFUNDED", partial.state());
        assertEquals(7000, partial.balance());
        assertEquals(paidAt, partial.settledAt());
        assertThrows(ResponseStatusException.class, () -> escrow.settle(order.id(), order.seller()));
        db.update("UPDATE payment_receipts SET status='CANCELED', balance_amount=0 WHERE order_id=?", order.id());
        events.publishEvent(new PaymentBalanceChanged(order.id()));
        var full = escrow.view(order.id(), order.seller());
        assertEquals("REFUNDED", full.state());
        assertEquals(0, full.balance());
    }
}
