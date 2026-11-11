package com.campus.remarket.auth;

import demo.campus.campusremarketmember.member.repository.MemberRepository;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
public class CampusUserDetailsService implements UserDetailsService {
    private final MemberRepository members;

    public CampusUserDetailsService(MemberRepository members) {
        this.members = members;
    }

    @Override
    public CampusPrincipal loadUserByUsername(String accountKey) throws UsernameNotFoundException {
        var account = members.findByAccountKey(accountKey)
                .orElseThrow(() -> new UsernameNotFoundException("회원 계정을 찾을 수 없습니다."));
        return new CampusPrincipal(account.getId(), account.getAccountKey(), account.getPassword(),
                account.getRole().name());
    }
}
