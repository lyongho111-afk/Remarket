package com.campus.remarket.payout;

import com.campus.remarket.auth.MarketMember;
import com.campus.remarket.portal.StaffAccess;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
public class TestPayoutController {
    private final TestPayoutVerification service;
    private final TestPayoutGateway gateway;
    private final StaffAccess access;
    private final MarketMember member;

    public TestPayoutController(TestPayoutVerification service, TestPayoutGateway gateway, StaffAccess access, MarketMember member) {
        this.service = service;
        this.gateway = gateway;
        this.access = access;
        this.member = member;
    }
    public record SellerLink(long memberId, String sellerId, String refSellerId) {}
    public record OrderLink(String orderId,String payoutId) {}
    public record Page(TestPayoutVerification.View payout, String csrfToken) {}

    @GetMapping("/api/admin/test-payouts/session")
    public Map<String,Object> session() {
        access.admin();
        return Map.of("enabled",gateway.enabled(),"csrfToken",access.csrf());
    }

    @PostMapping("/api/admin/test-payouts/seller")
    public Map<String,Boolean> seller(@RequestBody SellerLink link, @RequestHeader("X-CSRF-Token") String csrf) {
        access.admin(); access.verify(csrf);
        service.registerSeller(link.memberId(),link.sellerId(),link.refSellerId());
        return Map.of("linked",true);
    }

    @PostMapping("/api/admin/test-payouts/order")
    public TestPayoutVerification.View order(@RequestBody OrderLink link,@RequestHeader("X-CSRF-Token") String csrf) {
        access.admin(); access.verify(csrf);
        return service.link(link.orderId(),link.payoutId());
    }

    @GetMapping("/api/trades/{id}/test-payout")
    public Page view(@PathVariable String id) {
        member.trade(id);
        return new Page(service.view(id,member.current().getId()),access.csrf());
    }

    @PostMapping("/api/trades/{id}/test-payout/refresh")
    public Page refresh(@PathVariable String id, @RequestHeader("X-CSRF-Token") String csrf) {
        member.trade(id); access.verify(csrf);
        return new Page(service.refresh(id,member.current().getId()),access.csrf());
    }
}
