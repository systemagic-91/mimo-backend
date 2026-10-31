package br.com.luma.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import br.com.luma.shared.exception.ApiException;
import jakarta.servlet.http.Cookie;

class RefreshTokenCookieTests {

    private final RefreshTokenCookie cookie = new RefreshTokenCookie("luma_refresh", true, "Strict", Duration.ofDays(30));

    @Test
    void writesAnHttpOnlySecureStrictCookieScopedToAuthRoutes() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookie.write(response, "token-value");

        assertThat(response.getHeader("Set-Cookie"))
                .startsWith("luma_refresh=token-value; Path=/api/v1/auth; Max-Age=2592000;")
                .endsWith("; Secure; HttpOnly; SameSite=Strict");
    }

    @Test
    void clearingExpiresTheCookieImmediately() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookie.clear(response);

        assertThat(response.getHeader("Set-Cookie"))
                .startsWith("luma_refresh=; Path=/api/v1/auth; Max-Age=0;")
                .contains("HttpOnly", "Secure", "SameSite=Strict");
    }

    @Test
    void secureAndSameSiteAreConfigurable() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RefreshTokenCookie("dev_refresh", false, "Lax", Duration.ofHours(1)).write(response, "x");

        assertThat(response.getHeader("Set-Cookie"))
                .startsWith("dev_refresh=x; Path=/api/v1/auth; Max-Age=3600;")
                .doesNotContain("Secure")
                .contains("SameSite=Lax");
    }

    @Test
    void readsOnlyTheConfiguredCookie() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThat(cookie.read(request)).isEmpty();

        request.setCookies(new Cookie("other", "x"), new Cookie("luma_refresh", "token-value"));
        assertThat(cookie.read(request)).contains("token-value");

        request.setCookies(new Cookie("luma_refresh", "a".repeat(256)));
        assertThat(cookie.read(request)).isEmpty();
    }

    @Test
    void originGuardRejectsOnlyUnknownOrigins() {
        OriginGuard guard = new OriginGuard("http://localhost:5173, https://mimo.vercel.app");
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatCode(() -> guard.check(request)).doesNotThrowAnyException();
        request.addHeader("Origin", "https://mimo.vercel.app");
        assertThatCode(() -> guard.check(request)).doesNotThrowAnyException();

        MockHttpServletRequest attack = new MockHttpServletRequest();
        attack.addHeader("Origin", "https://evil.example");
        assertThatThrownBy(() -> guard.check(attack))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus().value()).isEqualTo(403);
                    assertThat(exception.getCode()).isEqualTo("ORIGIN_NOT_ALLOWED");
                });
    }
}
