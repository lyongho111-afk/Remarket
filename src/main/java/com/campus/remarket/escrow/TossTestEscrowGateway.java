package com.campus.remarket.escrow;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/** Real merchant API, hard-gated to matching test credentials. Never initiates a payout. */
@Component
public class TossTestEscrowGateway {
    private static final java.util.regex.Pattern UNSAFE_ESCAPE =
            java.util.regex.Pattern.compile("(?i)%(?:0[0-9a-f]|1[0-9a-f]|7f|2e|2f|5c|25)");
    public record Product(String id) {}
    public record Escrow(String id,String targetType,String targetId,String status,
                         List<Product> products,List<String> deliveryIds) {}
    public record Delivery(String id,String targetType,String targetId,String status,String deliveryMethod) {}
    public record EscrowEnvelope(Escrow entityBody) {}
    public record DeliveryEnvelope(Delivery entityBody) {}
    private final boolean enabled;
    private final RestClient client;

    @Autowired
    public TossTestEscrowGateway(@Value("${TOSS_TEST_ESCROW_ENABLED:false}") boolean optedIn,
            @Value("${TOSS_PAYMENT_MODE:TEST}") String mode,
            @Value("${integrations.toss-client-key:}") String clientKey,
            @Value("${integrations.toss-secret-key:}") String secretKey) {
        this(optedIn,mode,clientKey,secretKey,factory());
    }
    TossTestEscrowGateway(boolean optedIn,String mode,String ck,String sk,RestClient.Builder builder) {
        enabled=optedIn && "TEST".equals(mode) && ck.startsWith("test_ck_") && sk.startsWith("test_sk_");
        client=builder.baseUrl("https://api.tosspayments.com/v2")
            .defaultHeaders(headers->headers.setBasicAuth(sk,"",StandardCharsets.UTF_8)).build();
    }
    private static RestClient.Builder factory(){
        var f=new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(2));f.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(f);
    }
    public boolean enabled(){return enabled;}
    public static boolean validProviderId(String id){
        if(id==null || id.isBlank() || id.length()>200 || id.equals(".") || id.equals(".."))return false;
        // A provider ID is one opaque path segment; reject path/control escapes before interpolating it.
        return id.chars().noneMatch(c->Character.isISOControl(c) || c=='/' || c=='\\')
                && !UNSAFE_ESCAPE.matcher(id).find();
    }
    private void requireId(String id){
        if(!validProviderId(id))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"토스 식별자가 올바르지 않습니다.");
    }
    private void requireEnabled(){
        if(!enabled)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"토스 테스트 에스크로 설정이 필요합니다.");
    }
    private ResponseStatusException unavailable(){
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"토스 테스트 상점의 에스크로 권한과 거래 상태를 확인해 주세요.");
    }
    public Escrow fetch(String escrowId){
        requireEnabled();requireId(escrowId);
        try{
            var response=client.get().uri("/escrows/{id}",escrowId).retrieve().body(EscrowEnvelope.class);
            if(response==null || response.entityBody()==null)throw unavailable();
            return response.entityBody();
        }catch(RestClientException ex){throw unavailable();}
    }
    public Delivery getDelivery(String id){
        requireEnabled();requireId(id);
        try{
            var response=client.get().uri("/deliveries/{id}",id).retrieve().body(DeliveryEnvelope.class);
            if(response==null || response.entityBody()==null)throw unavailable();
            return response.entityBody();
        }catch(RestClientException ex){throw unavailable();}
    }
    public Delivery direct(String productId,String receiver,String receivedAt,String idem){
        requireEnabled();requireId(productId);
        try{
            var body=Map.of("targetType","ESCROW_PRODUCT","targetId",productId,"deliveryMethod","DIRECT",
                    "direct",Map.of("receivedAt",receivedAt,"receiverName",receiver,"receiverRelation","BUYER"));
            var response=client.post().uri("/deliveries").header("Idempotency-Key",idem)
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(DeliveryEnvelope.class);
            if(response==null || response.entityBody()==null)throw unavailable();
            return response.entityBody();
        }catch(RestClientException ex){throw unavailable();}
    }
}
