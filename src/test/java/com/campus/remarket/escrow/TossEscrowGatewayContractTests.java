package com.campus.remarket.escrow;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TossEscrowGatewayContractTests {
    @Test void providerIdsAllowLongTargetsAndRejectTraversalBeforeNetworkAccess(){
        assertTrue(TossTestEscrowGateway.validProviderId("p".repeat(200)));
        for(String id:java.util.List.of("p".repeat(201),"..","../other","..\\other","%252e%252e","%2F","a\rb","a\u007fb"))
            assertFalse(TossTestEscrowGateway.validProviderId(id));
    }
    @Test void usesV2EnvelopeAndRegistersOneDirectDelivery(){
        var builder=RestClient.builder();
        var server=MockRestServiceServer.bindTo(builder).build();
        var api=new TossTestEscrowGateway(true,"TEST","test_ck_stub","test_sk_stub",builder);
        server.expect(requestTo("https://api.tosspayments.com/v2/escrows/e-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                    {"version":"2024-06-01","entityType":"escrow","entityBody":{"id":"e-1",
                    "targetType":"PAYMENT","targetId":"payment-1","status":"IN_PROGRESS",
                    "products":[{"id":"product-1","quantity":1}],"deliveryIds":[]}}
                    """,MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.tosspayments.com/v2/deliveries"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Idempotency-Key","direct-order-1"))
                .andExpect(content().json("""
                    {"targetType":"ESCROW_PRODUCT","targetId":"product-1","deliveryMethod":"DIRECT",
                     "direct":{"receivedAt":"2026-11-10T15:47:00+09:00","receiverName":"수령인","receiverRelation":"BUYER"}}
                    """))
                .andRespond(withSuccess("""
                    {"entityType":"delivery","entityBody":{"id":"d-1","targetType":"ESCROW_PRODUCT",
                     "targetId":"product-1","deliveryMethod":"DIRECT","status":"COMPLETED"}}
                    """,MediaType.APPLICATION_JSON));
        assertEquals("payment-1",api.fetch("e-1").targetId());
        assertEquals("d-1",api.direct("product-1","수령인","2026-11-10T15:47:00+09:00","direct-order-1").id());
        server.verify();
    }
    @Test void liveCredentialsCannotOpenEscrowGateway(){
        var api=new TossTestEscrowGateway(true,"LIVE","live_ck_fake","live_sk_fake",RestClient.builder());
        assertFalse(api.enabled());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->api.fetch("example"));
    }
}
