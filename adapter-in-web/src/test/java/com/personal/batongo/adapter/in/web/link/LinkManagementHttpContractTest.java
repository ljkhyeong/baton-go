package com.personal.batongo.adapter.in.web.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.modifyHeaders;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessRequest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.batongo.adapter.in.web.GlobalExceptionHandler;
import com.personal.batongo.adapter.in.web.ManagementOperationLogger;
import com.personal.batongo.adapter.in.web.RequestIdFilter;
import com.personal.batongo.adapter.in.web.WebMvcConfiguration;
import com.personal.batongo.application.link.error.IdempotencyKeyConflictException;
import com.personal.batongo.application.link.error.InvalidRequestException;
import com.personal.batongo.application.link.error.LinkCodeKeyBindingException;
import com.personal.batongo.application.link.error.LinkCodeReplayMismatchException;
import com.personal.batongo.application.link.error.LinkCreationReplayUnavailableException;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.error.PublicLinkOriginReplayUnavailableException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreateLinkCommand;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreatedLinkResult;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.LinkResult;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.LinkBatchResult;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.LinkSearchQuery;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.LinkSearchResult;
import com.personal.batongo.domain.link.LinkAvailabilityPolicy.Status;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.LinkValidationException;
import com.personal.batongo.domain.link.TargetSystem;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.mockmvc.RestDocumentationResultHandler;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.config.annotation.DelegatingWebMvcConfiguration;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith({RestDocumentationExtension.class, OutputCaptureExtension.class})
class LinkManagementHttpContractTest {

    private static final UUID LINK_ID = UUID.fromString("83a430c4-5c5d-4eb4-a815-7a5ba1fd4aae");
    private static final String IDEMPOTENCY_KEY = "8e448211-66ae-44ab-9888-c4960648c22b";
    private static final Instant CREATED_AT = Instant.parse("2026-07-29T10:00:00Z");
    private static final String BATON_TARGET_PATH =
            "/teams/8e448211-66ae-44ab-9888-c4960648c22b"
                    + "/seasons/713d9cb7-2842-4f9f-b3cc-e31d98c6238a";

    private SmartLinkUseCase useCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp(RestDocumentationContextProvider restDocumentation) throws IOException {
        useCase = mock(SmartLinkUseCase.class);
        var jsonMapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        LinkManagementController controller = new LinkManagementController(
                useCase, new ManagementOperationLogger(jsonMapper)
        );
        var errors = new GlobalExceptionHandler(new SimpleMeterRegistry());
        var mvcConfiguration = new DelegatingWebMvcConfiguration();
        mvcConfiguration.setConfigurers(List.of(new WebMvcConfiguration()));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .defaultRequest(get("/").principal(() -> "baton-service"))
                .setControllerAdvice(errors)
                .setContentNegotiationManager(mvcConfiguration.mvcContentNegotiationManager())
                .setMessageConverters(
                        new StringHttpMessageConverter(StandardCharsets.UTF_8),
                        new JacksonJsonHttpMessageConverter(jsonMapper)
                )
                .addFilters(new RequestIdFilter())
                .apply(documentationConfiguration(restDocumentation))
                .build();
    }

    @Test
    @DisplayName("일괄 조회는 쉼표로 구분한 ID를 전달하고 링크 상태와 누락 ID를 반환한다")
    void getsLinkBatchContract() throws Exception {
        var link = linkResult();
        UUID missingId = UUID.fromString("00000000-0000-4000-8000-000000000001");
        when(useCase.getLinks(any())).thenReturn(new LinkBatchResult(
                List.of(link), List.of(missingId), link.evaluatedAt()
        ));

        mockMvc.perform(get("/api/v1/links/batch")
                        .param("linkIds", LINK_ID + "," + missingId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.items[0].id").value(LINK_ID.toString()))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].evaluatedAt").value(link.evaluatedAt().toString()))
                .andExpect(jsonPath("$.items[0].shortUrl").doesNotExist())
                .andExpect(jsonPath("$.items[0].codeHash").doesNotExist())
                .andExpect(jsonPath("$.notFoundIds[0]").value(missingId.toString()))
                .andExpect(jsonPath("$.evaluatedAt").value(link.evaluatedAt().toString()))
                .andDo(document("links-batch-get"));

        verify(useCase).getLinks(List.of(LINK_ID, missingId));
    }

