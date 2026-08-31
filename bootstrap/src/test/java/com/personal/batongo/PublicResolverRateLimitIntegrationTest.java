package com.personal.batongo;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.batongo.adapter.in.web.FilterErrorResponseWriter;
import com.personal.batongo.adapter.in.web.PublicLinkErrorPage;
import com.personal.batongo.adapter.in.web.ManagementApiSecurityConfiguration;
import com.personal.batongo.adapter.in.web.PublicResolverRateLimitProperties;
import com.personal.batongo.adapter.in.web.PublicResolverRateLimiter;
import com.personal.batongo.adapter.in.web.link.LinkResolverController;
import com.personal.batongo.application.link.LinkCodeKeyGuard;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.ResolvedLinkResult;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
        controllers = LinkResolverController.class,
        properties = {
                "baton-go.public-resolver-rate-limit.capacity=1",
                "baton-go.public-resolver-rate-limit.window=1h",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example",
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.security.oauth2.server.resource.autoconfigure.web."
                        + "OAuth2ResourceServerWebSecurityAutoConfiguration"
        },
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = ManagementApiSecurityConfiguration.class
        )
)
@EnableConfigurationProperties(PublicResolverRateLimitProperties.class)
@Import({
        PublicResolverRateLimiter.class,
        PublicLinkErrorPage.class,
        FilterErrorResponseWriter.class
})
class PublicResolverRateLimitIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SmartLinkUseCase smartLinkUseCase;

    @MockitoBean
    private MeterRegistry meterRegistry;

    @MockitoBean
    private LinkCodeKeyGuard linkCodeKeyGuard;

    @Test
    @DisplayName("실제 Spring 조립은 공개 링크 요청 제한 설정과 필터 순서를 적용한다")
    void assemblesPublicResolverRateLimit() throws Exception {
        String rawCode = "A".repeat(22);
        when(smartLinkUseCase.resolveLink(rawCode)).thenReturn(
                new ResolvedLinkResult(URI.create("https://baton.example/teams/active"))
        );

        mockMvc.perform(get("/l/{code}", rawCode))
                .andExpect(status().isFound());

        mockMvc.perform(get("/l/{code}", rawCode).accept(MediaType.TEXT_HTML))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, "3600"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("3600초 후에 다시 열어 주세요")));
    }
}
