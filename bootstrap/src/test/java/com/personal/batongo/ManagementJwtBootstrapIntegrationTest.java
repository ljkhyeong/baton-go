package com.personal.batongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("mysql")
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest
@TestPropertySource("mysql-it.properties")
class ManagementJwtBootstrapIntegrationTest {

    private static HttpServer jwtServer;
    private static JwtEncoder jwtEncoder;
    private static String jwtIssuer;

    @Container
    @ServiceConnection(name = "mysql")
    static final MySQLContainer MYSQL = MySqlTestImage.container();

    @DynamicPropertySource
    static void managementJwtProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> jwtIssuer
        );
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> jwtIssuer + "/jwks"
        );
    }

    @BeforeAll
    static void startJwtServer() throws Exception {
        var jwkSet = new JWKSet(new RSAKeyGenerator(2048).keyID("management-integration-test").generate());
        jwtEncoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(jwkSet));
        byte[] body = jwkSet.toString().getBytes(StandardCharsets.UTF_8);
        jwtServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jwtServer.createContext("/issuer/jwks", exchange -> {
            exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        jwtServer.start();
        jwtIssuer = "http://127.0.0.1:" + jwtServer.getAddress().getPort() + "/issuer";
    }

    @AfterAll
    static void stopJwtServer() {
        jwtServer.stop(0);
    }

    private static final String MISSING_LINK_PATH =
            "/api/v1/links/00000000-0000-4000-8000-000000000001";

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Spring 통합 구성은 서명·발급자·대상을 검증한 관리 JWT만 허용한다")
    void assemblesManagementJwtAuthentication() throws Exception {
        mockMvc.perform(get(MISSING_LINK_PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertThat(result.getRequest().getSession(false)).isNull())
                .andExpect(header().string(
                        HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer realm=\"baton-go-management\""
                ))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.code")
                        .value("MANAGEMENT_AUTHENTICATION_REQUIRED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        mockMvc.perform(get(MISSING_LINK_PATH)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + managementJwt(jwtIssuer, "baton-go")
                        ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));

        mockMvc.perform(get(MISSING_LINK_PATH)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + managementJwt(jwtIssuer, "another-service")
                        ))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code")
                        .value("MANAGEMENT_AUTHENTICATION_REQUIRED"));

        mockMvc.perform(get(MISSING_LINK_PATH)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + managementJwt(
                                        jwtIssuer + "/another-issuer",
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
                .claim("scope", "baton-go.links.read")
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }
}
