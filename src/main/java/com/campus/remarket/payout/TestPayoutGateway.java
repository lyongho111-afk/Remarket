package com.campus.remarket.payout;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

/** Read-only access to the same merchant's TEST environment. No transfer operation. */
@Component
public class TestPayoutGateway {
    private final RestClient http;
    private final boolean enabled;

    @Autowired
    public TestPayoutGateway(@Value("${integrations.toss-secret-key:}") String secret,
                             @Value("${TOSS_PAYMENT_MODE:TEST}") String mode,
                             @Value("${TOSS_TEST_PAYOUT_LOOKUP_ENABLED:false}") boolean optIn) {
        this(secret, mode, optIn, client());
    }

    TestPayoutGateway(String secret, String mode, boolean optIn, RestClient.Builder builder) {
        enabled = optIn && "TEST".equals(mode) && secret.startsWith("test_sk_");
        http = builder.baseUrl("https://api.tosspayments.com/v2")
            .defaultHeaders(h -> h.setBasicAuth(enabled ? secret : "", "", StandardCharsets.UTF_8)).build();
    }

    private static RestClient.Builder client() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(6));
        return RestClient.builder().requestFactory(factory);
    }

    public boolean enabled() { return enabled; }

    JsonNode seller(String sellerId) { return lookup("/sellers/{id}", "seller", sellerId); }
    JsonNode payout(String payoutId) { return lookup("/payouts/{id}", "payout", payoutId); }

    private JsonNode lookup(String route, String type, String id) {
        if (!enabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "테스트 지급 조회 설정과 계약 상점의 테스트 키를 확인해 주세요.");
        if (id == null || id.isBlank() || id.length() > 64 || id.indexOf('/') >= 0 ||
                id.indexOf('\\') >= 0 || id.equals(".") || id.equals("..") || id.chars().anyMatch(Character::isISOControl))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "토스 식별자를 확인해 주세요.");
        try {
            var response = http.get().uri(route,id).retrieve().body(JsonNode.class);
            if (response != null && type.equals(response.path("entityType").asString(""))) {
                var body = response.path("entityBody");
                if (body.isObject() && id.equals(body.path("id").asString(""))) return body;
            }
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "토스 조회 응답의 유형 또는 식별자가 일치하지 않습니다.");
        } catch (RestClientException ex) {
            // Provider errors can contain merchant or personal data.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "토스에서 조회할 수 없습니다. 상점 권한과 식별자를 확인하세요.");
        }
    }
}
