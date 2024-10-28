package io.micronaut.security.csrf.generator;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Requires;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.util.StringUtils;
import io.micronaut.http.*;
import io.micronaut.http.annotation.*;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.cookie.Cookie;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.csrf.CsrfConfiguration;
import io.micronaut.security.csrf.repository.CsrfTokenRepository;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.session.SessionIdResolver;
import io.micronaut.security.testutils.authprovider.MockAuthenticationProvider;
import io.micronaut.security.testutils.authprovider.SuccessAuthenticationScenario;
import io.micronaut.security.token.cookie.TokenCookieConfigurationProperties;
import io.micronaut.security.utils.HMacUtils;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.Test;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.micronaut.security.csrf.generator.DefaultCsrfTokenGenerator.hmacMessagePayload;
import static org.junit.jupiter.api.Assertions.*;

@Property(name = "micronaut.security.authentication", value = "cookie")
@Property(name = "micronaut.security.token.jwt.signatures.secret.generator.secret", value = "pleaseChangeThisSecretForANewOne")
@Property(name = "micronaut.security.csrf.signature-key", value = "pleaseChangeThisSecretForANewOnekoqQ-EstJQLr_T-1qS0gZH75aKtMN3Yj0iPS4hcgUuTwjAzZr1Z9CAow")
@Property(name = "micronaut.security.redirect.enabled", value = StringUtils.FALSE)
@Property(name = "micronaut.security.csrf.token-resolvers.field.enabled", value = StringUtils.FALSE)
@Property(name = "micronaut.security.csrf.filter.regex-pattern", value = "^(?!\\/login).*$")
@Property(name = "spec.name", value = "CsrfDoubleSubmitCookiePatternWithHeaderTest")
@MicronautTest
class CsrfDoubleSubmitCookiePatternWithHeaderTest {
    public static final String FIX_SESSION_ID = "123456789";

