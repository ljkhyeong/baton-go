package com.personal.batongo.application.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreateLinkCommand;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreatedLinkResult;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.LinkResult;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.ResolvedLinkResult;
import com.personal.batongo.application.link.port.out.IssuedLinkCode;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SensitiveValueToStringTest {

    private static final String IDEMPOTENCY_KEY =
            "8e448211-66ae-44ab-9888-c4960648c22b";
    private static final String TARGET_PATH =
            "/teams/8e448211-66ae-44ab-9888-c4960648c22b"
                    + "/seasons/713d9cb7-2842-4f9f-b3cc-e31d98c6238a";
    private static final String RAW_CODE = "abcdefghijklmnopqrstuv";
    private static final String CODE_HASH = "a".repeat(64);

    @Test
    @DisplayName("링크 생성과 해석 값의 문자열 표현은 키·코드·대상을 노출하지 않는다")
    void redactsSensitiveApplicationValuesFromStringRepresentations() {
        CreationIdempotencyKey idempotencyKey = new CreationIdempotencyKey(IDEMPOTENCY_KEY);
        LinkResult link = new LinkResult(
                UUID.fromString("4b6982dc-31aa-4f87-bcc5-a89d0bc101be"),
                TargetSystem.BATON,
                TARGET_PATH,
                LinkPurpose.NAVIGATION,
                null,
                null,
                null,
                Instant.parse("2026-08-08T00:00:00Z")
        );
        List<Object> values = List.of(
                idempotencyKey,
                new CreateLinkCommand(
                        idempotencyKey,
                        TargetSystem.BATON,
                        TARGET_PATH,
                        LinkPurpose.NAVIGATION,
                        null,
                        null
                ),
                new IssuedLinkCode(RAW_CODE, CODE_HASH),
                new CreatedLinkResult(link, RAW_CODE, false),
                link,
                new ResolvedLinkResult(link.id(), URI.create("https://baton.example" + TARGET_PATH))
        );

        assertThat(values.toString())
                .doesNotContain(IDEMPOTENCY_KEY)
                .doesNotContain(RAW_CODE)
                .doesNotContain(CODE_HASH)
                .doesNotContain(TARGET_PATH);
    }
}
