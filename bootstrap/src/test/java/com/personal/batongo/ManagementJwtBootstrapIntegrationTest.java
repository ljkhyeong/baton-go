package com.personal.batongo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("mysql")
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.audiences=baton-go",
        "baton-go.link-code.secret=test-link-code-secret-that-is-separate-and-long-enough",
        "baton-go.public-base-url=https://go.example",
        "baton-go.targets.baton-base-url=https://baton.example",
        "baton-go.targets.round-base-url=https://baton.example"
})
class ManagementJwtBootstrapIntegrationTest {

    private static final String JWT_KEY_ID = "management-integration-test";
    private static final KeyPair JWT_KEY_PAIR = jwtKeyPair();
    private static final HttpServer JWT_SERVER = startJwtServer();
    private static final String JWT_ISSUER =
            "http://127.0.0.1:" + JWT_SERVER.getAddress().getPort() + "/issuer";

    @Container
    @ServiceConnection(name = "mysql")
    static final MySQLContainer MYSQL = new MySQLContainer(MySqlTestImage.NAME)
            .withUrlParam("connectTimeout", "3000")
            .withUrlParam("socketTimeout", "30000");

    @DynamicPropertySource
    static void managementJwtProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> JWT_ISSUER
        );
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> JWT_ISSUER + "/jwks"
        );
    }

    @AfterAll
    static void stopJwtServer() {
        JWT_SERVER.stop(0);
    }

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Spring 통합 구성은 서명·발급자·대상을 검증한 관리 JWT만 허용한다")
    void assemblesManagementJwtAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/operations/link-target-contract-v1/inventory"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(
                        HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer realm=\"baton-go-management\""
                ))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.code")
                        .value("MANAGEMENT_AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        mockMvc.perform(get("/api/v1/operations/link-target-contract-v1/inventory")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + managementJwt(JWT_ISSUER, "baton-go")
                        ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/operations/link-target-contract-v1/inventory")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + managementJwt(JWT_ISSUER, "another-service")
                        ))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("MANAGEMENT_AUTHENTICATION_REQUIRED"));

        mockMvc.perform(get("/api/v1/operations/link-target-contract-v1/inventory")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + managementJwt(
                                        JWT_ISSUER + "/another-issuer",
                                        "baton-go"
                                )
                        ))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("MANAGEMENT_AUTHENTICATION_REQUIRED"));
    }

    private String managementJwt(String issuer, String audience) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject("baton-integration-test")
                .audience(List.of(audience))
                .issuedAt(now.minusSeconds(5))
                .expiresAt(now.plusSeconds(60))
                .claim("scope", "baton-go.target-contract.operate")
                .build();
        JwtEncoder encoder = NimbusJwtEncoder.withKeyPair(
                        (RSAPublicKey) JWT_KEY_PAIR.getPublic(),
                        (RSAPrivateKey) JWT_KEY_PAIR.getPrivate()
                )
                .jwkPostProcessor(key -> key.keyID(JWT_KEY_ID))
                .build();
        return encoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }

    private static KeyPair jwtKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("RSA 테스트 키를 생성할 수 없습니다", exception);
        }
    }

    private static HttpServer startJwtServer() {
        try {
            RSAKey publicKey = new RSAKey.Builder(
                    (RSAPublicKey) JWT_KEY_PAIR.getPublic()
            ).keyID(JWT_KEY_ID).build();
            byte[] jwkSet = new JWKSet(publicKey)
                    .toPublicJWKSet()
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);
            HttpServer server = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0),
                    0
            );
            server.createContext("/issuer/jwks", exchange -> {
                exchange.getResponseHeaders().set(
                        HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_JSON_VALUE
                );
                exchange.sendResponseHeaders(200, jwkSet.length);
                try (var response = exchange.getResponseBody()) {
                    response.write(jwkSet);
                }
            });
            server.start();
            return server;
        } catch (IOException exception) {
            throw new IllegalStateException("관리 JWT 테스트 서버를 시작할 수 없습니다", exception);
        }
    }
}
