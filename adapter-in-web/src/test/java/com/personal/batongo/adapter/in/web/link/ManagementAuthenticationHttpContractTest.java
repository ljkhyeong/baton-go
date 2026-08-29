package com.personal.batongo.adapter.in.web.link;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.modifyHeaders;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessRequest;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.batongo.adapter.in.web.FilterErrorResponseWriter;
import com.personal.batongo.adapter.in.web.ManagementApiSecurityConfiguration;
import com.personal.batongo.adapter.in.web.RequestIdFilter;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreatedLinkResult;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.LinkResult;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.restdocs.RestDocumentationContextProvider;
import org.springframework.restdocs.RestDocumentationExtension;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@ExtendWith(RestDocumentationExtension.class)
@WebMvcTest(
        controllers = LinkManagementController.class,
        properties = {
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example",
                "spring.security.oauth2.resourceserver.jwt.audiences=baton-go"
        }
)
@ContextConfiguration(classes = LinkManagementController.class)
@Import({
        ManagementApiSecurityConfiguration.class,
        FilterErrorResponseWriter.class
})
class ManagementAuthenticationHttpContractTest {

    private static final String MANAGEMENT_JWT = "test-management-jwt";
    private static final String BEARER_CHALLENGE =
            "Bearer realm=\"baton-go-management\"";
    private static final String IDEMPOTENCY_KEY = "8e448211-66ae-44ab-9888-c4960648c22b";
    private static final String BATON_TARGET_PATH =
            "/teams/8e448211-66ae-44ab-9888-c4960648c22b"
                    + "/seasons/713d9cb7-2842-4f9f-b3cc-e31d98c6238a";

    @Autowired
    private WebApplicationContext applicationContext;

    @MockitoBean
    private SmartLinkUseCase useCase;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp(RestDocumentationContextProvider restDocumentation) {
        mockMvc = MockMvcBuilders.webAppContextSetup(applicationContext)
                .addFilters(new RequestIdFilter())
                .apply(springSecurity())
                .apply(documentationConfiguration(restDocumentation))
                .build();
    }

    @Test
    @DisplayName("유효한 관리 JWT와 링크 생성 scope는 링크 생성을 허용한다")
    void acceptsJwtWithLinkCreationScope() throws Exception {
        when(jwtDecoder.decode(MANAGEMENT_JWT)).thenReturn(jwt(
                "baton-go.links.create"
        ));
        when(useCase.createLink(any())).thenReturn(createdLink());

        mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + MANAGEMENT_JWT)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest()))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE));

        verify(useCase).createLink(any());
    }

    @Test
    @DisplayName("검증할 수 없는 관리 JWT는 Bearer challenge가 있는 401 오류로 응답한다")
    void rejectsInvalidJwtWithBearerChallenge() throws Exception {
        when(jwtDecoder.decode("invalid-management-jwt"))
                .thenThrow(new BadJwtException("검증 실패"));

        mockMvc.perform(post("/api/v1/links")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer invalid-management-jwt"
                        )
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest()))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.code")
                        .value("MANAGEMENT_AUTHENTICATION_REQUIRED"))
                .andDo(document(
                        "management-authentication-required",
                        preprocessRequest(modifyHeaders().set(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer <invalid-management-jwt>"
                        ))
                ));

        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("링크 생성 scope가 없는 관리 JWT는 403 오류로 응답한다")
    void rejectsJwtWithoutLinkCreationScope() throws Exception {
        when(jwtDecoder.decode(MANAGEMENT_JWT)).thenReturn(jwt(
                "baton-go.links.read"
        ));

        mockMvc.perform(post("/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + MANAGEMENT_JWT)
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createRequest()))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(jsonPath("$.code")
                        .value("MANAGEMENT_AUTHORIZATION_REQUIRED"));

        verifyNoInteractions(useCase);
    }

    @Test
    @DisplayName("비정규 관리 API 경로는 Spring Security 경계에서 거부한다")
    void rejectsNonCanonicalManagementPath() throws Exception {
        mockMvc.perform(post(URI.create("/api/v1/links;x"))
                        .header("Idempotency-Key", IDEMPOTENCY_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "BATON",
                                  "targetPath": "%s",
                                  "purpose": "NAVIGATION"
                                }
                                """.formatted(BATON_TARGET_PATH)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(useCase);
    }

    private Jwt jwt(String scope) {
        return Jwt.withTokenValue(MANAGEMENT_JWT)
                .header("alg", "RS256")
                .subject("baton-service")
                .audience(List.of("baton-go"))
                .claim("scope", scope)
                .build();
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
