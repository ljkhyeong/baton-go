package com.personal.batongo.adapter.in.web.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.batongo.adapter.in.web.FilterErrorResponseWriter;
import com.personal.batongo.adapter.in.web.ManagementApiSecurityConfiguration;
import com.personal.batongo.adapter.in.web.RequestIdFilter;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example",
        "spring.security.oauth2.resourceserver.jwt.audiences=baton-go"
})
@ContextConfiguration(classes = ManagementApiSecurityConfiguration.class)
@Import({FilterErrorResponseWriter.class, RequestIdFilter.class})
@ExtendWith(OutputCaptureExtension.class)
class ManagementJwtServiceFailureHttpTest {

    private static final String JWK_FAILURE_BODY = "sensitive-jwk-service-error";
    private static HttpServer jwkServer;

    @Autowired
    private MockMvc mockMvc;

    @BeforeAll
    static void startJwkServer() throws IOException {
        jwkServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwkServer.createContext("/jwks", exchange -> {
            byte[] body = JWK_FAILURE_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        jwkServer.start();
    }

    @AfterAll
    static void stopJwkServer() {
        jwkServer.stop(0);
    }

    @DynamicPropertySource
    static void jwkSetUri(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:" + jwkServer.getAddress().getPort() + "/jwks");
    }

    @Test
    @DisplayName("JWK 조회 장애는 공통 500 응답을 반환하고 토큰과 원격 오류 원문을 기록하지 않는다")
    void handlesJwkServiceFailure(CapturedOutput output) throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keyPair = generator.generateKeyPair();
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("https://identity.example")
                .subject("test-client").audience(List.of("baton-go"))
                .issuedAt(now.minusSeconds(5)).expiresAt(now.plusSeconds(300))
                .claim("scope", "baton-go.links.read").build();
        String token = NimbusJwtEncoder.withKeyPair(
                        (RSAPublicKey) keyPair.getPublic(),
                        (RSAPrivateKey) keyPair.getPrivate())
                .jwkPostProcessor(key -> key.keyID("test-key"))
                .build().encode(JwtEncoderParameters.from(claims)).getTokenValue();
        String requestId = "jwk-service-failure-test";

        mockMvc.perform(get("/api/v1/links/83a430c4-5c5d-4eb4-a815-7a5ba1fd4aae")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("X-Request-Id", requestId))
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Request-Id", requestId))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("서버에서 요청을 처리하지 못했습니다"))
                .andExpect(jsonPath("$.requestId").value(requestId));

        assertThat(output).contains(requestId, AuthenticationServiceException.class.getName())
                .doesNotContain(JWK_FAILURE_BODY, token);
    }
}
