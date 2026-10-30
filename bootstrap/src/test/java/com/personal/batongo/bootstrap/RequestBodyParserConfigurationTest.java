package com.personal.batongo.bootstrap;

import com.personal.batongo.adapter.in.web.GlobalExceptionHandler;
import com.personal.batongo.adapter.in.web.ManagementApiSecurityConfiguration;
import com.personal.batongo.adapter.in.web.RequestIdFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.client.RestTestClient;

/** 운영 application.yml의 기본 본문 파서 비활성화가 실제 HTTP 서버에서 유지되는지 확인한다. */
@SpringBootTest(classes = RequestBodyParserConfigurationTest.WebConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:1/jwks"
        })
@AutoConfigureRestTestClient
@DirtiesContext
class RequestBodyParserConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import({ManagementApiSecurityConfiguration.class, GlobalExceptionHandler.class, RequestIdFilter.class})
    static class WebConfiguration {
    }

    @Autowired
    private RestTestClient client;

    @Test
    @DisplayName("multipart 헤더는 본문 파서를 거치지 않고 일반 경로 처리로 응답한다")
    void ignoresMultipartHeaderWithoutBoundary() {
        client.get().uri("/l/unknown")
                .header(HttpHeaders.CONTENT_TYPE, "multipart/form-data")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("PUT 폼 본문은 파싱하지 않아 잘못된 인코딩도 서버 오류가 되지 않는다")
    void ignoresFormBodyOnPut() {
        client.put().uri("/l/unknown")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body("broken=%ZZ")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
    }
}
