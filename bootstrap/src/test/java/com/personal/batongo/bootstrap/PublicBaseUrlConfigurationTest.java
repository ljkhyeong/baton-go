package com.personal.batongo.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.adapter.in.web.PublicLinkProperties;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class PublicBaseUrlConfigurationTest {

    @Test
    @DisplayName("공개 base URL 환경 변수가 없으면 application 설정 바인딩에 실패한다")
    void requiresExplicitPublicBaseUrl() throws IOException {
        ConfigurableEnvironment environment = applicationEnvironment();

        assertThatThrownBy(() -> Binder.get(environment).bind(
                "baton-go",
                PublicLinkProperties.class
        ).get())
                .isInstanceOf(BindException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("명시한 공개 base URL은 application 설정에 바인딩된다")
    void bindsExplicitPublicBaseUrl() throws IOException {
        ConfigurableEnvironment environment = applicationEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "explicitPublicBaseUrl",
                java.util.Map.of(
                        "BATON_GO_PUBLIC_BASE_URL",
                        "https://go.example"
                )
        ));

        PublicLinkProperties properties = Binder.get(environment).bind(
                "baton-go",
                PublicLinkProperties.class
        ).get();

        assertThat(properties.publicBaseUrl()).hasToString("https://go.example");
    }

    private ConfigurableEnvironment applicationEnvironment() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME
        );
        environment.getPropertySources().remove(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME
        );
        for (PropertySource<?> propertySource : new YamlPropertySourceLoader().load(
                "applicationConfig",
                new ClassPathResource("application.yml")
        )) {
            environment.getPropertySources().addLast(propertySource);
        }
        return environment;
    }
}
