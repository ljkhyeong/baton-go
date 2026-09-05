package com.personal.batongo.adapter.in.web.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.modifyHeaders;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessRequest;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.batongo.adapter.in.web.FilterErrorResponseWriter;
import com.personal.batongo.adapter.in.web.GlobalExceptionHandler;
import com.personal.batongo.adapter.in.web.ManagementApiSecurityConfiguration;
import com.personal.batongo.adapter.in.web.ManagementOperationLogger;
import com.personal.batongo.adapter.in.web.RequestIdFilter;
import com.personal.batongo.adapter.in.web.WebMvcConfiguration;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreatedLinkResult;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.LinkResult;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.LinkSearchResult;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.UriTemplate;

@ExtendWith({RestDocumentationExtension.class, OutputCaptureExtension.class})
@WebMvcTest(
        properties = {
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example",
                "spring.security.oauth2.resourceserver.jwt.audiences=baton-go",
                "baton-go.target-contract-operations.enabled=true",
                "baton-go.target-contract-operations.private-ingress-confirmed=true"
        }
)
@ContextConfiguration(classes = ManagementAuthenticationHttpContractTest.WebControllerScan.class)
@Import({
        ManagementApiSecurityConfiguration.class,
        ManagementOperationLogger.class,
        FilterErrorResponseWriter.class,
        GlobalExceptionHandler.class,
        WebMvcConfiguration.class,
        SimpleMeterRegistry.class
})
class ManagementAuthenticationHttpContractTest {

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(
            basePackageClasses = RequestIdFilter.class,
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(
                    type = FilterType.ANNOTATION,
                    classes = RestController.class
            )
    )
    static class WebControllerScan {
    }

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
    private TargetContractOperationsUseCase operationsUseCase;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private MeterRegistry meterRegistry;

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
    void acceptsJwtWithLinkCreationScope(CapturedOutput output) throws Exception {
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
        assertThat(output).contains("\"serviceId\":\"baton-service\"")
                .doesNotContain(MANAGEMENT_JWT, IDEMPOTENCY_KEY, BATON_TARGET_PATH);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("managementWritesWithUnsupportedResponseTypes")
    @DisplayName("관리 쓰기는 비지원 응답 형식을 서비스 실행과 완료 이력 기록 전에 406으로 거부한다")
    void rejectsUnsupportedResponseTypeBeforeMutation(
            String ignoredDescription,
            MockHttpServletRequestBuilder request,
            String grantedScope,
            CapturedOutput output
    ) throws Exception {
        when(jwtDecoder.decode(MANAGEMENT_JWT)).thenReturn(jwt(grantedScope));

        mockMvc.perform(request
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + MANAGEMENT_JWT)
                        .header("X-Request-Id", "management-response-format"))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("X-Request-Id", "management-response-format"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.requestId").value("management-response-format"));

        verifyNoInteractions(useCase, operationsUseCase);
        assertThat(output).doesNotContain("관리 작업 완료");
    }

    private static Stream<Arguments> managementWritesWithUnsupportedResponseTypes() {
        String linkId = "83a430c4-5c5d-4eb4-a815-7a5ba1fd4aae";
        return Stream.of(
                Arguments.of(
                        "링크 생성의 XML 응답 요청",
                        post("/api/v1/links")
                                .header("Idempotency-Key", IDEMPOTENCY_KEY)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(createRequest())
                                .accept(MediaType.APPLICATION_XML),
                        "baton-go.links.create"
                ),
                Arguments.of(
                        "링크 폐기의 HTML 응답 요청",
                        put("/api/v1/links/{linkId}/revocation", linkId)
                                .accept(MediaType.TEXT_HTML),
                        "baton-go.links.revoke"
                ),
                Arguments.of(
                        "대상 계약 정리 폐기의 XML 응답 요청",
                        put("/api/v1/operations/link-target-contract-v1/links/{linkId}/revocation", linkId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"expectedVersion\":7}")
                                .accept(MediaType.APPLICATION_XML),
                        "baton-go.target-contract.operate"
                )
        );
    }

    @Test
    @DisplayName("검증할 수 없는 관리 JWT는 응답 형식 검사보다 먼저 Bearer challenge가 있는 401로 거부한다")
    void rejectsInvalidJwtWithBearerChallenge(CapturedOutput output) throws Exception {
        when(jwtDecoder.decode("invalid-management-jwt"))
                .thenThrow(new BadJwtException("검증 실패"));

        mockMvc.perform(post("/api/v1/links")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer invalid-management-jwt"
                        )
                        .accept(MediaType.APPLICATION_XML)
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
        assertThat(output).doesNotContain("관리 작업 완료");
        assertThat(meterRegistry.get("baton.go.management.authentication.service.failures")
                .counter().count()).isZero();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("managementRequestsWithWrongScope")
    @DisplayName("관리 API는 작업과 다른 scope를 403으로 거부한다")
    void rejectsJwtWithWrongScope(
            String ignoredDescription,
            MockHttpServletRequestBuilder request,
            String grantedScope
    ) throws Exception {
        when(jwtDecoder.decode(MANAGEMENT_JWT)).thenReturn(jwt(
                grantedScope
        ));

        mockMvc.perform(request.header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + MANAGEMENT_JWT
                ))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(jsonPath("$.code")
                        .value("MANAGEMENT_AUTHORIZATION_REQUIRED"));

        verifyNoInteractions(useCase, operationsUseCase);
    }

    private static Stream<Arguments> managementRequestsWithWrongScope() {
        String linkId = "83a430c4-5c5d-4eb4-a815-7a5ba1fd4aae";
        return Stream.of(
                Arguments.of(
                        "조회 scope로 링크 생성을 요청한다",
                        post("/api/v1/links"),
                        "baton-go.links.read"
                ),
                Arguments.of(
                        "생성 scope로 링크 조회를 요청한다",
                        get("/api/v1/links/{linkId}", linkId),
                        "baton-go.links.create"
                ),
                Arguments.of(
                        "조회 scope로 링크 폐기를 요청한다",
                        put("/api/v1/links/{linkId}/revocation", linkId),
                        "baton-go.links.read"
                ),
                Arguments.of(
                        "조회 scope로 대상 계약 운영을 요청한다",
                        get("/api/v1/operations/link-target-contract-v1/inventory"),
                        "baton-go.links.read"
                )
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"baton-go.links.create", "baton-go.links.revoke"})
    @DisplayName("링크 HEAD 조회는 조회 외 scope를 403으로 거부한다")
    void rejectsHeadRequestWithoutLinkReadScope(String grantedScope) throws Exception {
        when(jwtDecoder.decode(MANAGEMENT_JWT)).thenReturn(jwt(grantedScope));

        mockMvc.perform(head(
                        "/api/v1/links/{linkId}",
                        "83a430c4-5c5d-4eb4-a815-7a5ba1fd4aae"
                ).header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + MANAGEMENT_JWT
                ))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(content().string(""));

        verifyNoInteractions(useCase, operationsUseCase);
    }

