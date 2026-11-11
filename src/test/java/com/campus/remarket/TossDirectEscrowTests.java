package com.campus.remarket;

import com.campus.remarket.escrow.TossDirectEscrowService;
import com.campus.remarket.escrow.TossTestEscrowGateway;
import com.campus.remarket.escrow.TossTestEscrowGateway.Delivery;
import com.campus.remarket.escrow.TossTestEscrowGateway.Escrow;
import com.campus.remarket.escrow.TossTestEscrowGateway.Product;
import com.campus.remarket.service.PaymentService;
import com.campus.remarket.service.TossPaymentGateway;
import com.campus.remarket.service.TossPaymentGateway.Payment;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:toss_direct_escrow;MODE=MySQL;DB_CLOSE_DELAY=-1")
class TossDirectEscrowTests {
    private static final int PRICE = 12000;
    private static final String BUYER = "demo-buyer";
    @Autowired PaymentService payments;
    @Autowired TossDirectEscrowService escrow;
    @Autowired JdbcTemplate db;
    @MockitoBean TossPaymentGateway paymentsApi;
    @MockitoBean TossTestEscrowGateway escrowApi;
    private String seller;
    private String order;
    private String paymentKey;
    private String escrowKey;
    private String productId;
    private String deliveryId;

    @BeforeEach void createTransaction() {
        when(paymentsApi.mode()).thenReturn("TEST");
        when(paymentsApi.ready()).thenReturn(true);
        when(escrowApi.enabled()).thenReturn(true);
        db.update("UPDATE sales SET school_id=(SELECT id FROM school WHERE campus_code='INHA') WHERE selno=2");
        seller = db.queryForObject("SELECT seller FROM sales WHERE selno=2",String.class);
        order = reserved(BUYER);
        paymentKey = "pay_" + order;
        escrowKey = "esc_" + order;
        productId = "prod_" + order;
        deliveryId = "del_" + order;
    }

    private String reserved(String buyer) {
        String id = "direct_" + UUID.randomUUID();
        db.update("INSERT INTO trade_orders(order_id,selno,buyer,agreed_price,status) VALUES (?,2,?,?,'RESERVED')",
                id, buyer, PRICE);
        return id;
    }

    private Escrow remote(String targetId, String state, List<String> deliveries) {
        return new Escrow(escrowKey,"PAYMENT",targetId,state,List.of(new Product(productId)),deliveries);
    }

    private void paidEscrow() {
        payments.prepare(order,true);
        when(paymentsApi.confirm(order,paymentKey,PRICE)).thenReturn(
                new Payment(order,paymentKey,PRICE,PRICE,"KRW","DONE","tx_"+order,true,escrowKey));
        payments.confirm(order,paymentKey,PRICE);
    }

    @Test void preparePseudonymIsStableAcrossOneBuyersOrdersAndDistinctForAnotherBuyer() {
        var first = payments.prepare(order,true);
        String customer = (String)first.get("customerKey");
        assertEquals(customer,UUID.fromString(customer).toString());
        assertEquals(true,first.get("useEscrow"));
        assertEquals(customer,payments.prepare(order,true).get("customerKey"));
        assertEquals(customer,payments.prepare(reserved(BUYER),true).get("customerKey"));
        assertNotEquals(customer,payments.prepare(reserved("demo-seller-3"),true).get("customerKey"));
    }

    @Test void disabledGateBlocksEscrowBeforeAdvancingTheOrder() {
        when(escrowApi.enabled()).thenReturn(false);
        assertThrows(IllegalStateException.class,()->payments.prepare(order,true));
        assertEquals("RESERVED",db.queryForObject("SELECT status FROM trade_orders WHERE order_id=?",String.class,order));
        assertEquals(false,payments.prepare(order,false).get("useEscrow"));
    }

    @Test void directDeliveryIsBoundToParticipantsReplaysOnceAndConfirmationRequiresMerchantReadback() {
        paidEscrow();
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM escrow_simulations WHERE order_id=?",Integer.class,order));
        when(escrowApi.fetch(escrowKey)).thenReturn(remote(paymentKey,"IN_PROGRESS",List.of()));
        assertEquals("IN_PROGRESS",escrow.refresh(order,BUYER).status());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->escrow.receive(order,seller,"수령인")).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->escrow.deliver(order,BUYER)).getStatusCode().value());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->escrow.view(order,"unrelated-account")).getStatusCode().value());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->escrow.deliver(order,seller)).getStatusCode().value());
        verify(escrowApi,never()).direct(anyString(),anyString(),anyString(),anyString());
        assertTrue(escrow.receive(order,BUYER,"수령인").received());
        var direct = new Delivery(deliveryId,"ESCROW_PRODUCT",productId,"COMPLETED","DIRECT");
        when(escrowApi.direct(eq(productId),eq("수령인"),anyString(),anyString())).thenAnswer(invocation -> {
            when(escrowApi.fetch(escrowKey)).thenReturn(remote(paymentKey,"IN_PROGRESS",List.of(deliveryId)));
            return direct;
        });
        when(escrowApi.getDelivery(deliveryId)).thenReturn(direct);
        assertTrue(escrow.deliver(order,seller).delivered());
        assertTrue(escrow.deliver(order,seller).delivered());
        verify(escrowApi,times(1)).direct(eq(productId),eq("수령인"),anyString(),anyString());
        assertNotEquals("CONFIRMED",escrow.view(order,BUYER).status());
        when(escrowApi.fetch(escrowKey)).thenReturn(remote(paymentKey,"CONFIRMED",List.of(deliveryId)));
        assertEquals("CONFIRMED",escrow.refresh(order,seller).status());
    }

    @Test void merchantSnapshotForDifferentPaymentCannotConfirmThisOrder() {
        paidEscrow();
        when(escrowApi.fetch(escrowKey)).thenReturn(remote("another-payment","CONFIRMED",List.of()));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->escrow.refresh(order,BUYER)).getStatusCode().value());
        assertNotEquals("CONFIRMED",escrow.view(order,BUYER).status());
        verify(escrowApi,never()).direct(anyString(),anyString(),anyString(),anyString());
    }
}