    @Test
    @DisplayName("일괄 조회에서 ID 매개변수 누락과 잘못된 UUID는 서비스 호출 전에 거부한다")
    void rejectsMalformedBatchIds() throws Exception {
        mockMvc.perform(get("/api/v1/links/batch"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mockMvc.perform(get("/api/v1/links/batch").param("linkIds", LINK_ID + ",invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("관리 검색은 조회 조건을 전달하고 공개 코드 없는 목록과 다음 커서를 반환한다")
    void searchesLinksContract() throws Exception {
        var link = linkResult();
        UUID cursor = UUID.fromString("00000000-0000-4000-8000-000000000000");
        when(useCase.searchLinks(any())).thenReturn(new LinkSearchResult(
                List.of(link), LINK_ID, true, link.evaluatedAt()
        ));

        mockMvc.perform(get("/api/v1/links")
                        .param("afterLinkId", cursor.toString())
                        .param("limit", "50")
                        .param("targetSystem", "BATON")
                        .param("createdFrom", "2026-07-29T19:00:00+09:00")
                        .param("createdBefore", "2026-07-30T10:00:00Z")
                        .param("status", "ACTIVE"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.items[0].id").value(LINK_ID.toString()))
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].shortUrl").doesNotExist())
                .andExpect(jsonPath("$.items[0].codeHash").doesNotExist())
                .andExpect(jsonPath("$.nextAfterLinkId").value(LINK_ID.toString()))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.items[0].evaluatedAt").value(link.evaluatedAt().toString()))
                .andExpect(jsonPath("$.evaluatedAt").value(link.evaluatedAt().toString()))
                .andDo(document("links-search"));

        verify(useCase).searchLinks(new LinkSearchQuery(
                cursor, 50, TargetSystem.BATON, CREATED_AT, CREATED_AT.plusSeconds(86400), Status.ACTIVE
        ));
    }

    @Test
    @DisplayName("관리 검색 조건을 생략하면 기본 조회 한도와 필터 없는 조회를 사용한다")
    void searchesWithDefaultParameters() throws Exception {
        when(useCase.searchLinks(any())).thenReturn(new LinkSearchResult(List.of(), null, false, CREATED_AT));

        mockMvc.perform(get("/api/v1/links"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.hasMore").value(false));

        verify(useCase).searchLinks(new LinkSearchQuery(null, 100, null, null, null, null));
    }

    @Test
    @DisplayName("관리 검색의 잘못된 시각 표현은 서비스 호출 전에 요청 오류로 반환한다")
    void rejectsMalformedSearchTime() throws Exception {
        mockMvc.perform(get("/api/v1/links").param("createdFrom", "not-a-time"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("링크 생성 응답은 공개 코드가 포함된 단축 URL과 정해진 필드를 반환한다")
    void createsLinkContract(CapturedOutput output) throws Exception {
        LinkResult link = linkResult();
        when(useCase.createLink(any())).thenReturn(new CreatedLinkResult(
                link,
                URI.create("https://go.example/l/VOvLShvx93kQpj8x7w2HYQ"),
                false
        ));

        mockMvc.perform(post("/api/v1/links")
                        .header("X-Request-Id", "link-create-history")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "BATON",
                                  "targetPath": "%s",
                                  "purpose": "NAVIGATION",
                                  "expiresAt": "2026-07-30T10:00:00Z"
                                }
                                """.formatted(BATON_TARGET_PATH)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/links/" + LINK_ID))
                .andExpect(header().string(
                        "Idempotency-Replayed",
                        "false"
                ))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.id").value(LINK_ID.toString()))
                .andExpect(jsonPath("$.shortUrl")
                        .value("https://go.example/l/VOvLShvx93kQpj8x7w2HYQ"))
                .andExpect(jsonPath("$.rawCode").doesNotExist())
                .andExpect(jsonPath("$.targetSystem").value("BATON"))
                .andExpect(jsonPath("$.targetPath").value(BATON_TARGET_PATH))
                .andExpect(jsonPath("$.purpose").value("NAVIGATION"))
                .andExpect(jsonPath("$.createdAt").value("2026-07-29T10:00:00Z"))
                .andDo(documentManagementEndpoint("links-create"));

        assertThat(output).contains(
                "\"operation\":\"LINK_CREATE\"",
                "\"serviceId\":\"baton-service\"",
                "\"linkId\":\"" + LINK_ID + "\"",
                "\"requestId\":\"link-create-history\""
        ).doesNotContain(IDEMPOTENCY_KEY, BATON_TARGET_PATH, "VOvLShvx93kQpj8x7w2HYQ");
    }

    @Test
    @DisplayName("같은 링크 생성 요청의 재시도는 동일한 단축 URL과 200으로 응답한다")
    void replaysLinkCreationContract(CapturedOutput output) throws Exception {
        when(useCase.createLink(any())).thenReturn(new CreatedLinkResult(
                linkResult(),
                URI.create("https://go.example/l/VOvLShvx93kQpj8x7w2HYQ"),
                true
        ));

        mockMvc.perform(post("/api/v1/links")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "BATON",
                                  "targetPath": "%s",
                                  "purpose": "NAVIGATION",
                                  "expiresAt": "2026-07-30T10:00:00Z"
                                }
                                """.formatted(BATON_TARGET_PATH)))
                .andExpect(status().isOk())
                .andExpect(header().string("Location", "/api/v1/links/" + LINK_ID))
                .andExpect(header().string(
                        "Idempotency-Replayed",
                        "true"
                ))
                .andExpect(jsonPath("$.shortUrl")
                        .value("https://go.example/l/VOvLShvx93kQpj8x7w2HYQ"))
                .andDo(documentManagementEndpoint("links-create-replay"));

        assertThat(output).contains("\"operation\":\"LINK_CREATE_REPLAY\"");
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("operationalReplayErrors")
    @DisplayName("링크 생성·재시도 실패는 원인별 오류를 반환하고 완료 이력을 남기지 않는다")
    void returnsOperationalReplayError(
            RuntimeException exception,
            String code,
            CapturedOutput output
    ) throws Exception {
        when(useCase.createLink(any())).thenThrow(exception);

        mockMvc.perform(post("/api/v1/links")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "BATON",
                                  "targetPath": "%s",
                                  "purpose": "NAVIGATION"
                                }
                                """.formatted(BATON_TARGET_PATH)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        assertThat(output).doesNotContain("관리 작업 완료");
    }

    private static Stream<Arguments> operationalReplayErrors() {
        return Stream.of(
                Arguments.of(
                        new IllegalStateException("service-failed"),
                        "INTERNAL_ERROR"
                ),
                Arguments.of(
                        new LinkCreationReplayUnavailableException(LINK_ID),
                        "LINK_CREATION_REPLAY_UNAVAILABLE"
                ),
                Arguments.of(
                        new LinkCodeReplayMismatchException(),
                        "LINK_CODE_REPLAY_UNAVAILABLE"
                ),
                Arguments.of(
                        new PublicLinkOriginReplayUnavailableException(),
                        "PUBLIC_LINK_ORIGIN_REPLAY_UNAVAILABLE"
                ),
                Arguments.of(
                        new LinkCodeKeyBindingException(),
                        "LINK_CODE_CONFIGURATION_MISMATCH"
                )
        );
    }

    @Test
    @DisplayName("관리 링크 조회는 원문 공개 코드와 단축 URL을 노출하지 않는다")
    void getsManagedLinkWithoutRawShortUrl(CapturedOutput output) throws Exception {
        when(useCase.getLink(LINK_ID)).thenReturn(linkResult());

        mockMvc.perform(get("/api/v1/links/{linkId}", LINK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(LINK_ID.toString()))
                .andExpect(jsonPath("$.targetSystem").value("BATON"))
                .andExpect(jsonPath("$.targetPath").value(BATON_TARGET_PATH))
                .andExpect(jsonPath("$.purpose").value("NAVIGATION"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.evaluatedAt").value(CREATED_AT.plusSeconds(60).toString()))
                .andExpect(jsonPath("$.shortUrl").doesNotExist())
                .andDo(documentManagementEndpoint("links-get"));

        assertThat(output).doesNotContain("관리 작업 완료");
    }

    @Test
    @DisplayName("존재하지 않는 관리 링크 조회는 404로 응답한다")
    void returnsNotFoundForMissingManagedLink() throws Exception {
        when(useCase.getLink(LINK_ID)).thenThrow(new LinkNotFoundException());

        mockMvc.perform(get("/api/v1/links/{linkId}", LINK_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("관리 링크 폐기 응답은 최초 폐기 시각을 유지하고 단축 URL을 노출하지 않는다")
    void revokesManagedLinkWithoutRawShortUrl(CapturedOutput output) throws Exception {
        Instant firstRevokedAt = Instant.parse("2026-07-29T11:00:00Z");
        when(useCase.revokeLink(LINK_ID)).thenReturn(linkResult(firstRevokedAt));

        mockMvc.perform(put("/api/v1/links/{linkId}/revocation", LINK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(LINK_ID.toString()))
                .andExpect(jsonPath("$.revokedAt").value(firstRevokedAt.toString()))
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.evaluatedAt").value(firstRevokedAt.plusSeconds(60).toString()))
                .andExpect(jsonPath("$.shortUrl").doesNotExist())
                .andDo(documentManagementEndpoint("links-revoke"));

        verify(useCase).revokeLink(LINK_ID);
        assertThat(output).contains("\"operation\":\"LINK_REVOKE\"");
    }

    @Test
    @DisplayName("링크 생성 요청에 소문자 표준 UUID 멱등성 키가 없으면 400으로 응답한다")
    void requiresIdempotencyKey() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "BATON",
                                  "targetPath": "%s",
                                  "purpose": "NAVIGATION"
                                }
                                """.formatted(BATON_TARGET_PATH)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @ParameterizedTest(name = "{index}: {0}")
    @ValueSource(strings = {
            "targetSystem=0",
            "targetSystem=\"0\"",
            "targetSystem=\" BATON\"",
            "targetSystem=\"UNKNOWN_SYSTEM\"",
            "purpose=0"
    })
    @DisplayName("대상 열거형이 정확하지 않으면 서비스 호출 전에 400으로 거부한다")
    void rejectsInexactTargetEnumsBeforeApplication(String input) throws Exception {
        String[] fieldAndValue = input.split("=", 2);
        String targetSystem = fieldAndValue[0].equals("targetSystem")
                ? fieldAndValue[1]
                : "\"BATON\"";
        String purpose = fieldAndValue[0].equals("purpose")
                ? fieldAndValue[1]
                : "\"NAVIGATION\"";

        mockMvc.perform(post("/api/v1/links")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": %s,
                                  "targetPath": "%s",
                                  "purpose": %s
                                }
                                """.formatted(targetSystem, BATON_TARGET_PATH, purpose)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("저장할 수 없는 생성 시각은 정해진 400 오류로 응답한다")
    void mapsUnstorableCreationTimeToInvalidRequest() throws Exception {
        when(useCase.createLink(any())).thenThrow(InvalidRequestException.creationTime());

        mockMvc.perform(post("/api/v1/links")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "BATON",
                                  "targetPath": "%s",
                                  "purpose": "NAVIGATION",
                                  "expiresAt": "2026-07-30T10:00:00.123456001Z"
                                }
                                """.formatted(BATON_TARGET_PATH)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        verify(useCase).createLink(any());
    }

    @ParameterizedTest(name = "{index}: {0}")
    @ValueSource(strings = {
            "expiresAt=\"2026-07-30T23:59:60Z\"",
            "expiresAt=\"2026-07-30T24:00:00Z\"",
            "expiresAt=\"2026-07-30T10:60:00Z\"",
            "expiresAt=\"+02026-07-30T10:00:00Z\"",
            "expiresAt=1780000000",
            "expiresAt=\" 2026-07-30T10:00:00Z \"",
            "expiresAt=\"2026-07-30T10:00:00+00:00\"",
            "expiresAt=\"2026-07-30t10:00:00z\"",
            "expiresAt=\"2026-07-30T10:00Z\"",
            "expiresAt=\"2026-07-30T10:00:00.Z\"",
            "notBefore=1780000000"
    })
    @DisplayName("표준 형식이 아닌 생성 시각은 예약 전에 400 INVALID_REQUEST로 거부한다")
    void rejectsNonCanonicalCreationTimesBeforeApplication(String input)
            throws Exception {
        String[] fieldAndValue = input.split("=", 2);
        mockMvc.perform(post("/api/v1/links")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "BATON",
                                  "targetPath": "%s",
                                  "purpose": "NAVIGATION",
                                  "%s": %s
                                }
                                """.formatted(
                                        BATON_TARGET_PATH,
                                        fieldAndValue[0],
                                        fieldAndValue[1]
                                )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(useCase);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @ValueSource(strings = {
            "2026-07-30T10:00:00.000000Z",
            "2026-07-30T10:00:00.123456Z",
            "2026-07-30T10:00:00.123456789Z"
    })
    @DisplayName("계약된 마이크로초와 과거 나노초 시각은 원본 Instant로 전달한다")
    void forwardsCanonicalCreationTimesWithoutChangingTheirMeaning(String rawTime)
            throws Exception {
        when(useCase.createLink(any())).thenAnswer(invocation -> {
            CreateLinkCommand command = invocation.getArgument(0);
            assertThat(command.expiresAt()).isEqualTo(Instant.parse(rawTime));
            return new CreatedLinkResult(
                    linkResult(),
                    URI.create("https://go.example/l/VOvLShvx93kQpj8x7w2HYQ"),
                    true
            );
        });

        mockMvc.perform(post("/api/v1/links")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "BATON",
                                  "targetPath": "%s",
                                  "purpose": "NAVIGATION",
                                  "expiresAt": "%s"
                                }
                                """.formatted(BATON_TARGET_PATH, rawTime)))
                .andExpect(status().isOk())
                .andExpect(header().string(
                        "Idempotency-Replayed",
                        "true"
                ));

        verify(useCase).createLink(any());
    }

    @Test
    @DisplayName("같은 멱등성 키의 다른 생성 요청은 정해진 409 오류로 응답한다")
    void rejectsIdempotencyKeyReuse() throws Exception {
        when(useCase.createLink(any())).thenThrow(new IdempotencyKeyConflictException());

        mockMvc.perform(post("/api/v1/links")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "ROUND",
                                  "targetPath": "/room/abcd-efgh-jkmn",
                                  "purpose": "MEETING_ENTRY"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("지원하지 않는 링크 생성 본문 형식은 정해진 415 오류로 응답한다")
    void rejectsUnsupportedMediaType() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("unsupported"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("존재하지 않는 링크 API 경로는 정해진 404 오류로 응답한다")
    void returnsNotFoundForUnknownRoute() throws Exception {
        mockMvc.perform(get("/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }
    @Test
    @DisplayName("빈 대상 경로는 400 INVALID_LINK로 응답한다")
    void rejectsBlankTargetPathAsInvalidLink() throws Exception {
        when(useCase.createLink(any())).thenThrow(new LinkValidationException("검증 실패"));

        mockMvc.perform(post("/api/v1/links")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "BATON",
                                  "targetPath": "",
                                  "purpose": "NAVIGATION"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LINK"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }
    private static RestDocumentationResultHandler documentManagementEndpoint(
            String identifier
    ) {
        return document(identifier, preprocessRequest(modifyHeaders().set(
                HttpHeaders.AUTHORIZATION,
                "Bearer <management-jwt>"
        )));
    }

    private LinkResult linkResult() {
        return linkResult(null);
    }

    private LinkResult linkResult(Instant revokedAt) {
        return new LinkResult(
                LINK_ID,
                TargetSystem.BATON,
                BATON_TARGET_PATH,
                LinkPurpose.NAVIGATION,
                null,
                Instant.parse("2026-07-30T10:00:00Z"),
                revokedAt,
                CREATED_AT,
                (revokedAt == null ? CREATED_AT : revokedAt).plusSeconds(60)
        );
    }
}
