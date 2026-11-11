package com.campus.remarket.service;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class TossGatewayContractTests {
    @Test void uncertainApprovalLooksUpSameOrderWithMerchantCredentials(){
        var builder=RestClient.builder();
        var server=MockRestServiceServer.bindTo(builder).build();
        var client=new TossPaymentGateway("test_ck_local","test_sk_local","TEST",false,builder);
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/confirm"))
              .andExpect(method(HttpMethod.POST)).andExpect(header("Idempotency-Key","order1"))
              .andExpect(header("Authorization","Basic dGVzdF9za19sb2NhbDo="))
              .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        server.expect(requestTo("https://api.tosspayments.com/v1/payments/orders/order1"))
              .andExpect(method(HttpMethod.GET)).andExpect(header("Authorization","Basic dGVzdF9za19sb2NhbDo="))
              .andRespond(withSuccess("""
                    {"orderId":"order1","paymentKey":"key","status":"DONE","totalAmount":12000,
                     "balanceAmount":12000,"currency":"KRW","lastTransactionKey":"t1","method":"카드"}
                    """,MediaType.APPLICATION_JSON));
        var result=client.confirm("order1","key",12000);
        assertEquals("DONE",result.status());
        assertEquals(12000,result.balanceAmount());
        server.verify();
    }
    @Test void liveKeysRequireBothModeAndExplicitOptIn(){
        assertFalse(new TossPaymentGateway("live_ck_x","live_sk_x","TEST",true).ready());
        assertFalse(new TossPaymentGateway("live_ck_x","live_sk_x","LIVE",false).ready());
        assertFalse(new TossPaymentGateway("test_ck_x","test_sk_x","LIVE",true).ready());
        assertTrue(new TossPaymentGateway("live_ck_x","live_sk_x","LIVE",true).ready());
    }
}
