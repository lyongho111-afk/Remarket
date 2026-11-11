package com.campus.remarket.escrow;

import com.campus.remarket.auth.MarketMember;
import com.campus.remarket.portal.StaffAccess;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class TossDirectEscrowController {
    private final MarketMember member;
    private final StaffAccess csrf;
    private final TossDirectEscrowService escrow;
    public TossDirectEscrowController(MarketMember member, StaffAccess csrf,TossDirectEscrowService escrow){
        this.member=member;this.csrf=csrf;this.escrow=escrow;
    }
    public record Page(TossDirectEscrowService.View escrow,String csrfToken){}
    public record Receipt(String receiverName){}
    public record EventBody(String id,String targetId){}
    public record Notice(String eventType,EventBody entityBody){}
    private Page response(TossDirectEscrowService.View v){return new Page(v,csrf.csrf());}
    private String actor(String id){member.trade(id);return member.studentKey();}
    private String mutate(String id,String token){String a=actor(id);csrf.verify(token);return a;}

    @GetMapping("/api/trades/{id}/toss-escrow")
    public Page view(@PathVariable String id){return response(escrow.view(id,actor(id)));}
    @PostMapping("/api/trades/{id}/toss-escrow/refresh")
    public Page refresh(@PathVariable String id,@RequestHeader(value="X-CSRF-Token",required=false) String token){
        return response(escrow.refresh(id,mutate(id,token)));
    }
    @PostMapping("/api/trades/{id}/toss-escrow/receive")
    public Page receive(@PathVariable String id,@RequestHeader(value="X-CSRF-Token",required=false) String token,
                        @RequestBody Receipt receipt){
        return response(escrow.receive(id,mutate(id,token),receipt.receiverName()));
    }
    @PostMapping("/api/trades/{id}/toss-escrow/deliver")
    public Page deliver(@PathVariable String id,@RequestHeader(value="X-CSRF-Token",required=false) String token){
        return response(escrow.deliver(id,mutate(id,token)));
    }
    @PostMapping("/api/payments/webhook/toss-escrow")
    public ResponseEntity<Void> webhook(@RequestBody Notice notice){
        if(!"escrow.changed".equals(notice.eventType()) && !"delivery.changed".equals(notice.eventType()))
            return ResponseEntity.ok().build();
        if(notice.entityBody()==null)return ResponseEntity.badRequest().build();
        escrow.webhook(notice.eventType(),notice.entityBody().id(),notice.entityBody().targetId());
        return ResponseEntity.ok().build();
    }
}
