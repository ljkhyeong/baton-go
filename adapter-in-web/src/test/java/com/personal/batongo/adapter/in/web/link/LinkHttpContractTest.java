package com.personal.batongo.adapter.in.web.link;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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
import com.personal.batongo.application.link.error.IdempotencyKeyConflictException;
import com.personal.batongo.application.link.error.LinkCodeKeyBindingException;
import com.personal.batongo.application.link.error.LinkCodeReplayMismatchException;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreatedLinkResult;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.LinkResult;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.ResolvedLinkResult;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.LinkUnavailableException;
import com.personal.batongo.domain.link.TargetSystem;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LinkHttpContractTest {

    private static final UUID LINK_ID = UUID.fromString("83a430c4-5c5d-4eb4-a815-7a5ba1fd4aae");
    private static final String IDEMPOTENCY_KEY = "8e448211-66ae-44ab-9888-c4960648c22b";
    private static final Instant CREATED_AT = Instant.parse("2026-07-29T10:00:00Z");

    private SmartLinkUseCase useCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCase = mock(SmartLinkUseCase.class);
        LinkManagementController managementController = new LinkManagementController(
                useCase,
                new PublicLinkProperties(URI.create("https://go.example"))
        );
        LinkResolverController resolverController = new LinkResolverController(useCase);
        mockMvc = MockMvcBuilders.standaloneSetup(managementController, resolverController)
                .setControllerAdvice(new GlobalExceptionHandler())
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
                                  "targetPath": "/teams/team-1",
                                  "purpose": "NAVIGATION",
                                  "expiresAt": "2026-07-30T10:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/links/" + LINK_ID))
                .andExpect(header().string(
                        LinkManagementController.IDEMPOTENCY_REPLAYED_HEADER,
                        "false"
                ))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().exists(RequestIdFilter.HEADER_NAME))
                .andExpect(jsonPath("$.id").value(LINK_ID.toString()))
                .andExpect(jsonPath("$.shortUrl")
                        .value("https://go.example/l/VOvLShvx93kQpj8x7w2HYQ"))
                .andExpect(jsonPath("$.targetSystem").value("BATON"))
                .andExpect(jsonPath("$.targetPath").value("/teams/team-1"))
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
                                  "targetPath": "/teams/team-1",
                                  "purpose": "NAVIGATION",
                                  "expiresAt": "2026-07-30T10:00:00Z"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Location", "/api/v1/links/" + LINK_ID))
                .andExpect(header().string(
                        LinkManagementController.IDEMPOTENCY_REPLAYED_HEADER,
                        "true"
                ))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
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
                                  "targetPath": "/teams/team-1",
                                  "purpose": "NAVIGATION",
                                  "expiresAt": "2026-07-30T10:00:00Z"
                                }
                                """))
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
                                  "targetPath": "/teams/team-1",
                                  "purpose": "NAVIGATION"
                                }
                                """))
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
                .andExpect(header().exists(RequestIdFilter.HEADER_NAME))
                .andExpect(jsonPath("$.id").value(LINK_ID.toString()))
                .andExpect(jsonPath("$.targetSystem").value("BATON"))
                .andExpect(jsonPath("$.targetPath").value("/teams/team-1"))
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
                                  "targetPath": "/teams/team-1",
                                  "purpose": "NAVIGATION"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
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
                                  "targetSystem": "BATON",
                                  "targetPath": "/teams/team-2",
                                  "purpose": "NAVIGATION"
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
                        URI.create("https://baton.example/teams/team-1")
                ));

        mockMvc.perform(get("/l/VOvLShvx93kQpj8x7w2HYQ"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://baton.example/teams/team-1"))
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
                URI.create("https://baton.example/teams/team-1")
        ));

        mockMvc.perform(head("/l/{code}", rawCode))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://baton.example/teams/team-1"))
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

    private LinkResult linkResult() {
        return linkResult(null);
    }

    private LinkResult linkResult(Instant revokedAt) {
        return new LinkResult(
                LINK_ID,
                TargetSystem.BATON,
                "/teams/team-1",
                LinkPurpose.NAVIGATION,
                null,
                Instant.parse("2026-07-30T10:00:00Z"),
                revokedAt,
                CREATED_AT
        );
    }
}
