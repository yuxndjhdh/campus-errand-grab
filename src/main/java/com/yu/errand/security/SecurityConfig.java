package com.yu.errand.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yu.errand.common.ApiResponse;
import com.yu.errand.common.ErrorCode;
import com.yu.errand.monitoring.TraceIdFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    public UserDetailsService unusedFormLoginUserDetailsService() {
        return username -> { throw new UsernameNotFoundException(username); };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationFilter jwt,
                                                   TraceIdFilter traceId, ObjectMapper objectMapper) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**", "/api/payments/webhook", "/api/ping", "/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(jsonError(objectMapper, ErrorCode.UNAUTHORIZED))
                        .accessDeniedHandler(jsonDenied(objectMapper)))
                .addFilterBefore(traceId, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    private AuthenticationEntryPoint jsonError(ObjectMapper mapper, ErrorCode code) {
        return (request, response, exception) -> write(mapper, response, code, "authentication is required");
    }

    private AccessDeniedHandler jsonDenied(ObjectMapper mapper) {
        return (request, response, exception) -> write(mapper, response, ErrorCode.FORBIDDEN, "access is denied");
    }

    private void write(ObjectMapper mapper, jakarta.servlet.http.HttpServletResponse response,
                       ErrorCode code, String message) throws java.io.IOException {
        response.setStatus(code.httpStatus());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getWriter(), new ApiResponse<Void>(code.code(), message, null));
    }
}
