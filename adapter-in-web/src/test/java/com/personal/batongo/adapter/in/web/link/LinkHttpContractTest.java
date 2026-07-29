package com.personal.batongo.adapter.in.web.link;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.batongo.adapter.in.web.GlobalExceptionHandler;
import com.personal.batongo.adapter.in.web.PublicLinkProperties;
import com.personal.batongo.adapter.in.web.RequestIdFilter;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LinkHttpContractTest {

    private static final UUID LINK_ID = UUID.fromString("83a430c4-5c5d-4eb4-a815-7a5ba1fd4aae");
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
    @DisplayName("링크 생성 응답은 원문 코드 대신 한 번 사용할 short URL과 안정된 필드를 반환한다")
    void createsLinkContract() throws Exception {
        LinkResult link = linkResult();
        when(useCase.createLink(any())).thenReturn(new CreatedLinkResult(
                link,
                "VOvLShvx93kQpj8x7w2HYQ"
        ));

        mockMvc.perform(post("/api/v1/links")
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
        return new LinkResult(
                LINK_ID,
                TargetSystem.BATON,
                "/teams/team-1",
                LinkPurpose.NAVIGATION,
                null,
                Instant.parse("2026-07-30T10:00:00Z"),
                null,
                CREATED_AT
        );
    }
}
