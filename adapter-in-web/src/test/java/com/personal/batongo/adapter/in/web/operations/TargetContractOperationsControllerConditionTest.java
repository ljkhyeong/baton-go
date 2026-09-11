package com.personal.batongo.adapter.in.web.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class TargetContractOperationsControllerConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(
                    TargetContractOperationsUseCase.class,
                    () -> mock(TargetContractOperationsUseCase.class)
            )
            .withUserConfiguration(OperationsControllerConfiguration.class);

    @ParameterizedTest(name = "{index}: {0}")
    @ValueSource(strings = {"enabled", "private-ingress-confirmed"})
    @DisplayName("운영 관리 컨트롤러는 활성화 설정 하나만 켜면 등록하지 않는다")
    void doesNotRegisterControllerWithOnlyOneConfirmation(String propertyName) {
        contextRunner
                .withPropertyValues(
                        "baton-go.target-contract-operations." + propertyName + "=true"
                )
                .run(context -> assertThat(context)
                        .doesNotHaveBean(TargetContractOperationsController.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(TargetContractOperationsController.class)
    static class OperationsControllerConfiguration {
    }
}