    @Test
    void loginSavesACsrfTokenInCookie(@Client("/") HttpClient httpClient,
                                      CsrfConfiguration csrfConfiguration) throws NoSuchAlgorithmException, InvalidKeyException {
        BlockingHttpClient client = httpClient.toBlocking();

        HttpRequest<?> loginRequest = HttpRequest.POST("/login",Map.of("username",  "sherlock", "password", "password"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE);

        HttpResponse<?> loginRsp = assertDoesNotThrow(() -> client.exchange(loginRequest));
        assertEquals(HttpStatus.OK, loginRsp.getStatus());
        Optional<Cookie> cookieJwtOptional = loginRsp.getCookie("JWT");
        assertTrue(cookieJwtOptional.isPresent());
        Cookie cookieJwt = cookieJwtOptional.get();
        String csrfTokenCookieName = "__Host-csrfToken";
        Optional<Cookie> cookieCsrfTokenOptional = loginRsp.getCookie(csrfTokenCookieName);
        assertTrue(cookieCsrfTokenOptional.isPresent());
        Cookie cookieCsrfToken = cookieCsrfTokenOptional.get();

        // CSRF Only in the cookie, not in the request headers or field, request is denied
        String csrfTokenHeader = "";
        assertDenied(client, cookieJwt.getValue(), csrfTokenCookieName,  new PasswordChange("sherlock", "evil"), cookieCsrfToken.getValue(), csrfTokenHeader);

        // CSRF Token in request and in cookie don't match, request is unauthorized
        String csrfToken = "abcdefg";
        assertNotEquals(cookieCsrfToken.getValue(), csrfToken);
        PasswordChange formWithCsrfToken = new PasswordChange("sherlock", "evil");
        csrfTokenHeader = csrfToken;
        assertDenied(client, cookieJwt.getValue(), csrfTokenCookieName, formWithCsrfToken, cookieCsrfToken.getValue(), csrfTokenHeader);

        // CSRF Token with HMAC but not session id feed into HMAC calculation, request is unauthorized
        String randomValue = "abcdefg";
        String hmac = HMacUtils.base64EncodedHmacSha256(randomValue, csrfConfiguration.getSecretKey());
        String csrfTokenCalculatedWithoutSessionId = hmac + "." + randomValue;
        PasswordChange body = new PasswordChange("sherlock", "evil");
        assertDenied(client, cookieJwt.getValue(), csrfTokenCookieName, body, csrfTokenCalculatedWithoutSessionId, csrfTokenCalculatedWithoutSessionId);

        String message = hmacMessagePayload(FIX_SESSION_ID, randomValue);
        hmac = HMacUtils.base64EncodedHmacSha256(message, csrfConfiguration.getSecretKey());
        csrfToken = hmac + "." + randomValue;
        assertOk(client, cookieJwt.getValue(), csrfTokenCookieName, csrfToken);

        // Even if you have the same session id and random value, the attacker cannot generate the same hmac as he does not have the same secret key
        String evilSignatureKey = "evilAyM1SysPpbyDfgZld3umj1qzKObwVMkoqQ-EstJQLr_T-1qS0gZH75aKtMN3Yj0iPS4hcgUuTwjAzZr1Z9CAowevil";
        csrfToken = HMacUtils.base64EncodedHmacSha256(message, evilSignatureKey) + "." + randomValue;
        assertDenied(client, cookieJwt.getValue(), csrfTokenCookieName, new PasswordChange("sherlock", "evil"), csrfToken, csrfToken);
        // invalid csrf but content type not intercepted by the filter.
        assertOk(client, cookieJwt.getValue(), csrfTokenCookieName, csrfToken, MediaType.APPLICATION_JSON_TYPE);

        // CSRF Token in request match token in cookie and hmac signature is valid.
        csrfToken = cookieCsrfToken.getValue();
        assertOk(client, cookieJwt.getValue(), csrfTokenCookieName, csrfToken);

        // Default CSRF Token validator expects the CSRF token to have a dot
        csrfToken= csrfToken.replace(".", "");
        assertDenied(client, cookieJwt.getValue(), csrfTokenCookieName, new PasswordChange("sherlock", "evil"), csrfToken, csrfToken);
    }

    private void assertDenied(BlockingHttpClient client, String cookieJwt, String csrfTokenCookieName, PasswordChange body, String csrfToken, String csrfTokenHeader) {
        HttpRequest<?> request = HttpRequest.POST("/password/change", body)
            .header("X-CSRF-TOKEN", csrfTokenHeader)
            .cookie(Cookie.of(TokenCookieConfigurationProperties.DEFAULT_COOKIENAME, cookieJwt))
            .cookie(Cookie.of(csrfTokenCookieName, csrfToken))
            .accept(MediaType.TEXT_HTML)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED_TYPE);
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () -> client.retrieve(request));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
    }

    private void assertOk(BlockingHttpClient client, String cookieJwt, String csrfTokenCookieName, String csrfToken) {
        assertOk(client, cookieJwt, csrfTokenCookieName, csrfToken, MediaType.APPLICATION_FORM_URLENCODED_TYPE);
    }

    private void assertOk(BlockingHttpClient client, String cookieJwt, String csrfTokenCookieName, String csrfToken, MediaType contentType) {
        PasswordChange body = new PasswordChange("sherlock", "evil");
        HttpRequest<?> request = HttpRequest.POST("/password/change", body)
            .header("X-CSRF-TOKEN", csrfToken)
            .cookie(Cookie.of(TokenCookieConfigurationProperties.DEFAULT_COOKIENAME, cookieJwt))
            .cookie(Cookie.of(csrfTokenCookieName, csrfToken))
            .accept(MediaType.TEXT_HTML)
            .contentType(contentType);

        HttpResponse<String> response = assertDoesNotThrow(() -> client.exchange(request, String.class));
        assertEquals(HttpStatus.OK, response.getStatus());
    }

    @Requires(property = "spec.name", value = "CsrfDoubleSubmitCookiePatternWithHeaderTest")
    @Singleton
    static class MockSessionIdResolver implements SessionIdResolver<HttpRequest<?>> {
        @Override
        @NonNull
        public Optional<String> findSessionId(@NonNull HttpRequest<?> request) {
            return Optional.of(FIX_SESSION_ID);
        }
    }

    @Requires(property = "spec.name", value = "CsrfDoubleSubmitCookiePatternWithHeaderTest")
    @Singleton
    static class AuthenticationProviderUserPassword extends MockAuthenticationProvider {
        AuthenticationProviderUserPassword() {
            super(List.of(new SuccessAuthenticationScenario("sherlock")));
        }
    }

    @Requires(property = "spec.name", value = "CsrfDoubleSubmitCookiePatternWithHeaderTest")
    @Controller
    static class PasswordChangeController {
        @Secured(SecurityRule.IS_ANONYMOUS)
        @Produces(MediaType.TEXT_HTML)
        @Consumes({ MediaType.APPLICATION_FORM_URLENCODED, MediaType.APPLICATION_JSON })
        @Post("/password/change")
        String changePassword(@Body PasswordChange passwordChangeForm) {
            return passwordChangeForm.username;
        }
    }

    @Serdeable
    record PasswordChange(
            String username,
            String password) {
    }

    @Requires(property = "spec.name", value = "CsrfDoubleSubmitCookiePatternWithHeaderTest")
    @Controller("/csrf")
    static class CsrfTokenEchoController {

        private final CsrfTokenRepository<HttpRequest<?>> csrfTokenRepository;

        CsrfTokenEchoController(CsrfTokenRepository<HttpRequest<?>> csrfTokenRepository) {
            this.csrfTokenRepository = csrfTokenRepository;
        }

        @Secured(SecurityRule.IS_ANONYMOUS)
        @Produces(MediaType.TEXT_PLAIN)
        @Get("/echo")
        Optional<String> echo(HttpRequest<?> request) {
            return csrfTokenRepository.findCsrfToken(request);
        }
    }
}
