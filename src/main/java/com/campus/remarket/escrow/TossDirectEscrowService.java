package com.campus.remarket.escrow;

import com.campus.remarket.dao.TradeDao;
import com.campus.remarket.dto.TradeDto;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TossDirectEscrowService {
    private final TradeDao orders;
    private final JdbcTemplate db;
    private final TossTestEscrowGateway gateway;
    public TossDirectEscrowService(TradeDao orders,JdbcTemplate db,TossTestEscrowGateway gateway){
        this.orders=orders;this.db=db;this.gateway=gateway;
    }

    public record View(String status,boolean received,boolean delivered,boolean selected){}
    private record Entry(String escrow,String product,String status,String delivery,OffsetDateTime received,String receiver){}
    private Entry entry(String id){
        return db.query("SELECT escrow_id,product_id,status,delivery_id,received_at,receiver_name FROM toss_escrow_orders WHERE order_id=?",
                (r,n)->new Entry(r.getString(1),r.getString(2),r.getString(3),r.getString(4),
                        r.getObject(5,OffsetDateTime.class),r.getString(6)),id).stream().findFirst().orElse(null);
    }
    private View state(String id){
        var e=entry(id);
        return e==null?new View("NOT_SELECTED",false,false,false):new View(e.status,e.received!=null,e.delivery!=null,true);
    }
    private TradeDto order(String id){
        if(orders.lockOne(id)==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"거래를 찾을 수 없습니다.");
        return orders.selectOne(id);
    }
    private void participant(TradeDto o,String actor){
        if(!Objects.equals(actor,o.getBuyer())&&!Objects.equals(actor,o.getSeller()))throw forbidden();
    }
    private ResponseStatusException forbidden(){return new ResponseStatusException(HttpStatus.FORBIDDEN,"해당 거래의 당사자만 사용할 수 있습니다.");}
    private ResponseStatusException invalid(){return new ResponseStatusException(HttpStatus.CONFLICT,"토스 에스크로 거래·상품·배송 상태를 확인해 주세요.");}

    @Transactional public View view(String id,String actor){participant(order(id),actor);return state(id);}
    @Transactional public View refresh(String id,String actor){var o=order(id);participant(o,actor);sync(o);return state(id);}

    /** Webhook payload selects a known resource only. Always re-read state from the merchant API. */
    @Transactional public void webhook(String type,String resource,String target){
        String condition=switch(type){
            case "escrow.changed" -> "escrow_id=?";
            case "delivery.changed" -> "product_id=?";
            default -> null;
        };
        if(condition==null)return;
        String key=type.equals("escrow.changed")?resource:target;
        if(!valid(key))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"웹훅 식별자가 필요합니다.");
        String id=db.query("SELECT order_id FROM toss_escrow_orders WHERE "+condition,(r,n)->r.getString(1),key).stream().findFirst().orElse(null);
        if(id!=null)sync(order(id));
    }
    private static boolean valid(String id){return TossTestEscrowGateway.validProviderId(id);}

    private void sync(TradeDto o){
        String id=o.getOrderId();
        var e=entry(id);
        if(e==null||e.escrow==null)return;
        if(!gateway.enabled())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"토스 테스트 에스크로가 꺼져 있습니다.");
        var keys=db.query("SELECT payment_key FROM payment_receipts WHERE order_id=? AND payment_mode='TEST' AND total_amount=?",
                (r,n)->r.getString(1),id,o.getAgreedPrice());
        if(keys.size()!=1)throw invalid();
        var remote=gateway.fetch(e.escrow);
        if(remote==null||!e.escrow.equals(remote.id())||!"PAYMENT".equals(remote.targetType())||!keys.getFirst().equals(remote.targetId())
                ||remote.status()==null||!Set.of("IN_PROGRESS","CANCEL_REQUESTED","CONFIRMED","CANCELED").contains(remote.status())
                ||remote.products()==null||remote.products().size()!=1||remote.products().getFirst()==null
                ||!valid(remote.products().getFirst().id())||remote.deliveryIds()==null||remote.deliveryIds().size()>1)throw invalid();
        String product=remote.products().getFirst().id();
        if(e.product!=null&&!e.product.equals(product))throw invalid();
        if("CANCELED".equals(e.status)&&!"CANCELED".equals(remote.status())
                ||"CONFIRMED".equals(e.status)&&!Set.of("CONFIRMED","CANCELED").contains(remote.status())
                ||"CANCEL_REQUESTED".equals(e.status)&&"IN_PROGRESS".equals(remote.status()))throw invalid();
        String delivery=e.delivery;
        if(!remote.deliveryIds().isEmpty()){
            String remoteId=remote.deliveryIds().getFirst();
            if(!valid(remoteId)||(delivery!=null&&!delivery.equals(remoteId)))throw invalid();
            var d=gateway.getDelivery(remoteId);validateDelivery(d,product,remoteId);delivery=d.id();
        }
        db.update("UPDATE toss_escrow_orders SET product_id=?,status=?,delivery_id=?,verified_at=CURRENT_TIMESTAMP WHERE order_id=?",
                product,remote.status(),delivery,id);
    }
    private void validateDelivery(TossTestEscrowGateway.Delivery d,String product,String expectedId){
        if(d==null||!valid(d.id())||(expectedId!=null&&!expectedId.equals(d.id()))
                ||!"ESCROW_PRODUCT".equals(d.targetType())||!product.equals(d.targetId())
                ||!"DIRECT".equals(d.deliveryMethod())||!"COMPLETED".equals(d.status()))throw invalid();
    }

    @Transactional public View receive(String id,String actor,String receiver){
        var o=order(id);if(!Objects.equals(actor,o.getBuyer()))throw forbidden();
        if(receiver==null||receiver.isBlank()||receiver.strip().length()>50)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"수령인 이름을 확인하세요.");
        sync(o);var e=entry(id);
        if(e==null||e.escrow==null)throw invalid();
        if(e.received!=null)return state(id);
        if(!"IN_PROGRESS".equals(e.status))throw invalid();
        db.update("UPDATE toss_escrow_orders SET receiver_name=?,received_at=? WHERE order_id=?",receiver.strip(),
                OffsetDateTime.now(ZoneOffset.ofHours(9)).truncatedTo(ChronoUnit.SECONDS),id);
        return state(id);
    }

    @Transactional public View deliver(String id,String actor){
        var o=order(id);if(!Objects.equals(actor,o.getSeller()))throw forbidden();
        sync(o);var e=entry(id);
        if(e==null||e.escrow==null||e.received==null)throw invalid();
        if(e.delivery!=null)return state(id);
        if(!"IN_PROGRESS".equals(e.status)||!valid(e.product))throw invalid();
        String receivedAt=e.received.withOffsetSameInstant(ZoneOffset.ofHours(9))
                .format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX"));
        var d=gateway.direct(e.product,e.receiver,receivedAt,"direct-"+id);
        validateDelivery(d,e.product,null);
        db.update("UPDATE toss_escrow_orders SET delivery_id=? WHERE order_id=?",d.id(),id);
        return state(id);
    }
}
