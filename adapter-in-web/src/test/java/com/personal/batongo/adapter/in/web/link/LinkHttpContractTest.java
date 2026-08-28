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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.batongo.adapter.in.web.GlobalExceptionHandler;
import com.personal.batongo.adapter.in.web.RequestIdFilter;
import com.personal.batongo.application.link.error.InvalidCreationTimeException;
import com.personal.batongo.application.link.error.IdempotencyKeyConflictException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreateLinkCommand;
import com.personal.batongo.application.link.error.LinkCodeKeyBindingException;
import com.personal.batongo.application.link.error.LinkCodeReplayMismatchException;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.error.PublicLinkOriginReplayUnavailableException;
import com.personal.batongo.application.link.error.StoredTargetPolicyViolationException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreatedLinkResult;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.LinkResult;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.ResolvedLinkResult;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.LinkUnavailableException;
import com.personal.batongo.domain.link.LinkValidationException;
import com.personal.batongo.domain.link.TargetSystem;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.restdocs.mockmvc.RestDocumentationResultHandler;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(RestDocumentationExtension.class)
class LinkHttpContractTest {

    private static final UUID LINK_ID = UUID.fromString("83a430c4-5c5d-4eb4-a815-7a5ba1fd4aae");
    private static final String IDEMPOTENCY_KEY = "8e448211-66ae-44ab-9888-c4960648c22b";
    private static final Instant CREATED_AT = Instant.parse("2026-07-29T10:00:00Z");
    private static final String BATON_TARGET_PATH =
            "/teams/8e448211-66ae-44ab-9888-c4960648c22b"
                    + "/seasons/713d9cb7-2842-4f9f-b3cc-e31d98c6238a";
    private static final String BATON_DESTINATION = "https://baton.example" + BATON_TARGET_PATH;
    private static final String TARGET_POLICY_VIOLATION_METRIC =
            "baton.go.public.resolver.target.contract.violations";
    private static final String PUBLIC_NOT_FOUND_REQUEST_ID = "public-not-found-contract";

