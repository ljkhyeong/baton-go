package com.personal.batongo.adapter.in.web.operations;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.batongo.adapter.in.web.FilterErrorResponseWriter;
import com.personal.batongo.adapter.in.web.GlobalExceptionHandler;
import com.personal.batongo.adapter.in.web.ManagementAuthenticationFilter;
import com.personal.batongo.adapter.in.web.ManagementProperties;
import com.personal.batongo.adapter.in.web.RequestIdFilter;
import com.personal.batongo.application.link.error.InvalidTargetContractInventoryRequestException;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.Compliance;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.CreationRequestState;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.InventoryItem;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.InventoryQuery;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.InventoryResult;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.RemediationCommand;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.RemediationResult;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.RemediationState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class TargetContractOperationsHttpContractTest {

    private static final String BASE_PATH =
            "/api/v1/operations/link-target-contract-v1";
    private static final String MANAGEMENT_TOKEN =
            "management-token-with-at-least-32-characters";
    private static final UUID LINK_ID =
            UUID.fromString("83a430c4-5c5d-4eb4-a815-7a5ba1fd4aae");
    private static final UUID AFTER_LINK_ID =
            UUID.fromString("713d9cb7-2842-4f9f-b3cc-e31d98c6238a");
    private static final Instant CREATED_AT = Instant.parse("2026-07-29T10:00:00Z");
    private static final Instant REVOKED_AT = Instant.parse("2026-08-03T00:00:00Z");

    private TargetContractOperationsUseCase operationsUseCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        operationsUseCase = mock(TargetContractOperationsUseCase.class);
        TargetContractOperationsController controller =
                new TargetContractOperationsController(operationsUseCase);
        ManagementAuthenticationFilter authenticationFilter =
                new ManagementAuthenticationFilter(
                        new ManagementProperties(MANAGEMENT_TOKEN),
                        new FilterErrorResponseWriter(new ObjectMapper())
                );
        var jsonMapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(new SimpleMeterRegistry()))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(
                        jsonMapper
                ))
                .addFilters(new RequestIdFilter(), authenticationFilter)
                .build();
    }

    @Test
    @DisplayName("inventory는 안전한 분류 필드와 다음 keyset cursor만 반환한다")
    void returnsSafeInventoryPage() throws Exception {
        when(operationsUseCase.inventory(new InventoryQuery(AFTER_LINK_ID, 1)))
                .thenReturn(new InventoryResult(
                        "v1",
                        List.of(new InventoryItem(
                                LINK_ID,
                                Compliance.NON_COMPLIANT,
                                RemediationState.UNREVOKED,
                                CreationRequestState.PRESENT,
                                CREATED_AT,
                                null,
                                null,
                                7L
                        )),
                        LINK_ID,
                        true
                ));

        mockMvc.perform(authorized(get(BASE_PATH + "/inventory")
                        .queryParam("afterLinkId", AFTER_LINK_ID.toString())
                        .queryParam("limit", "1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contractVersion").value("v1"))
                .andExpect(jsonPath("$.items[0].linkId").value(LINK_ID.toString()))
                .andExpect(jsonPath("$.items[0].compliance").value("NON_COMPLIANT"))
                .andExpect(jsonPath("$.items[0].remediationState").value("UNREVOKED"))
                .andExpect(jsonPath("$.items[0].creationRequestState").value("PRESENT"))
                .andExpect(jsonPath("$.items[0].createdAt").value(CREATED_AT.toString()))
                .andExpect(jsonPath("$.items[0].version").value(7))
                .andExpect(jsonPath("$.nextAfterLinkId").value(LINK_ID.toString()))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(content().string(not(containsString("\"targetPath\""))))
                .andExpect(content().string(not(containsString("\"targetSystem\""))))
                .andExpect(content().string(not(containsString("\"purpose\""))))
                .andExpect(content().string(not(containsString("\"codeHash\""))))
                .andExpect(content().string(not(containsString("\"shortUrl\""))))
                .andExpect(content().string(not(containsString("\"idempotencyHash\""))))
                .andExpect(content().string(not(containsString("\"idempotencyKeyHash\""))));

        verify(operationsUseCase).inventory(new InventoryQuery(AFTER_LINK_ID, 1));
    }

    @Test
    @DisplayName("inventory limit을 생략하면 application에 기본값 100을 전달한다")
    void usesDefaultInventoryLimit() throws Exception {
        when(operationsUseCase.inventory(new InventoryQuery(null, 100)))
                .thenReturn(new InventoryResult("v1", List.of(), null, false));

        mockMvc.perform(authorized(get(BASE_PATH + "/inventory")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.hasMore").value(false));

        verify(operationsUseCase).inventory(new InventoryQuery(null, 100));
    }

    @Test
    @DisplayName("inventory limit 경계를 벗어나면 400 INVALID_REQUEST로 응답한다")
    void rejectsOutOfRangeInventoryLimit() throws Exception {
        when(operationsUseCase.inventory(any()))
                .thenThrow(new InvalidTargetContractInventoryRequestException());

        mockMvc.perform(authorized(get(BASE_PATH + "/inventory")
                        .queryParam("limit", "0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("inventory UUID cursor 형식이 잘못되면 use case 호출 없이 400으로 응답한다")
    void rejectsMalformedInventoryCursor() throws Exception {
        mockMvc.perform(authorized(get(BASE_PATH + "/inventory")
                        .queryParam("afterLinkId", "not-a-uuid")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(operationsUseCase);
    }

    @Test
    @DisplayName("승인된 non-compliant 링크 폐기는 계약 버전과 폐기 결과만 반환한다")
    void remediatesNonCompliantLink() throws Exception {
        when(operationsUseCase.remediate(new RemediationCommand(LINK_ID, 7L)))
                .thenReturn(new RemediationResult(
                        LINK_ID,
                        "v1",
                        RemediationState.REVOKED,
                        REVOKED_AT,
                        false
                ));

        mockMvc.perform(authorized(put(BASE_PATH + "/links/{linkId}/revocation", LINK_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":7}
                                """)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.linkId").value(LINK_ID.toString()))
                .andExpect(jsonPath("$.contractVersion").value("v1"))
                .andExpect(jsonPath("$.remediationState").value("REVOKED"))
                .andExpect(jsonPath("$.revokedAt").value(REVOKED_AT.toString()))
                .andExpect(jsonPath("$.alreadyRevoked").value(false))
                .andExpect(jsonPath("$.targetPath").doesNotExist())
                .andExpect(jsonPath("$.shortUrl").doesNotExist());

        verify(operationsUseCase).remediate(new RemediationCommand(LINK_ID, 7L));
    }

    @Test
    @DisplayName("expectedVersion이 없는 폐기 요청은 변경 전에 400으로 거부한다")
    void rejectsMissingExpectedVersionBeforeMutation() throws Exception {
        mockMvc.perform(remediationRequest("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("expectedVersion: 요청 값이 올바르지 않습니다"));

        verifyNoInteractions(operationsUseCase);
    }

    private MockHttpServletRequestBuilder remediationRequest(String body) {
        return authorized(put(BASE_PATH + "/links/{linkId}/revocation", LINK_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + MANAGEMENT_TOKEN);
    }
}
