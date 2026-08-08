package com.personal.batongo.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.adapter.in.web.ManagementProperties;
import com.personal.batongo.adapter.out.external.link.LinkCodeProperties;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.boot.logging.DeferredLogs;
import org.springframework.boot.support.EnvironmentPostProcessorsFactory;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class RawCredentialEnvironmentPostProcessorTest {

    @Test
    @DisplayName("원문 credential processor는 Spring 시작 전 환경 처리 단계에 등록된다")
    void registersAsEnvironmentPostProcessor() {
        assertThat(EnvironmentPostProcessorsFactory.fromSpringFactories(
                getClass().getClassLoader()
        ).getEnvironmentPostProcessors(
                new DeferredLogs(),
                new DefaultBootstrapContext()
        )).anyMatch(RawCredentialEnvironmentPostProcessor.class::isInstance);
    }

    @Test
    @DisplayName("process environment credential의 placeholder와 역슬래시는 원문 그대로 바인딩한다")
    void bindsProcessEnvironmentCredentialsWithoutPlaceholderResolution() {
        String linkCodeSecret = " 링크-${random.uuid}-비밀\\원문을-그대로-보존한다 ";
        String managementToken = "token-${HOME}-with-backslash\\-and-enough-length";
        String databaseUrl = "jdbc:mysql://db:3306/baton_go?label=${HOME}\\raw";
        String databaseUsername = "user-${HOME}\\raw";
        String databasePassword = " password-${random.uuid}\\${HOME} ";
        StandardEnvironment environment = environmentWithResolvedAlternatives();

        new RawCredentialEnvironmentPostProcessor(Map.of(
                "BATON_GO_LINK_CODE_SECRET", linkCodeSecret,
                "BATON_GO_MANAGEMENT_TOKEN", managementToken,
                "BATON_GO_DB_URL", databaseUrl,
                "BATON_GO_DB_USERNAME", databaseUsername,
                "BATON_GO_DB_PASSWORD", databasePassword
        )).postProcessEnvironment(environment, null);

        Binder binder = Binder.get(environment);
        LinkCodeProperties linkCode = binder.bind(
                "baton-go.link-code",
                LinkCodeProperties.class
        ).get();
        ManagementProperties management = binder.bind(
                "baton-go.management",
                ManagementProperties.class
        ).get();
        DataSourceProperties dataSource = binder.bind(
                "spring.datasource",
                DataSourceProperties.class
        ).get();

        assertThat(environment.getProperty("baton-go.link-code.secret"))
                .isEqualTo(linkCodeSecret);
        assertThat(environment.getProperty("spring.datasource.password"))
                .isEqualTo(databasePassword);
        assertThat(linkCode.secret()).isEqualTo(linkCodeSecret);
        assertThat(management.token()).isEqualTo(managementToken);
        assertThat(dataSource.getUrl()).isEqualTo(databaseUrl);
        assertThat(dataSource.getUsername()).isEqualTo(databaseUsername);
        assertThat(dataSource.getPassword()).isEqualTo(databasePassword);
    }

    @Test
    @DisplayName("짧은 literal placeholder는 임의 값으로 확장하지 않고 링크 비밀 길이 검증에서 거부한다")
    void rejectsShortLiteralPlaceholderInsteadOfExpandingIt() {
        StandardEnvironment environment = environmentWithResolvedAlternatives();
        new RawCredentialEnvironmentPostProcessor(Map.of(
                "BATON_GO_LINK_CODE_SECRET", "${random.uuid}"
        )).postProcessEnvironment(environment, null);

        Object diagnosticValue = environment.getPropertySources()
                .get(RawCredentialEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)
                .getProperty("baton-go.link-code.secret");
        assertThat(diagnosticValue).isInstanceOf(String[].class);
        assertThat(String.valueOf(diagnosticValue))
                .doesNotContain("${random.uuid}");

        assertThatThrownBy(() -> Binder.get(environment).bind(
                "baton-go.link-code",
                LinkCodeProperties.class
        ).get())
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .rootCause()
                .hasMessage("링크 코드 파생 키는 32자 이상이어야 합니다");
    }

    @Test
    @DisplayName("대상 process environment 변수가 없으면 기존 Spring 설정 우선순위를 유지한다")
    void leavesExistingSpringConfigurationUntouchedWhenEnvironmentIsAbsent() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "testConfiguration",
                Map.of(
                        "baton-go.link-code.secret",
                        "test-link-code-secret-with-more-than-32-characters"
                )
        ));

        new RawCredentialEnvironmentPostProcessor(Map.of())
                .postProcessEnvironment(environment, null);

        assertThat(environment.getPropertySources().contains(
                RawCredentialEnvironmentPostProcessor.PROPERTY_SOURCE_NAME
        )).isFalse();
        assertThat(Binder.get(environment).bind(
                "baton-go.link-code",
                LinkCodeProperties.class
        ).get().secret()).isEqualTo(
                "test-link-code-secret-with-more-than-32-characters"
        );
    }

    @Test
    @DisplayName("process environment credential은 command line과 JVM property보다 우선한다")
    void givesRawProcessEnvironmentCredentialsHighestPrecedence() {
        String rawSecret = "raw-process-secret-${HOME}-with-more-than-32-characters";
        StandardEnvironment environment = environmentWithResolvedAlternatives();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "commandLineArgs",
                Map.of(
                        "baton-go.link-code.secret",
                        "command-line-${random.uuid}-with-more-than-32-characters"
                )
        ));
        environment.getPropertySources().addFirst(new MapPropertySource(
                StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                Map.of(
                        "baton-go.link-code.secret",
                        "system-property-${random.uuid}-with-more-than-32-characters"
                )
        ));

        new RawCredentialEnvironmentPostProcessor(Map.of(
                "BATON_GO_LINK_CODE_SECRET",
                rawSecret
        )).postProcessEnvironment(environment, null);

        assertThat(environment.getPropertySources().iterator().next().getName())
                .isEqualTo(RawCredentialEnvironmentPostProcessor.PROPERTY_SOURCE_NAME);
        assertThat(Binder.get(environment).bind(
                "baton-go.link-code",
                LinkCodeProperties.class
        ).get().secret()).isEqualTo(rawSecret);
    }

    private StandardEnvironment environmentWithResolvedAlternatives() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource(
                "wouldResolvePlaceholders",
                Map.of(
                        "random.uuid", "expanded-random-value",
                        "HOME", "/expanded/home"
                )
        ));
        return environment;
    }
}
