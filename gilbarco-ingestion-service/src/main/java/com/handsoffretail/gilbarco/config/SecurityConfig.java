package com.handsoffretail.gilbarco.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;

import com.handsoffretail.auth.client.CsrfHeaderFilter;
import com.handsoffretail.auth.client.HorHttpSecurity;
import com.handsoffretail.auth.client.ProblemResponses;
import com.handsoffretail.auth.client.RequestLoggingFilter;

/**
 * The hor-auth resource-server chain (cookie {@code hor_access}, {@code X-HOR-Request} + Origin on writes,
 * problem-detail errors) with this service's rule: every endpoint is PLATFORM_ADMIN only.
 * Authorities are the bare role claim (no ROLE_ prefix), hence {@code hasAuthority}.
 * The JwtDecoder comes from auth-client (JWKS of hor-auth); tests replace it with a bean of their own.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";

    @Bean
    SecurityFilterChain ingestionFilterChain(HttpSecurity http, BearerTokenResolver tokenResolver,
            CsrfHeaderFilter csrfHeaderFilter, RequestLoggingFilter requestLoggingFilter) throws Exception {
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/ingestion/gilbarco/**").hasAuthority(PLATFORM_ADMIN)
                        .requestMatchers("/error").permitAll()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(ProblemResponses.authenticationEntryPoint())
                        .bearerTokenResolver(tokenResolver)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(HorHttpSecurity.jwtAuthenticationConverter())))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(ProblemResponses.authenticationEntryPoint())
                        .accessDeniedHandler(ProblemResponses.accessDeniedHandler()))
                .addFilterAfter(requestLoggingFilter, SecurityContextHolderFilter.class)
                .addFilterBefore(csrfHeaderFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