    private SmartLinkUseCase useCase;
    private SimpleMeterRegistry meterRegistry;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp(RestDocumentationContextProvider restDocumentation) {
        useCase = mock(SmartLinkUseCase.class);
        meterRegistry = new SimpleMeterRegistry();
        LinkManagementController managementController = new LinkManagementController(useCase);
        LinkResolverController resolverController = new LinkResolverController(useCase);
        var jsonMapper = JsonMapper.builder()
                .findAndAddModules()
                .build();
        mockMvc = MockMvcBuilders.standaloneSetup(managementController, resolverController)
                .setControllerAdvice(new GlobalExceptionHandler(meterRegistry))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper))
                .addFilters(new RequestIdFilter())
                .apply(documentationConfiguration(restDocumentation))
                .build();
    }

    @Test
    @DisplayName("링크 생성 응답은 공개 코드가 포함된 short URL과 안정된 필드를 반환한다")
    void createsLinkContract() throws Exception {
        LinkResult link = linkResult();
        when(useCase.createLink(any())).thenReturn(new CreatedLinkResult(
                link,
                URI.create("https://go.example/l/VOvLShvx93kQpj8x7w2HYQ"),
                false
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
    }

    @Test
    @DisplayName("같은 링크 생성 요청의 재시도는 동일한 short URL과 200으로 응답한다")
    void replaysLinkCreationContract() throws Exception {
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
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("operationalReplayErrors")
    @DisplayName("재생 안전성을 보장할 수 없으면 원인별 운영 오류로 응답한다")
    void returnsOperationalReplayError(
            RuntimeException exception,
            String code
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
    }

    private static Stream<Arguments> operationalReplayErrors() {
        return Stream.of(
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
    @DisplayName("관리 링크 조회는 원문 공개 코드와 short URL을 노출하지 않는다")
    void getsManagedLinkWithoutRawShortUrl() throws Exception {
        when(useCase.getLink(LINK_ID)).thenReturn(linkResult());

        mockMvc.perform(get("/api/v1/links/{linkId}", LINK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(LINK_ID.toString()))
                .andExpect(jsonPath("$.targetSystem").value("BATON"))
                .andExpect(jsonPath("$.targetPath").value(BATON_TARGET_PATH))
                .andExpect(jsonPath("$.purpose").value("NAVIGATION"))
                .andExpect(jsonPath("$.shortUrl").doesNotExist())
                .andDo(documentManagementEndpoint("links-get"));
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
    @DisplayName("관리 링크 폐기 응답은 최초 폐기 시각을 유지하고 short URL을 노출하지 않는다")
    void revokesManagedLinkWithoutRawShortUrl() throws Exception {
        Instant firstRevokedAt = Instant.parse("2026-07-29T11:00:00Z");
        when(useCase.revokeLink(LINK_ID)).thenReturn(linkResult(firstRevokedAt));

        mockMvc.perform(put("/api/v1/links/{linkId}/revocation", LINK_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(LINK_ID.toString()))
                .andExpect(jsonPath("$.revokedAt").value(firstRevokedAt.toString()))
                .andExpect(jsonPath("$.shortUrl").doesNotExist())
                .andDo(documentManagementEndpoint("links-revoke"));

        verify(useCase).revokeLink(LINK_ID);
    }

    @Test
    @DisplayName("링크 생성 요청에 canonical UUID 멱등성 키가 없으면 400으로 응답한다")
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
    @DisplayName("target enum의 비정확한 입력은 application 호출 전에 400으로 거부한다")
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
    @DisplayName("저장할 수 없는 생성 시각 예외는 안정된 400으로 응답한다")
    void mapsUnstorableCreationTimeToInvalidRequest() throws Exception {
        when(useCase.createLink(any())).thenThrow(new InvalidCreationTimeException());

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
    @DisplayName("비canonical 생성 시각은 예약 전에 400 INVALID_REQUEST로 거부한다")
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
    @DisplayName("같은 멱등성 키의 다른 생성 요청은 안정된 409 오류로 응답한다")
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
    @DisplayName("지원하지 않는 링크 API 메서드는 안정된 405 오류로 응답한다")
    void rejectsUnsupportedMethod() throws Exception {
        mockMvc.perform(post("/l/VOvLShvx93kQpj8x7w2HYQ"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("지원하지 않는 링크 생성 본문 형식은 안정된 415 오류로 응답한다")
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
    @DisplayName("존재하지 않는 링크 API 경로는 안정된 404 오류로 응답한다")
    void returnsNotFoundForUnknownRoute() throws Exception {
        mockMvc.perform(get("/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("활성 공개 링크는 신뢰 대상에 302로 응답한다")
    void resolvesLinkContract() throws Exception {
        when(useCase.resolveLink("VOvLShvx93kQpj8x7w2HYQ"))
                .thenReturn(new ResolvedLinkResult(
                        URI.create(BATON_DESTINATION)
                ));

        mockMvc.perform(get("/l/VOvLShvx93kQpj8x7w2HYQ"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", BATON_DESTINATION))
                .andDo(document("links-resolve"));
    }

    @Test
    @DisplayName("공개 링크 HEAD는 리다이렉트 헤더만 반환하고 링크 상태를 변경하지 않는다")
    void resolvesHeadWithoutMutatingLinkState() throws Exception {
        String rawCode = "VOvLShvx93kQpj8x7w2HYQ";
        when(useCase.resolveLink(rawCode)).thenReturn(new ResolvedLinkResult(
                URI.create(BATON_DESTINATION)
        ));

        mockMvc.perform(head("/l/{code}", rawCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", BATON_DESTINATION))
                .andExpect(content().string(""))
                .andDo(document("links-resolve-head"));

        verify(useCase).resolveLink(rawCode);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @CsvSource({
            "NOT_ACTIVE, 404, LINK_NOT_ACTIVE",
            "EXPIRED, 410, LINK_EXPIRED",
            "REVOKED, 410, LINK_REVOKED"
    })
    @DisplayName("사용할 수 없는 공개 링크는 사유별 안정된 오류로 응답한다")
    void returnsUnavailableContract(
            LinkUnavailableException.Reason reason,
            int expectedStatus,
            String expectedCode
    ) throws Exception {
        when(useCase.resolveLink("VOvLShvx93kQpj8x7w2HYQ"))
                .thenThrow(new LinkUnavailableException(
                        reason,
                        "링크를 사용할 수 없습니다"
                ));

        mockMvc.perform(get("/l/VOvLShvx93kQpj8x7w2HYQ"))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.code").value(expectedCode))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("일반 미존재와 저장 target 계약 위반 GET은 같은 공개 404이며 위반만 기록한다")
    void hidesStoredTargetPolicyViolationLikeMissingLinkForGet() throws Exception {
        String missingCode = "missing-link-code";
        when(useCase.resolveLink(missingCode)).thenThrow(new LinkNotFoundException());

        String missingBody = performPublicNotFoundGet(missingCode);

        assertThat(storedTargetPolicyViolationCount()).isZero();

        String violatingCode = "stored-policy-violation";
        when(useCase.resolveLink(violatingCode))
                .thenThrow(new StoredTargetPolicyViolationException(LINK_ID));

        String violationBody = performPublicNotFoundGet(violatingCode);

        assertThat(violationBody).isEqualTo(missingBody);
        assertThat(storedTargetPolicyViolationCount()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("일반 미존재와 저장 target 계약 위반 HEAD는 같은 본문 없는 404이며 위반만 기록한다")
    void hidesStoredTargetPolicyViolationLikeMissingLinkForHead() throws Exception {
        String missingCode = "missing-link-code";
        when(useCase.resolveLink(missingCode)).thenThrow(new LinkNotFoundException());

        performPublicNotFoundHead(missingCode);

        assertThat(storedTargetPolicyViolationCount()).isZero();

        String violatingCode = "stored-policy-violation";
        when(useCase.resolveLink(violatingCode))
                .thenThrow(new StoredTargetPolicyViolationException(LINK_ID));

        performPublicNotFoundHead(violatingCode);

        assertThat(storedTargetPolicyViolationCount()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("알려진 값의 비허용 target 조합은 안정된 400 INVALID_LINK로 응답한다")
    void rejectsKnownDisallowedTargetCombinationAsInvalidLink() throws Exception {
        when(useCase.createLink(any())).thenThrow(new LinkValidationException("검증 실패"));

        mockMvc.perform(post("/api/v1/links")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "BATON",
                                  "targetPath": "%s",
                                  "purpose": "MEETING_ENTRY"
                                }
                                """.formatted(BATON_TARGET_PATH)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LINK"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    private String performPublicNotFoundGet(String rawCode) throws Exception {
        return mockMvc.perform(get("/l/{code}", rawCode)
                        .header("X-Request-Id", PUBLIC_NOT_FOUND_REQUEST_ID))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(header().string(
                        "X-Request-Id",
                        PUBLIC_NOT_FOUND_REQUEST_ID
                ))
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.requestId").value(PUBLIC_NOT_FOUND_REQUEST_ID))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private void performPublicNotFoundHead(String rawCode) throws Exception {
        mockMvc.perform(head("/l/{code}", rawCode)
                        .header("X-Request-Id", PUBLIC_NOT_FOUND_REQUEST_ID))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(header().string(
                        "X-Request-Id",
                        PUBLIC_NOT_FOUND_REQUEST_ID
                ))
                .andExpect(content().string(""));
    }

    private double storedTargetPolicyViolationCount() {
        return meterRegistry.counter(TARGET_POLICY_VIOLATION_METRIC).count();
    }

    private static RestDocumentationResultHandler documentManagementEndpoint(
            String identifier
    ) {
        return document(identifier, preprocessRequest(modifyHeaders().set(
                HttpHeaders.AUTHORIZATION,
                "Bearer <management-token>"
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
                CREATED_AT
        );
    }
}