    @Test
    @DisplayName("링크 HEAD 조회는 조회 scope로 현재 상태를 확인한다")
    void acceptsHeadRequestWithLinkReadScope() throws Exception {
        UUID linkId = UUID.fromString("83a430c4-5c5d-4eb4-a815-7a5ba1fd4aae");
        when(jwtDecoder.decode(MANAGEMENT_JWT)).thenReturn(jwt(
                "baton-go.links.read"
        ));
        when(useCase.getLink(linkId)).thenReturn(link());

        mockMvc.perform(head("/api/v1/links/{linkId}", linkId)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + MANAGEMENT_JWT
                        ))
                .andExpect(status().isOk());

        verify(useCase).getLink(linkId);
        verifyNoInteractions(operationsUseCase);
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "HEAD"})
    @DisplayName("관리 목록의 GET과 HEAD는 조회 scope로 접근할 수 있다")
    void acceptsReadScopeForLinkSearch(String method) throws Exception {
        when(jwtDecoder.decode(MANAGEMENT_JWT)).thenReturn(jwt("baton-go.links.read"));
        when(useCase.searchLinks(any())).thenReturn(new LinkSearchResult(
                List.of(link()), null, false, link().evaluatedAt()
        ));

        mockMvc.perform(request(HttpMethod.valueOf(method), "/api/v1/links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + MANAGEMENT_JWT))
                .andExpect(status().isOk());
        verify(useCase).searchLinks(any());
    }

    @Test
    @DisplayName("등록된 모든 관리 HTTP 메서드는 작업별 scope를 요구한다")
    void requiresOperationScopeForEveryRegisteredManagementMethod() throws Exception {
        when(jwtDecoder.decode(MANAGEMENT_JWT)).thenReturn(jwt(
                "baton-go.unrelated"
        ));

        List<ManagementRoute> routes = handlerMapping.getHandlerMethods().keySet().stream()
                .filter(mapping -> mapping.getPatternValues().stream()
                        .anyMatch(path -> path.startsWith("/api/v1/")))
                .peek(mapping -> assertThat(mapping.getMethodsCondition().getMethods())
                        .as("관리 핸들러는 HTTP 메서드를 명시해야 한다: %s", mapping)
                        .isNotEmpty())
                .flatMap(mapping -> mapping.getPatternValues().stream()
                        .filter(path -> path.startsWith("/api/v1/"))
                        .flatMap(path -> mapping.getMethodsCondition().getMethods().stream()
                                .flatMap(ManagementAuthenticationHttpContractTest::withImplicitHead)
                                .map(method -> new ManagementRoute(method, path))))
                .distinct()
                .sorted(Comparator.comparing(ManagementRoute::path)
                        .thenComparing(route -> route.method().name()))
                .toList();

        assertThat(routes).isNotEmpty();
        for (ManagementRoute route : routes) {
            UriTemplate uriTemplate = new UriTemplate(route.path());
            Map<String, String> variables = uriTemplate.getVariableNames().stream()
                    .collect(Collectors.toMap(
                            Function.identity(),
                            ignored -> "83a430c4-5c5d-4eb4-a815-7a5ba1fd4aae"
                    ));

            mockMvc.perform(request(
                            route.method(),
                            uriTemplate.expand(variables)
                    ).header(
                            HttpHeaders.AUTHORIZATION,
                            "Bearer " + MANAGEMENT_JWT
                    ))
                    .andExpect(status().isForbidden());
        }

        verifyNoInteractions(useCase, operationsUseCase);
    }

    private static Stream<HttpMethod> withImplicitHead(RequestMethod method) {
        HttpMethod httpMethod = HttpMethod.valueOf(method.name());
        return method == RequestMethod.GET
                ? Stream.of(httpMethod, HttpMethod.HEAD)
                : Stream.of(httpMethod);
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

    private static String createRequest() {
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
                link(),
                URI.create("https://go.example/l/VOvLShvx93kQpj8x7w2HYQ"),
                false
        );
    }

    private LinkResult link() {
        return new LinkResult(
                UUID.fromString("83a430c4-5c5d-4eb4-a815-7a5ba1fd4aae"),
                TargetSystem.BATON,
                BATON_TARGET_PATH,
                LinkPurpose.NAVIGATION,
                null,
                Instant.parse("2026-07-30T10:00:00Z"),
                null,
                Instant.parse("2026-07-29T10:00:00Z"),
                Instant.parse("2026-07-29T10:00:00Z")
        );
    }

    private record ManagementRoute(HttpMethod method, String path) {
    }
}
