package com.personal.batongo.adapter.in.web.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.batongo.adapter.in.web.FilterErrorResponseWriter;
import com.personal.batongo.adapter.in.web.GlobalExceptionHandler;
import com.personal.batongo.adapter.in.web.ManagementAuthenticationFilter;
import com.personal.batongo.adapter.in.web.ManagementProperties;
import com.personal.batongo.adapter.in.web.RequestIdFilter;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

class TargetContractOperationsControllerConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(
                    TargetContractOperationsUseCase.class,
                    () -> mock(TargetContractOperationsUseCase.class)
            )
            .withUserConfiguration(OperationsControllerConfiguration.class);

    private final WebApplicationContextRunner webContextRunner = new WebApplicationContextRunner()
            .withBean(
                    TargetContractOperationsUseCase.class,
                    () -> mock(TargetContractOperationsUseCase.class)
            )
            .withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(DisabledOperationsWebConfiguration.class);

    @Test
    @DisplayName("operations 활성화 설정이 없으면 관리 컨트롤러를 등록하지 않는다")
    void doesNotRegisterControllerByDefault() {
        contextRunner.run(context -> assertThat(context)
                .doesNotHaveBean(TargetContractOperationsController.class));
    }

    @Test
    @DisplayName("operations 기본 비활성 상태는 인증된 HTTP 요청에도 안전한 404를 반환한다")
    void returnsSafeNotFoundWhenDisabledByDefault() {
        webContextRunner.run(context -> {
            try {
                MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context)
                        .addFilters(
                                new RequestIdFilter(),
                                new ManagementAuthenticationFilter(
                                        new ManagementProperties(
                                                "management-token-with-at-least-32-characters"
                                        ),
                                        new FilterErrorResponseWriter(new ObjectMapper())
                                )
                        )
                        .build();

                mockMvc.perform(get(
                                "/api/v1/operations/link-target-contract-v1/inventory"
                        )
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer management-token-with-at-least-32-characters"
                                ))
                        .andExpect(status().isNotFound())
                        .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                        .andExpect(header().string("Referrer-Policy", "no-referrer"))
                        .andExpect(header().exists(RequestIdFilter.HEADER_NAME))
                        .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        });
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

    @EnableWebMvc
    @Configuration(proxyBeanMethods = false)
    @Import({TargetContractOperationsController.class, GlobalExceptionHandler.class})
    static class DisabledOperationsWebConfiguration {
    }
}
