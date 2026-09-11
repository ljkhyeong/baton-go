package com.personal.batongo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.batongo.adapter.in.web.link.CreateLinkRequest;
import com.personal.batongo.adapter.in.web.link.CreateLinkResponse;
import com.personal.batongo.adapter.in.web.link.LinkResponse;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.LinkAvailabilityPolicy.Status;
import com.personal.batongo.domain.link.TargetSystem;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SensitiveWebValueToStringTest {

    private static final String TARGET_PATH =
            "/teams/8e448211-66ae-44ab-9888-c4960648c22b"
                    + "/seasons/713d9cb7-2842-4f9f-b3cc-e31d98c6238a";

    @Test
    @DisplayName("생성 응답의 문자열 표현은 단축 URL과 대상 경로를 노출하지 않는다")
    void redactsShortUrlAndTargetPathFromStringRepresentation() {
        URI shortUrl = URI.create("https://go.example/l/abcdefghijklmnopqrstuv");
        CreateLinkResponse response = new CreateLinkResponse(
                UUID.fromString("ad2facfc-468d-4527-81a3-38e7bbea7cba"),
                shortUrl,
                TargetSystem.BATON,
                TARGET_PATH,
                LinkPurpose.NAVIGATION,
                null,
                null,
                null,
                Instant.parse("2026-08-08T00:00:00Z")
        );

        assertThat(response.toString())
                .doesNotContain(shortUrl.toString())
                .doesNotContain(TARGET_PATH);
    }

    @Test
    @DisplayName("생성 요청과 관리 응답의 문자열 표현은 대상 경로를 노출하지 않는다")
    void redactsTargetPathFromRequestAndManagementResponse() {
        CreateLinkRequest request = new CreateLinkRequest(
                TargetSystem.BATON,
                TARGET_PATH,
                LinkPurpose.NAVIGATION,
                null,
                null
        );
        LinkResponse response = new LinkResponse(
                UUID.fromString("ad2facfc-468d-4527-81a3-38e7bbea7cba"),
                TargetSystem.BATON,
                TARGET_PATH,
                LinkPurpose.NAVIGATION,
                null,
                null,
                null,
                Instant.parse("2026-08-08T00:00:00Z"),
                Status.ACTIVE,
                Instant.parse("2026-08-08T00:00:00Z")
        );

        assertThat(request.toString()).doesNotContain(TARGET_PATH);
        assertThat(response.toString()).doesNotContain(TARGET_PATH);
    }
}
