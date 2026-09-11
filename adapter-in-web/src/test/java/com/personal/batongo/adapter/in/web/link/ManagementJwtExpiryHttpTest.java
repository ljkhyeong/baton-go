package com.personal.batongo.adapter.in.web.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.personal.batongo.adapter.in.web.FilterErrorResponseWriter;
import com.personal.batongo.adapter.in.web.ManagementApiSecurityConfiguration;
import com.personal.batongo.adapter.in.web.ManagementOperationLogger;
import com.personal.batongo.adapter.in.web.RequestIdFilter;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.LinkResult;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example",
        "spring.security.oauth2.resourceserver.jwt.audiences=baton-go"
})
@ContextConfiguration(classes = {
        ManagementApiSecurityConfiguration.class, LinkManagementController.class
})
@Import({
        FilterErrorResponseWriter.class, RequestIdFilter.class,
        ManagementOperationLogger.class, SimpleMeterRegistry.class
})
class ManagementJwtExpiryHttpTest {

    private static final UUID LINK_ID = UUID.fromString("83a430c4-5c5d-4eb4-a815-7a5ba1fd4aae");
    private static HttpServer jwkServer;
    private static JwtEncoder jwtEncoder;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private SmartLinkUseCase useCase;

    @BeforeAll
    static void startJwkServer() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var keyPair = generator.generateKeyPair();
        var publicKey = (RSAPublicKey) keyPair.getPublic();
        jwtEncoder = NimbusJwtEncoder.withKeyPair(publicKey, (RSAPrivateKey) keyPair.getPrivate())
                .jwkPostProcessor(key -> key.keyID("expiry-test-key"))
                .build();
        byte[] body = new JWKSet(new RSAKey.Builder(publicKey).keyID("expiry-test-key").build())
                .toString().getBytes(StandardCharsets.UTF_8);
        jwkServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwkServer.createContext("/jwks", exchange -> {
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
            exchange.sendResponseHeaders(200, body.length);
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

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = -3600)
    @DisplayName("만료 시각이 없거나 이미 만료된 관리 JWT는 서비스 장애가 아닌 401로 거부한다")
    void rejectsMissingOrExpiredExpiry(Long expiryOffsetSeconds) throws Exception {
        String requestId = "jwt-expiry-test";
        mockMvc.perform(get("/api/v1/links/{linkId}", LINK_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(expiryOffsetSeconds))
                        .header("X-Request-Id", requestId))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer realm=\"baton-go-management\""))
                .andExpect(jsonPath("$.code").value("MANAGEMENT_AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.requestId").value(requestId));

        verifyNoInteractions(useCase);
        assertThat(meterRegistry.get("baton.go.management.authentication.service.failures")
                .counter().count()).isZero();
    }

    @Test
    @DisplayName("유효한 만료 시각을 가진 관리 JWT는 활성 시각 nbf 없이도 링크를 조회한다")
    void acceptsValidExpiryWithoutNotBefore() throws Exception {
        when(useCase.getLink(LINK_ID)).thenReturn(new LinkResult(
                LINK_ID, TargetSystem.ROUND, "/room/abcd-efgh-jkmn", LinkPurpose.MEETING_ENTRY,
                null, null, null, Instant.parse("2026-08-29T00:00:00Z"),
                Instant.parse("2026-08-29T01:00:00Z")
        ));

        mockMvc.perform(get("/api/v1/links/{linkId}", LINK_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(300L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(LINK_ID.toString()));

        verify(useCase).getLink(LINK_ID);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = " ")
    @DisplayName("서비스 식별자가 없거나 비어 있는 관리 JWT는 401로 거부한다")
    void rejectsMissingOrBlankSubject(String subject) throws Exception {
        mockMvc.perform(get("/api/v1/links/{linkId}", LINK_ID)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token(300L, subject)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("MANAGEMENT_AUTHENTICATION_REQUIRED"));

        verifyNoInteractions(useCase);
    }

    private String token(Long expiryOffsetSeconds) {
        return token(expiryOffsetSeconds, "test-client");
    }

    private String token(Long expiryOffsetSeconds, String subject) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder().issuer("https://identity.example")
                .audience(List.of("baton-go"))
                .issuedAt(now.minusSeconds(86400)).claim("scope", "baton-go.links.read");
        if (subject != null) {
            claims.claim("sub", subject);
        }
        if (expiryOffsetSeconds != null) {
            claims.expiresAt(now.plusSeconds(expiryOffsetSeconds));
        }
        return jwtEncoder.encode(JwtEncoderParameters.from(claims.build())).getTokenValue();
    }
}
