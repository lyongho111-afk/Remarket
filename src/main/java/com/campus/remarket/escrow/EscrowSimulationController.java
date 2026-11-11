package com.campus.remarket.escrow;

import com.campus.remarket.auth.MarketMember;
import com.campus.remarket.portal.StaffAccess;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/trades/{id}/escrow-simulation")
public class EscrowSimulationController {
    private final MarketMember member;
    private final StaffAccess access;
    private final EscrowSimulationService escrow;

    public EscrowSimulationController(MarketMember member, StaffAccess access, EscrowSimulationService escrow) {
        this.member = member;
        this.access = access;
        this.escrow = escrow;
    }

    public record Page(EscrowSimulationService.Ledger ledger, String csrfToken) {}

    @GetMapping
    public Page view(@PathVariable String id) {
        member.trade(id);
        return new Page(escrow.view(id, member.studentKey()), access.csrf());
    }

    @PostMapping("/confirm")
    public Page confirm(@PathVariable String id, @RequestHeader(value="X-CSRF-Token", required=false) String token) {
        member.trade(id);
        access.verify(token);
        return new Page(escrow.confirm(id, member.studentKey()), access.csrf());
    }

    @PostMapping("/settle")
    public Page settle(@PathVariable String id, @RequestHeader(value="X-CSRF-Token", required=false) String token) {
        member.trade(id);
        access.verify(token);
        return new Page(escrow.settle(id, member.studentKey()), access.csrf());
    }
}
