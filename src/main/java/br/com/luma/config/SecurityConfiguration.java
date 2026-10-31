package br.com.luma.config;

import java.util.Set;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

import br.com.luma.auth.web.PasswordChangeRequiredFilter;
import br.com.luma.shared.web.ProblemDetailWriter;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

    private static final String[] PUBLIC_AUTH_ROUTES = {
            "/api/v1/auth/login", "/api/v1/auth/refresh", "/api/v1/auth/logout" };

    // Nas rotas públicas de sessão o Authorization é ignorado: um access token vencido não pode impedir o logout.
    private static BearerTokenResolver bearerTokenResolver() {
        DefaultBearerTokenResolver delegate = new DefaultBearerTokenResolver();
        Set<String> publicRoutes = Set.of(PUBLIC_AUTH_ROUTES);
        return request -> publicRoutes.contains(request.getRequestURI()) ? null : delegate.resolve(request);
    }

    @Bean
            SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter,
                    ProblemDetailWriter problemDetailWriter)
                        throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> problemDetailWriter.write(
                                request, response, org.springframework.http.HttpStatus.UNAUTHORIZED,
                                "UNAUTHENTICATED", "Autenticação obrigatória."))
                        .accessDeniedHandler((request, response, exception) -> problemDetailWriter.write(
                                request, response, org.springframework.http.HttpStatus.FORBIDDEN,
                                "FORBIDDEN", "Você não tem permissão para acessar este recurso.")))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers(PUBLIC_AUTH_ROUTES)
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/photos/*")
                        .permitAll()
                        .requestMatchers("/actuator/metrics", "/actuator/metrics/**", "/actuator/prometheus")
                        .hasRole("OWNER")
                        .requestMatchers(HttpMethod.GET, "/api/v1/settings")
                        .hasAnyRole("OWNER", "STAFF")
                        .requestMatchers("/api/v1/dashboard/**", "/api/v1/reports/**", "/api/v1/settings/**",
                                "/api/v1/users", "/api/v1/users/**")
                        .hasRole("OWNER")
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .bearerTokenResolver(bearerTokenResolver())
                        .jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
                .addFilterAfter(new PasswordChangeRequiredFilter(problemDetailWriter),
                        BearerTokenAuthenticationFilter.class)
                .build();
    }
}