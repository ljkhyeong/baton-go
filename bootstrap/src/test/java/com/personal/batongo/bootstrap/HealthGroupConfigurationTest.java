package com.personal.batongo.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.personal.batongo.adapter.in.web.FilterErrorResponseWriter;
import com.personal.batongo.adapter.in.web.ManagementApiSecurityConfiguration;
import com.personal.batongo.adapter.in.web.RequestIdFilter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(classes = HealthGroupConfigurationTest.WebConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/jwks"
        })
@DirtiesContext
class HealthGroupConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({ManagementApiSecurityConfiguration.class, FilterErrorResponseWriter.class, RequestIdFilter.class})
    static class WebConfiguration {
    }

    @MockitoBean(name = "db", answers = Answers.CALLS_REAL_METHODS)
    private HealthIndicator databaseHealth;

    @MockitoBean(name = "resolverQuotaRedis", answers = Answers.CALLS_REAL_METHODS)
    private HealthIndicator resolverQuotaRedisHealth;

    @Value("${local.server.port}")
    private int port;

    @Test
    @DisplayName("주 HTTP 포트의 상태 확인은 DB나 Redis 장애 때 준비 상태만 실패한다")
    void probesOnMainPortIncludeDependenciesOnlyInReadiness() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            when(databaseHealth.health()).thenReturn(Health.up().build());
            when(resolverQuotaRedisHealth.health()).thenReturn(Health.up().build());
            assertStatus(client, "/readyz", 200);
            assertStatus(client, "/livez", 200);

            when(databaseHealth.health()).thenReturn(Health.down().build());
            assertStatus(client, "/readyz", 503);
            assertStatus(client, "/livez", 200);

            when(databaseHealth.health()).thenReturn(Health.up().build());
            when(resolverQuotaRedisHealth.health()).thenReturn(Health.down().build());
            assertStatus(client, "/readyz", 503);
            assertStatus(client, "/livez", 200);
        }
    }

    private void assertStatus(HttpClient client, String path, int expectedStatus) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).as("%s 응답 상태", path).isEqualTo(expectedStatus);
    }
}
