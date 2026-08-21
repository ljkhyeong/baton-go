package com.personal.batongo.adapter.in.web.link;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.batongo.adapter.in.web.FilterErrorResponseWriter;
import com.personal.batongo.adapter.in.web.ManagementAuthenticationFilter;
import com.personal.batongo.adapter.in.web.ManagementProperties;
import com.personal.batongo.adapter.in.web.RequestIdFilter;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreatedLinkResult;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.LinkResult;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class ManagementAuthenticationHttpContractTest {

    private static final String MANAGEMENT_TOKEN =
            "management-token-with-at-least-32-characters";
    private static final String BEARER_CHALLENGE =
            "Bearer realm=\"baton-go-management\"";
    private static final String IDEMPOTENCY_KEY = "8e448211-66ae-44ab-9888-c4960648c22b";
    private static final String BATON_TARGET_PATH =
            "/teams/8e448211-66ae-44ab-9888-c4960648c22b"
                    + "/seasons/713d9cb7-2842-4f9f-b3cc-e31d98c6238a";

    private SmartLinkUseCase useCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        useCase = mock(SmartLinkUseCase.class);
        LinkManagementController controller = new LinkManagementController(useCase);
        ManagementAuthenticationFilter authenticationFilter =
                new ManagementAuthenticationFilter(
                        new ManagementProperties(MANAGEMENT_TOKEN),
                        new FilterErrorResponseWriter(new ObjectMapper())
                );

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new RequestIdFilter(), authenticationFilter)
                .build();
    }

    @Test
    @DisplayName("Bearer 스킴은 대소문자를 구분하지 않고 연속 SP를 허용한다")
    void acceptsCaseInsensitiveSchemeAndRepeatedSpaces() throws Exception {
        when(useCase.createLink(any())).thenReturn(createdLink());

        mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "bEaReR   " + MANAGEMENT_TOKEN)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest()))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));

        verify(useCase).createLink(any());
    }

    @ParameterizedTest(name = "{index}: {0}")
    @ValueSource(strings = {
            "Bearer invalid-management-token",
            "Bearer\tmanagement-token-with-at-least-32-characters"
    })
    @DisplayName("잘못된 관리 credential은 Bearer challenge가 있는 401 오류로 응답한다")
    void rejectsInvalidCredentialWithBearerChallenge(String authorization) throws Exception {
        mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code")
                        .value("MANAGEMENT_AUTHENTICATION_REQUIRED"));

        verifyNoInteractions(useCase);
    }

    @ParameterizedTest(name = "{index}: {0}")
    @ValueSource(strings = {
            "/api/v1;x/links",
            "/api/v%31/links",
            "/api/v1/links;x"
    })
    @DisplayName("Spring MVC가 관리 API로 해석하는 경로 변형은 인증 없이 호출할 수 없다")
    void rejectsMappedPathVariantWithoutCredential(String path) throws Exception {
        mockMvc.perform(post(URI.create(path))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "BATON",
                                  "targetPath": "%s",
                                  "purpose": "NAVIGATION"
                                }
                                """.formatted(BATON_TARGET_PATH)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE))
                .andExpect(jsonPath("$.code")
                        .value("MANAGEMENT_AUTHENTICATION_REQUIRED"));

        verifyNoInteractions(useCase);
    }

    private String createRequest() {
        return """
                {
                  "targetSystem": "BATON",
                  "targetPath": "%s",
                  "purpose": "NAVIGATION"
                }
                """.formatted(BATON_TARGET_PATH);
    }

    private CreatedLinkResult createdLink() {
        return new CreatedLinkResult(
                new LinkResult(
                        UUID.fromString("83a430c4-5c5d-4eb4-a815-7a5ba1fd4aae"),
                        TargetSystem.BATON,
                        BATON_TARGET_PATH,
                        LinkPurpose.NAVIGATION,
                        null,
                        Instant.parse("2026-07-30T10:00:00Z"),
                        null,
                        Instant.parse("2026-07-29T10:00:00Z")
                ),
                URI.create("https://go.example/l/VOvLShvx93kQpj8x7w2HYQ"),
                false
        );
    }
}
