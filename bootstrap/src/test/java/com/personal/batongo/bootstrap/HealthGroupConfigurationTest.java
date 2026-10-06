package com.personal.batongo.bootstrap;

import static org.mockito.Mockito.when;

import com.personal.batongo.adapter.in.web.GlobalExceptionHandler;
import com.personal.batongo.adapter.in.web.ManagementApiSecurityConfiguration;
import com.personal.batongo.adapter.in.web.RequestIdFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;

@SpringBootTest(classes = HealthGroupConfigurationTest.WebConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/jwks"
        })
@AutoConfigureRestTestClient
@DirtiesContext
class HealthGroupConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({ManagementApiSecurityConfiguration.class, GlobalExceptionHandler.class, RequestIdFilter.class})
    static class WebConfiguration {
    }

    @MockitoBean(name = "db", answers = Answers.CALLS_REAL_METHODS)
    private HealthIndicator databaseHealth;

    @MockitoBean(name = "redis", answers = Answers.CALLS_REAL_METHODS)
    private HealthIndicator redisHealth;

    @Autowired
    private RestTestClient client;

    @Test
    @DisplayName("주 HTTP 포트의 상태 확인은 DB나 Redis 장애 때 준비 상태만 실패한다")
    void probesOnMainPortIncludeDependenciesOnlyInReadiness() {
        when(databaseHealth.health()).thenReturn(Health.up().build());
        when(redisHealth.health()).thenReturn(Health.up().build());
        assertStatus("/readyz", 200);
        assertStatus("/livez", 200);

        when(databaseHealth.health()).thenReturn(Health.down().build());
        assertStatus("/readyz", 503);
        assertStatus("/livez", 200);

        when(databaseHealth.health()).thenReturn(Health.up().build());
        when(redisHealth.health()).thenReturn(Health.down().build());
        assertStatus("/readyz", 503);
        assertStatus("/livez", 200);
    }

    private void assertStatus(String path, int expectedStatus) {
        client.get().uri(path).exchange().expectStatus().isEqualTo(expectedStatus);
    }
}
