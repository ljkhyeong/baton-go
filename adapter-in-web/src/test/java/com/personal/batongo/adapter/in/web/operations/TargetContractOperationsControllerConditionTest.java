package com.personal.batongo.adapter.in.web.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

    @Test
    @DisplayName("operations 활성화 설정이 없으면 관리 컨트롤러를 등록하지 않는다")
    void doesNotRegisterControllerByDefault() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean(TargetContractOperationsController.class));
    }

    @Test
    @DisplayName("operations 활성화만으로는 private ingress 확인 전 관리 컨트롤러를 등록하지 않는다")
    void doesNotRegisterControllerWithoutPrivateIngressConfirmation() {
        contextRunner
                .withPropertyValues("baton-go.target-contract-operations.enabled=true")
                .run(context -> assertThat(context)
                        .doesNotHaveBean(TargetContractOperationsController.class));
    }

    @Test
    @DisplayName("operations 활성화와 private ingress 확인이 모두 true이면 관리 컨트롤러를 등록한다")
    void registersControllerWhenExplicitlyEnabledAndPrivate() {
        contextRunner
                .withPropertyValues(
                        "baton-go.target-contract-operations.enabled=true",
                        "baton-go.target-contract-operations.private-ingress-confirmed=true"
                )
                .run(context -> assertThat(context)
                        .hasSingleBean(TargetContractOperationsController.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(TargetContractOperationsController.class)
    static class OperationsControllerConfiguration {
    }
}
