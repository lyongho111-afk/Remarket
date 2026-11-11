package com.campus.remarket.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
public class CampusAuthenticationConfig {
    @Bean
    public DaoAuthenticationProvider campusCredentialProvider(CampusUserDetailsService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return provider;
    }

    @Bean
    public AuthenticationManager campusAuthenticationManager(DaoAuthenticationProvider campusCredentialProvider) {
        // ProviderManager erases the submitted password and the principal's BCrypt hash on success.
        return new ProviderManager(campusCredentialProvider);
    }
}
