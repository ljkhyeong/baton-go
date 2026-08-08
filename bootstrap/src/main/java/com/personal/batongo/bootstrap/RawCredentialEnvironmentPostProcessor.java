package com.personal.batongo.bootstrap;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;

/**
 * 민감한 process environment 값을 Spring placeholder 재해석 없이 바인딩합니다.
 *
 * <p>Spring의 일반 configuration-properties binder는 문자열 안의 {@code ${...}}도 다시
 * 해석합니다. 링크 코드 비밀과 credential은 원문 자체가 identity이므로, 문자열이 아닌
 * 불투명 값으로 property source에 제공하고 타입 변환 단계에서만 문자열로 바꿉니다.</p>
 */
public final class RawCredentialEnvironmentPostProcessor
        implements EnvironmentPostProcessor, Ordered {

    static final String PROPERTY_SOURCE_NAME = "batonGoRawProcessCredentials";
    private static final Map<String, String> PROPERTY_ENVIRONMENT_NAMES = Map.of(
            "baton-go.link-code.secret", "BATON_GO_LINK_CODE_SECRET",
            "baton-go.management.token", "BATON_GO_MANAGEMENT_TOKEN",
            "spring.datasource.url", "BATON_GO_DB_URL",
            "spring.datasource.username", "BATON_GO_DB_USERNAME",
            "spring.datasource.password", "BATON_GO_DB_PASSWORD"
    );

    private final Map<String, String> environment;

    public RawCredentialEnvironmentPostProcessor() {
        this(System.getenv());
    }

    RawCredentialEnvironmentPostProcessor(Map<String, String> environment) {
        this.environment = copyRelevantEnvironment(environment);
    }

    @Override
    public void postProcessEnvironment(
            ConfigurableEnvironment targetEnvironment,
            SpringApplication application
    ) {
        RawCredentialPropertySource propertySource =
                new RawCredentialPropertySource(environment);
        if (propertySource.getPropertyNames().length == 0) {
            return;
        }

        // process environment가 존재하면 server와 guard-tool이 같은 credential을
        // 사용해야 하므로 command line과 JVM system property보다도 우선한다.
        targetEnvironment.getPropertySources().addFirst(propertySource);
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    private static Map<String, String> copyRelevantEnvironment(
            Map<String, String> environment
    ) {
        Map<String, String> relevantEnvironment = new LinkedHashMap<>();
        PROPERTY_ENVIRONMENT_NAMES.values().forEach(environmentName -> {
            if (environment.containsKey(environmentName)) {
                relevantEnvironment.put(environmentName, environment.get(environmentName));
            }
        });
        return Map.copyOf(relevantEnvironment);
    }

    private static final class RawCredentialPropertySource
            extends EnumerablePropertySource<RawCredentialValues> {

        private final String[] propertyNames;

        private RawCredentialPropertySource(Map<String, String> environment) {
            super(PROPERTY_SOURCE_NAME, RawCredentialValues.from(environment));
            this.propertyNames = source.propertyNames();
        }

        @Override
        public String[] getPropertyNames() {
            return Arrays.copyOf(propertyNames, propertyNames.length);
        }

        @Override
        public Object getProperty(String name) {
            String value = source.get(name);
            // Spring의 표준 1-element array-to-String 변환은 원문을 보존하지만
            // placeholder resolver와 failure analyzer에는 raw String을 노출하지 않는다.
            return value == null ? null : new String[]{value};
        }
    }

    private static final class RawCredentialValues {

        private final Map<String, String> values;

        private RawCredentialValues(Map<String, String> values) {
            this.values = values;
        }

        private static RawCredentialValues from(Map<String, String> environment) {
            Map<String, String> values = new LinkedHashMap<>();
            PROPERTY_ENVIRONMENT_NAMES.forEach((propertyName, environmentName) -> {
                if (environment.containsKey(environmentName)) {
                    values.put(propertyName, environment.get(environmentName));
                }
            });
            return new RawCredentialValues(Map.copyOf(values));
        }

        private String[] propertyNames() {
            return values.keySet().toArray(String[]::new);
        }

        private String get(String name) {
            return values.get(name);
        }

        @Override
        public String toString() {
            return "RawCredentialValues[redacted]";
        }
    }

}
