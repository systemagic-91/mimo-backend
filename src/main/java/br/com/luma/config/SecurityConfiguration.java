package br.com.luma.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    @Bean
        SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter)
                        throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers("/api/v1/auth/login", "/api/v1/auth/refresh")
                        .permitAll()
                        .requestMatchers("/api/v1/dashboard/**", "/api/v1/reports/**", "/api/v1/settings/**")
                        .hasRole("OWNER")
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .build();
    }
}