package com.campus.remarket.payout;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.junit.jupiter.api.Assertions.*;

class TestPayoutVerificationTests {
    JdbcTemplate db;
    HikariDataSource ds;
    MockRestServiceServer remote;
    TestPayoutVerification service;

    @BeforeEach void setup() {
        ds = new HikariDataSource();
        ds.setJdbcUrl("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL");
        db = new JdbcTemplate(ds);
        db.execute("CREATE TABLE student (member_id BIGINT PRIMARY KEY, unisno VARCHAR(50) UNIQUE)");
        db.execute("CREATE TABLE sales (selno BIGINT PRIMARY KEY,seller_member_id BIGINT,seller VARCHAR(50))");
        db.execute("CREATE TABLE trade_orders (order_id VARCHAR(64) PRIMARY KEY,selno BIGINT,payment_key VARCHAR(200),agreed_price INT)");
        new ResourceDatabasePopulator(new ClassPathResource("payment-schema.sql"),new ClassPathResource("toss-test-payout.sql")).execute(ds);
        db.update("INSERT INTO student VALUES (17,'student1')");
        db.update("INSERT INTO sales VALUES (1,17,'student1')");
        db.update("INSERT INTO trade_orders VALUES ('campus_1',1,'verifiedKey',10000)");
        db.update("INSERT INTO payment_receipts(order_id,payment_key,payment_mode,status,total_amount,balance_amount) VALUES ('campus_1','verifiedKey','TEST','DONE',10000,10000)");
        db.update("INSERT INTO toss_escrow_orders(order_id,escrow_id,product_id,status,verified_at) VALUES('campus_1','escrow1','product1','CONFIRMED',CURRENT_TIMESTAMP)");
        var builder = RestClient.builder();
        remote = MockRestServiceServer.bindTo(builder).build();
        service = new TestPayoutVerification(db,new TestPayoutGateway("test_sk_local","TEST",true,builder));
    }

    @AfterEach void close() { ds.close(); }

    private void seller(String type, String status) {
        expect("sellers/seller1","""
            {"entityType":"seller","entityBody":{"id":"seller1","refSellerId":"campus17","businessType":"%s","status":"%s",
              "account":{"holderName":"never return this","accountNumber":"never persist this"}}}
            """.formatted(type,status));
    }

    private void payout(String reference, String recipient, int amount, String status) {
        expect("payouts/payout1","""
            {"entityType":"payout","entityBody":{"id":"payout1","refPayoutId":"%s","destination":"%s",
              "amount":{"currency":"KRW","value":%d},"status":"%s"}}
            """.formatted(reference,recipient,amount,status));
    }

    private void expect(String endpoint,String body) {
        remote.expect(requestTo("https://api.tosspayments.com/v2/"+endpoint)).andExpect(method(HttpMethod.GET))
              .andExpect(header("Authorization","Basic dGVzdF9za19sb2NhbDo="))
              .andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
    }

    @Test void verifiedCorporateTestPayoutIsLinkedAndRefreshedExactlyOnce() {
        seller("CORPORATE","PARTIALLY_APPROVED");
        payout("campus_1","seller1",10000,"REQUESTED");
        payout("campus_1","seller1",10000,"COMPLETED");
        payout("campus_1","seller1",10000,"REQUESTED");
        service.registerSeller(17,"seller1","campus17");
        assertEquals("REQUESTED",service.link("campus_1","payout1").status());
        assertEquals(403,assertThrows(ResponseStatusException.class,()->service.refresh("campus_1",99)).getStatusCode().value());
        assertEquals("COMPLETED",service.refresh("campus_1",17).status());
        assertThrows(ResponseStatusException.class,()->service.link("campus_1","anotherPayout"));
        assertThrows(ResponseStatusException.class,()->service.refresh("campus_1",17));
        assertEquals("COMPLETED",service.view("campus_1",17).status());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM toss_test_payout_links",Integer.class));
        remote.verify();
    }

    @Test void mismatchedOrderSellerAmountAndUnconfirmedEscrowCannotBeLinked() {
        seller("CORPORATE","PARTIALLY_APPROVED");
        payout("anotherOrder","seller1",10000,"COMPLETED");
        payout("campus_1","anotherSeller",10000,"COMPLETED");
        payout("campus_1","seller1",9999,"COMPLETED");
        service.registerSeller(17,"seller1","campus17");
        for(int i=0;i<3;i++) assertThrows(ResponseStatusException.class,()->service.link("campus_1","payout1"));
        db.update("UPDATE toss_escrow_orders SET status='IN_PROGRESS'");
        assertThrows(ResponseStatusException.class,()->service.link("campus_1","payout1"));
        assertFalse(service.view("campus_1",17).linked());
        remote.verify();
    }

    @Test void liveKeyModeAndIndividualSellersAreRejected() {
        assertFalse(new TestPayoutGateway("live_sk_no","TEST",true).enabled());
        assertFalse(new TestPayoutGateway("test_sk_no","LIVE",true).enabled());
        seller("INDIVIDUAL","PARTIALLY_APPROVED");
        seller("CORPORATE","KYC_REQUIRED");
        assertThrows(ResponseStatusException.class,()->service.registerSeller(17,"seller1","campus17"));
        assertThrows(ResponseStatusException.class,()->service.registerSeller(17,"seller1","campus17"));
        remote.verify();
    }
}
