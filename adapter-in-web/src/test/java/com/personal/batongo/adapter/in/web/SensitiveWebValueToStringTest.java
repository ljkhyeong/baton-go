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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SensitiveWebValueToStringTest {

    private static final String TARGET_PATH =
            "/teams/8e448211-66ae-44ab-9888-c4960648c22b"
                    + "/seasons/713d9cb7-2842-4f9f-b3cc-e31d98c6238a";

    @Test
    @DisplayName("생성 요청·응답과 관리 응답의 문자열 표현은 단축 URL과 대상 경로를 노출하지 않는다")
    void redactsShortUrlAndTargetPathFromStringRepresentation() {
        URI shortUrl = URI.create("https://go.example/l/abcdefghijklmnopqrstuv");
        UUID linkId = UUID.fromString("ad2facfc-468d-4527-81a3-38e7bbea7cba");
        Instant createdAt = Instant.parse("2026-08-08T00:00:00Z");

        assertThat(List.of(
                new CreateLinkRequest(TargetSystem.BATON, TARGET_PATH, LinkPurpose.NAVIGATION, null, null),
                new CreateLinkResponse(linkId, shortUrl, TargetSystem.BATON, TARGET_PATH, LinkPurpose.NAVIGATION,
                        null, null, null, createdAt),
                new LinkResponse(linkId, TargetSystem.BATON, TARGET_PATH, LinkPurpose.NAVIGATION,
                        null, null, null, createdAt, Status.ACTIVE, createdAt)
        )).allSatisfy(value -> assertThat(value.toString()).doesNotContain(shortUrl.toString(), TARGET_PATH));
    }
}
