package com.campus.remarket;

import com.campus.remarket.service.PaymentService;
import com.campus.remarket.service.TossPaymentGateway;
import com.campus.remarket.service.TossPaymentGateway.Payment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:payment_hooks;MODE=MySQL;DB_CLOSE_DELAY=-1")
@Transactional
class TossPaymentReconciliationTests {
    private static final String ORDER="demo-reserved-2", KEY="sandbox-payment";
    private static final int PRICE=12000;
    @Autowired PaymentService payments;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean TossPaymentGateway toss;

    @BeforeEach void prepare(){
        when(toss.mode()).thenReturn("TEST");
        payments.prepare(ORDER);
    }
    private Payment snapshot(String state,int balance,String transaction){
        return new Payment(ORDER,KEY,PRICE,balance,"KRW",state,transaction);
    }
    private String tradeState(){
        return jdbc.queryForObject("SELECT status FROM trade_orders WHERE order_id=?",String.class,ORDER);
    }
    private int eventCount(){
        return jdbc.queryForObject("SELECT COUNT(*) FROM payment_events WHERE order_id=?",Integer.class,ORDER);
    }

    @Test void webhookFirstThenRedirectAndDuplicateDoesNotChargeAgain(){
        when(toss.getByOrder(ORDER)).thenReturn(snapshot("DONE",PRICE,"t1"));
        payments.reconcile(ORDER,KEY);
        payments.reconcile(ORDER,KEY);
        assertEquals("DONE",payments.confirm(ORDER,KEY,PRICE).get("status"));
        assertEquals("PAID",tradeState());
        assertEquals(1,eventCount());
        verify(toss,never()).confirm(any(),any(),anyInt());
    }

    @Test void refundsAreVerifiedAndOlderNotificationCannotReopenTheOrder(){
        when(toss.confirm(ORDER,KEY,PRICE)).thenReturn(snapshot("DONE",PRICE,"t1"));
        payments.confirm(ORDER,KEY,PRICE);
        when(toss.getByOrder(ORDER)).thenReturn(snapshot("PARTIAL_CANCELED",5000,"t2"));
        payments.reconcile(ORDER,KEY);
        assertEquals("PAYMENT_READY",tradeState());
        assertThrows(IllegalStateException.class,()->payments.prepare(ORDER));
        assertEquals(5000,payments.status(ORDER).get("balanceAmount"));
        when(toss.getByOrder(ORDER)).thenReturn(snapshot("CANCELED",0,"t3"));
        payments.reconcile(ORDER,KEY);
        payments.reconcile(ORDER,KEY);
        assertEquals("CANCELED",tradeState());
        assertEquals(3,eventCount());
        assertEquals("CANCELED",payments.confirm(ORDER,KEY,PRICE).get("status"));
        verify(toss,times(1)).confirm(ORDER,KEY,PRICE);
    }

    @Test void merchantLookupAmountMismatchDoesNotCreateReceipt(){
        when(toss.getByOrder(ORDER)).thenReturn(new Payment(ORDER,KEY,1,1,"KRW","DONE","t1"));
        assertThrows(IllegalStateException.class,()->payments.reconcile(ORDER,KEY));
        assertEquals("PAYMENT_READY",tradeState());
        assertEquals(0,eventCount());
    }

    @Test void expiryDoesNotBecomeAPaidSale(){
        when(toss.getByOrder(ORDER)).thenReturn(snapshot("EXPIRED",0,"expired"));
        payments.reconcile(ORDER,KEY);
        assertEquals("CANCELED",tradeState());
        assertEquals("EXPIRED",payments.status(ORDER).get("status"));
        assertEquals(1,eventCount());
    }
}
