package com.campus.remarket.auth;

import java.io.Serial;
import java.util.List;
import demo.campus.campusremarketmember.member.entity.Member;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;

/** An authenticated session carries the account ID and role, never a JPA entity. */
public final class CampusPrincipal extends User {
    @Serial
    private static final long serialVersionUID = 1L;
    private final long memberId;

    public CampusPrincipal(long memberId, String accountKey, String encodedPassword, String role) {
        super(accountKey, encodedPassword, List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        this.memberId = memberId;
    }

    public long memberId() {
        return memberId;
    }

    /** Refresh authorization from a live member row without copying its password hash. */
    public static CampusPrincipal forSession(Member account) {
        var principal = new CampusPrincipal(account.getId(), account.getAccountKey(), "", account.getRole().name());
        principal.eraseCredentials();
        return principal;
    }
}
