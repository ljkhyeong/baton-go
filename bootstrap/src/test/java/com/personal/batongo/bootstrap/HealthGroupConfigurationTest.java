package com.personal.batongo.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.registry.DefaultHealthContributorRegistry;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class HealthGroupConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(HealthEndpointAutoConfiguration.class))
            .withBean(HealthContributorRegistry.class, this::healthContributorRegistry);

    @Test
    @DisplayName("readiness는 애플리케이션 상태와 데이터베이스를 함께 확인한다")
    void includesDatabaseInReadiness() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            HealthEndpointGroups groups = context.getBean(HealthEndpointGroups.class);
            HealthEndpointGroup readiness = groups.get("readiness");

            assertThat(readiness).isNotNull();
            assertThat(readiness.isMember("readinessState")).isTrue();
            assertThat(readiness.isMember("db")).isTrue();
            assertThat(readiness.isMember("livenessState")).isFalse();
        });
    }

    private HealthContributorRegistry healthContributorRegistry() {
        DefaultHealthContributorRegistry registry = new DefaultHealthContributorRegistry();
        HealthIndicator up = () -> Health.up().build();
        registry.registerContributor("readinessState", up);
        registry.registerContributor("db", up);
        return registry;
    }
}
