package com.personal.batongo.adapter.in.web.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.batongo.adapter.in.web.GlobalExceptionHandler;
import com.personal.batongo.adapter.in.web.PublicLinkProperties;
import com.personal.batongo.adapter.in.web.RequestIdFilter;
import com.personal.batongo.adapter.in.web.StrictHttpJsonConfiguration;
import com.personal.batongo.application.link.CreationTimeStoragePolicy;
import com.personal.batongo.application.link.error.IdempotencyKeyConflictException;
import com.personal.batongo.application.link.error.InvalidIdempotencyKeyException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreateLinkCommand;
import com.personal.batongo.application.link.error.LinkCodeKeyBindingException;
import com.personal.batongo.application.link.error.LinkCodeReplayMismatchException;
import com.personal.batongo.application.link.error.LinkNotFoundException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

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
    void setUp() {
        useCase = mock(SmartLinkUseCase.class);
        meterRegistry = new SimpleMeterRegistry();
        LinkManagementController managementController = new LinkManagementController(
                useCase,
                new PublicLinkProperties(URI.create("https://go.example"))
        );
        LinkResolverController resolverController = new LinkResolverController(useCase);
        var jsonMapperBuilder = JsonMapper.builder()
                .findAndAddModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        new StrictHttpJsonConfiguration()
                .strictHttpJsonCustomizer()
                .customize(jsonMapperBuilder);
        mockMvc = MockMvcBuilders.standaloneSetup(managementController, resolverController)
                .setControllerAdvice(new GlobalExceptionHandler(meterRegistry))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapperBuilder))
                .addFilters(new RequestIdFilter())
                .build();
    }

    @Test
    @DisplayName("링크 생성 응답은 공개 코드가 포함된 short URL과 안정된 필드를 반환한다")
    void createsLinkContract() throws Exception {
        LinkResult link = linkResult();
        when(useCase.createLink(any())).thenReturn(new CreatedLinkResult(
                link,
                "VOvLShvx93kQpj8x7w2HYQ",
                false
        ));

        mockMvc.perform(post("/api/v1/links")
                        .header(LinkManagementController.IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY)
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
                        LinkManagementController.IDEMPOTENCY_REPLAYED_HEADER,
                        "false"
                ))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().exists(RequestIdFilter.HEADER_NAME))
                .andExpect(jsonPath("$.id").value(LINK_ID.toString()))
                .andExpect(jsonPath("$.shortUrl")
                        .value("https://go.example/l/VOvLShvx93kQpj8x7w2HYQ"))
                .andExpect(jsonPath("$.targetSystem").value("BATON"))
                .andExpect(jsonPath("$.targetPath").value(BATON_TARGET_PATH))
                .andExpect(jsonPath("$.purpose").value("NAVIGATION"))
                .andExpect(jsonPath("$.createdAt").value("2026-07-29T10:00:00Z"));
    }

    @Test
    @DisplayName("같은 링크 생성 요청의 재시도는 동일한 short URL과 200으로 응답한다")
    void replaysLinkCreationContract() throws Exception {
        when(useCase.createLink(any())).thenReturn(new CreatedLinkResult(
                linkResult(),
                "VOvLShvx93kQpj8x7w2HYQ",
                true
        ));

        mockMvc.perform(post("/api/v1/links")
                        .header(LinkManagementController.IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY)
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
                        LinkManagementController.IDEMPOTENCY_REPLAYED_HEADER,
                        "true"
                ))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.shortUrl")
                        .value("https://go.example/l/VOvLShvx93kQpj8x7w2HYQ"));
    }

    @Test
    @DisplayName("링크 코드 설정 불일치로 재생할 수 없으면 운영 오류 코드로 응답한다")
    void returnsOperationalErrorWhenLinkCodeReplayIsUnavailable() throws Exception {
        when(useCase.createLink(any())).thenThrow(new LinkCodeReplayMismatchException());

        mockMvc.perform(post("/api/v1/links")
                        .header(LinkManagementController.IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "BATON",
                                  "targetPath": "%s",
                                  "purpose": "NAVIGATION",
                                  "expiresAt": "2026-07-30T10:00:00Z"
                                }
                                """.formatted(BATON_TARGET_PATH)))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("LINK_CODE_REPLAY_UNAVAILABLE"))
                .andExpect(jsonPath("$.message")
                        .value("현재 링크 코드 파생 설정으로 기존 링크를 재생할 수 없습니다"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("링크 코드 키가 데이터베이스 결합과 다르면 운영 오류 코드로 응답한다")
    void returnsOperationalErrorWhenLinkCodeKeyBindingIsUnavailable() throws Exception {
        when(useCase.createLink(any())).thenThrow(new LinkCodeKeyBindingException());

        mockMvc.perform(post("/api/v1/links")
                        .header(LinkManagementController.IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "BATON",
                                  "targetPath": "%s",
                                  "purpose": "NAVIGATION"
                                }
                                """.formatted(BATON_TARGET_PATH)))
                .andExpect(status().isInternalServerError())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("LINK_CODE_CONFIGURATION_MISMATCH"))
                .andExpect(jsonPath("$.message").value(
                        "링크 코드 파생 키를 현재 데이터베이스에 안전하게 결합할 수 없습니다"
                ))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("관리 링크 조회는 원문 공개 코드와 short URL을 노출하지 않는다")
    void getsManagedLinkWithoutRawShortUrl() throws Exception {
        when(useCase.getLink(LINK_ID)).thenReturn(linkResult());

        mockMvc.perform(get("/api/v1/links/{linkId}", LINK_ID))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().exists(RequestIdFilter.HEADER_NAME))
                .andExpect(jsonPath("$.id").value(LINK_ID.toString()))
                .andExpect(jsonPath("$.targetSystem").value("BATON"))
                .andExpect(jsonPath("$.targetPath").value(BATON_TARGET_PATH))
                .andExpect(jsonPath("$.purpose").value("NAVIGATION"))
                .andExpect(jsonPath("$.shortUrl").doesNotExist());
    }

    @Test
    @DisplayName("존재하지 않는 관리 링크 조회는 안정된 404 오류로 응답한다")
    void returnsNotFoundForMissingManagedLink() throws Exception {
        when(useCase.getLink(LINK_ID)).thenThrow(new LinkNotFoundException());

        mockMvc.perform(get("/api/v1/links/{linkId}", LINK_ID))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("관리 링크를 반복 폐기해도 최초 폐기 시각을 유지하고 short URL을 노출하지 않는다")
    void revokesManagedLinkIdempotentlyWithoutRawShortUrl() throws Exception {
        Instant firstRevokedAt = Instant.parse("2026-07-29T11:00:00Z");
        when(useCase.revokeLink(LINK_ID)).thenReturn(linkResult(firstRevokedAt));

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(put("/api/v1/links/{linkId}/revocation", LINK_ID))
                    .andExpect(status().isOk())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(header().exists(RequestIdFilter.HEADER_NAME))
                    .andExpect(jsonPath("$.id").value(LINK_ID.toString()))
                    .andExpect(jsonPath("$.revokedAt").value(firstRevokedAt.toString()))
                    .andExpect(jsonPath("$.shortUrl").doesNotExist());
        }

        verify(useCase, times(2)).revokeLink(LINK_ID);
    }

    @Test
    @DisplayName("존재하지 않는 관리 링크 폐기는 안정된 404 오류로 응답한다")
    void returnsNotFoundWhenRevokingMissingManagedLink() throws Exception {
        when(useCase.revokeLink(LINK_ID)).thenThrow(new LinkNotFoundException());

        mockMvc.perform(put("/api/v1/links/{linkId}/revocation", LINK_ID))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
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

    @ParameterizedTest
    @ValueSource(strings = {
            "8E448211-66AE-44AB-9888-C4960648C22B",
            "00000000-0000-0000-0000-000000000000",
            "00000000-0000-0000-8000-000000000000",
            "00000000-0000-6000-8000-000000000000",
            "00000000-0000-4000-7000-000000000000"
    })
    @DisplayName("기존 예약이 없는 과거 UUID 멱등성 키는 안정된 400으로 거부한다")
    void rejectsReplayOnlyIdempotencyKeyWithoutReservation(String idempotencyKey)
            throws Exception {
        when(useCase.createLink(any())).thenAnswer(invocation -> {
            CreateLinkCommand command = invocation.getArgument(0);
            assertThat(command.idempotencyKey().allowsNewReservation()).isFalse();
            throw new InvalidIdempotencyKeyException();
        });

        mockMvc.perform(post("/api/v1/links")
                        .header(LinkManagementController.IDEMPOTENCY_KEY_HEADER, idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "BATON",
                                  "targetPath": "%s",
                                  "purpose": "NAVIGATION"
                                }
                                """.formatted(BATON_TARGET_PATH)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"))
                .andExpect(jsonPath("$.message")
                        .value("Idempotency-Key는 canonical UUID 형식이어야 합니다"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        verify(useCase).createLink(any());
        verifyNoMoreInteractions(useCase);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @ValueSource(strings = {
            "targetSystem=0",
            "targetSystem=\"0\"",
            "targetSystem=\" BATON\"",
            "targetSystem=\"BATON \"",
            "purpose=0",
            "purpose=\"0\"",
            "purpose=\" NAVIGATION\"",
            "purpose=\"NAVIGATION \\t\""
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
                        .header(LinkManagementController.IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": %s,
                                  "targetPath": "%s",
                                  "purpose": %s
                                }
                                """.formatted(targetSystem, BATON_TARGET_PATH, purpose)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다"));

        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("과거 계약에도 없던 UUID 표기는 application 호출 전에 400으로 거부한다")
    void rejectsIdempotencyKeyOutsideHistoricalContract() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .header(LinkManagementController.IDEMPOTENCY_KEY_HEADER, "1-1-1-1-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "BATON",
                                  "targetPath": "%s",
                                  "purpose": "NAVIGATION"
                                }
                                """.formatted(BATON_TARGET_PATH)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        verifyNoMoreInteractions(useCase);
    }

    @ParameterizedTest
    @CsvSource({
            "notBefore, 1582-10-14T23:59:59.999999Z",
            "expiresAt, 1582-10-14T23:59:59.999999Z",
            "notBefore, +10000-01-01T00:00:00Z",
            "expiresAt, +10000-01-01T00:00:00Z",
            "notBefore, 2026-07-30T10:00:00.123456001Z",
            "expiresAt, 2026-07-30T10:00:00.123456001Z"
    })
    @DisplayName("저장 범위 밖이거나 마이크로초보다 세밀한 생성 시각은 안정된 400으로 거부한다")
    void rejectsUnstorableCreationTimes(String fieldName, String rawTime) throws Exception {
        when(useCase.createLink(any())).thenAnswer(invocation -> {
            CreateLinkCommand command = invocation.getArgument(0);
            CreationTimeStoragePolicy.requireStorable(
                    command.notBefore(),
                    command.expiresAt()
            );
            throw new AssertionError("저장할 수 없는 생성 시각을 허용했습니다");
        });

        mockMvc.perform(post("/api/v1/links")
                        .header(LinkManagementController.IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "BATON",
                                  "targetPath": "%s",
                                  "purpose": "NAVIGATION",
                                  "%s": "%s"
                                }
                                """.formatted(BATON_TARGET_PATH, fieldName, rawTime)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message")
                        .value("notBefore와 expiresAt은 1582-10-15T00:00:00Z 이상 "
                                + "9999-12-31T23:59:59.999999Z 이하의 마이크로초 단위여야 합니다"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        verify(useCase).createLink(any());
        verifyNoMoreInteractions(useCase);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @ValueSource(strings = {
            "\"2026-07-30T23:59:60Z\"",
            "\"2026-07-30T24:00:00Z\"",
            "\"2026-07-30T10:60:00Z\"",
            "\"+02026-07-30T10:00:00Z\"",
            "1780000000",
            "\" 2026-07-30T10:00:00Z \"",
            "\"2026-07-30T10:00:00+00:00\"",
            "\"2026-07-30t10:00:00z\"",
            "\"2026-07-30T10:00Z\""
    })
    @DisplayName("비canonical 생성 시각은 예약 전에 400 INVALID_REQUEST로 거부한다")
    void rejectsNonCanonicalCreationTimesBeforeApplication(String rawJsonValue)
            throws Exception {
        for (String fieldName : new String[]{"notBefore", "expiresAt"}) {
            mockMvc.perform(post("/api/v1/links")
                            .header(
                                    LinkManagementController.IDEMPOTENCY_KEY_HEADER,
                                    IDEMPOTENCY_KEY
                            )
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
                                            fieldName,
                                            rawJsonValue
                                    )))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다"));
        }

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
                    "VOvLShvx93kQpj8x7w2HYQ",
                    true
            );
        });

        mockMvc.perform(post("/api/v1/links")
                        .header(LinkManagementController.IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY)
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
                        LinkManagementController.IDEMPOTENCY_REPLAYED_HEADER,
                        "true"
                ));

        verify(useCase).createLink(any());
        verifyNoMoreInteractions(useCase);
    }

    @Test
    @DisplayName("같은 멱등성 키의 다른 생성 요청은 안정된 409 오류로 응답한다")
    void rejectsIdempotencyKeyReuse() throws Exception {
        when(useCase.createLink(any())).thenThrow(new IdempotencyKeyConflictException());

        mockMvc.perform(post("/api/v1/links")
                        .header(LinkManagementController.IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY)
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
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string(HttpHeaders.ALLOW, containsString("GET")))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("지원하지 않는 링크 생성 본문 형식은 안정된 415 오류로 응답한다")
    void rejectsUnsupportedMediaType() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .header(LinkManagementController.IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY)
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("unsupported"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("존재하지 않는 링크 API 경로는 안정된 404 오류로 응답한다")
    void returnsNotFoundForUnknownRoute() throws Exception {
        mockMvc.perform(get("/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("활성 공개 링크는 no-store와 no-referrer를 포함해 신뢰 대상에 302로 응답한다")
    void resolvesLinkContract() throws Exception {
        when(useCase.resolveLink("VOvLShvx93kQpj8x7w2HYQ"))
                .thenReturn(new ResolvedLinkResult(
                        LINK_ID,
                        URI.create(BATON_DESTINATION)
                ));

        mockMvc.perform(get("/l/VOvLShvx93kQpj8x7w2HYQ"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", BATON_DESTINATION))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().exists(RequestIdFilter.HEADER_NAME));
    }

    @Test
    @DisplayName("공개 링크 HEAD는 리다이렉트 헤더만 반환하고 링크 상태를 변경하지 않는다")
    void resolvesHeadWithoutMutatingLinkState() throws Exception {
        String rawCode = "VOvLShvx93kQpj8x7w2HYQ";
        when(useCase.resolveLink(rawCode)).thenReturn(new ResolvedLinkResult(
                LINK_ID,
                URI.create(BATON_DESTINATION)
        ));

        mockMvc.perform(head("/l/{code}", rawCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", BATON_DESTINATION))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().exists(RequestIdFilter.HEADER_NAME))
                .andExpect(content().string(""));

        verify(useCase).resolveLink(rawCode);
        verifyNoMoreInteractions(useCase);
    }

    @Test
    @DisplayName("폐기된 공개 링크는 request ID가 있는 안정된 410 오류로 응답한다")
    void returnsRevokedContract() throws Exception {
        when(useCase.resolveLink("VOvLShvx93kQpj8x7w2HYQ"))
                .thenThrow(new LinkUnavailableException(
                        LinkUnavailableException.Reason.REVOKED,
                        "폐기된 링크입니다"
                ));

        mockMvc.perform(get("/l/VOvLShvx93kQpj8x7w2HYQ"))
                .andExpect(status().isGone())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("LINK_REVOKED"))
                .andExpect(jsonPath("$.message").value("폐기된 링크입니다"))
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
        String message = "대상 시스템, 목적과 경로가 v1 신뢰 대상 계약에 맞지 않습니다";
        when(useCase.createLink(any())).thenThrow(new LinkValidationException(message));

        mockMvc.perform(post("/api/v1/links")
                        .header(LinkManagementController.IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "BATON",
                                  "targetPath": "%s",
                                  "purpose": "MEETING_ENTRY"
                                }
                                """.formatted(BATON_TARGET_PATH)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.code").value("INVALID_LINK"))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    @DisplayName("알려지지 않은 target enum 문자열은 400 INVALID_REQUEST로 응답한다")
    void rejectsUnknownTargetEnumAsInvalidRequest() throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .header(LinkManagementController.IDEMPOTENCY_KEY_HEADER, IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "UNKNOWN_SYSTEM",
                                  "targetPath": "%s",
                                  "purpose": "NAVIGATION"
                                }
                                """.formatted(BATON_TARGET_PATH)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("요청 형식이 올바르지 않습니다"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        verifyNoMoreInteractions(useCase);
    }

    private String performPublicNotFoundGet(String rawCode) throws Exception {
        return mockMvc.perform(get("/l/{code}", rawCode)
                        .header(RequestIdFilter.HEADER_NAME, PUBLIC_NOT_FOUND_REQUEST_ID))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string(
                        RequestIdFilter.HEADER_NAME,
                        PUBLIC_NOT_FOUND_REQUEST_ID
                ))
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("링크를 찾을 수 없습니다"))
                .andExpect(jsonPath("$.requestId").value(PUBLIC_NOT_FOUND_REQUEST_ID))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private void performPublicNotFoundHead(String rawCode) throws Exception {
        mockMvc.perform(head("/l/{code}", rawCode)
                        .header(RequestIdFilter.HEADER_NAME, PUBLIC_NOT_FOUND_REQUEST_ID))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string(
                        RequestIdFilter.HEADER_NAME,
                        PUBLIC_NOT_FOUND_REQUEST_ID
                ))
                .andExpect(content().string(""));
    }

    private double storedTargetPolicyViolationCount() {
        return meterRegistry.counter(TARGET_POLICY_VIOLATION_METRIC).count();
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
