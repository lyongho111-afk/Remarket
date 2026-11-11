package com.campus.remarket.service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** The merchant API is the source of truth; browser callbacks and webhook bodies are only signals. */
@Component
public class TossPaymentGateway {
    public record Payment(String orderId, String paymentKey, Integer totalAmount,
                          Integer balanceAmount, String currency, String status, String lastTransactionKey,
                          Boolean useEscrow, String escrowKey) {
        public Payment(String id,String key,Integer total,Integer balance,String currency,String status,String last) {
            this(id,key,total,balance,currency,status,last,false,null);
        }
    }

    private final String clientKey;
    private final String mode;
    private final boolean ready;
    private final RestClient http;

    @Autowired
    public TossPaymentGateway(@Value("${integrations.toss-client-key:}") String clientKey,
                              @Value("${integrations.toss-secret-key:}") String secretKey,
                              @Value("${TOSS_PAYMENT_MODE:TEST}") String mode,
                              @Value("${ENABLE_LIVE_PAYMENTS:false}") boolean enableLive) {
        this(clientKey,secretKey,mode,enableLive,merchantClient());
    }

    private static RestClient.Builder merchantClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(4));
        factory.setReadTimeout(Duration.ofSeconds(12));
        return RestClient.builder().requestFactory(factory);
    }

    TossPaymentGateway(String clientKey,String secretKey,String mode,boolean enableLive,RestClient.Builder client) {
        this.clientKey = clientKey;
        this.mode = mode;
        this.ready = switch (mode) {
            case "TEST" -> clientKey.startsWith("test_ck_") && secretKey.startsWith("test_sk_");
            case "LIVE" -> enableLive && clientKey.startsWith("live_ck_") && secretKey.startsWith("live_sk_");
            default -> false;
        };
        http = client.baseUrl("https://api.tosspayments.com/v1")
                .defaultHeaders(headers -> headers.setBasicAuth(secretKey, "", StandardCharsets.UTF_8))
                .build();
    }

    public boolean ready() { return ready; }
    public String clientKey() { return ready ? clientKey : ""; }
    public String mode() { return mode; }

    public Payment confirm(String id, String key, int price) {
        requireReady();
        try {
            return http.post().uri("/payments/confirm").header("Idempotency-Key", id)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("paymentKey", key, "orderId", id, "amount", price))
                    .retrieve().body(Payment.class);
        } catch (RestClientException ex) {
            // The provider may have approved the payment before the network failed.
            // Resolve the same order without creating a second approval or marking it failed.
            return getByOrder(id);
        }
    }

    public Payment getByOrder(String id) {
        requireReady();
        try {
            return http.get().uri("/payments/orders/{id}", id).retrieve().body(Payment.class);
        } catch (RestClientException ex) {
            throw new IllegalStateException("토스 결제 내역을 아직 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
    }

    public void requireReady() {
        if (!ready) throw new IllegalStateException("결제 모드와 해당 토스페이먼츠 키 설정을 확인해 주세요.");
    }
}
