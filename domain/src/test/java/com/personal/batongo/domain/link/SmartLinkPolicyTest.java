package com.personal.batongo.domain.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.domain.link.LinkAvailabilityPolicy.Status;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class SmartLinkPolicyTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-29T10:00:00Z");
    private static final String CODE_HASH = "a".repeat(64);
    private static final String BATON_PATH =
            "/teams/8e448211-66ae-44ab-9888-c4960648c22b"
                    + "/seasons/713d9cb7-2842-4f9f-b3cc-e31d98c6238a";

    @ParameterizedTest
    @MethodSource("availabilityCases")
    @DisplayName("상태 조회와 공개 링크 처리는 활성·만료 시각과 폐기 우선순위를 함께 적용한다")
    void evaluatesAvailabilityAndResolution(
            Instant revokedAt, Instant notBefore, Instant expiresAt, Instant now, Status expected
    ) {
        assertThat(LinkAvailabilityPolicy.evaluate(revokedAt, notBefore, expiresAt, now))
                .isEqualTo(expected);
        if (expected == Status.ACTIVE) {
            LinkAvailabilityPolicy.requireResolvableAt(revokedAt, notBefore, expiresAt, now);
        } else {
            assertThatThrownBy(() -> LinkAvailabilityPolicy.requireResolvableAt(
                    revokedAt, notBefore, expiresAt, now
            ))
                    .isInstanceOf(LinkUnavailableException.class)
                    .extracting(exception -> ((LinkUnavailableException) exception).reason().name())
                    .isEqualTo(expected.name());
        }
    }

    private static Stream<Arguments> availabilityCases() {
        Instant notBefore = CREATED_AT.plusSeconds(60);
        Instant expiresAt = CREATED_AT.plusSeconds(120);
        return Stream.of(
                Arguments.of(null, notBefore, expiresAt, notBefore.minusNanos(1), Status.NOT_ACTIVE),
                Arguments.of(null, notBefore, expiresAt, notBefore, Status.ACTIVE),
                Arguments.of(null, notBefore, expiresAt, notBefore.plusNanos(1), Status.ACTIVE),
                Arguments.of(null, notBefore, expiresAt, expiresAt.minusNanos(1), Status.ACTIVE),
                Arguments.of(null, notBefore, expiresAt, expiresAt, Status.EXPIRED),
                Arguments.of(null, notBefore, expiresAt, expiresAt.plusNanos(1), Status.EXPIRED),
                Arguments.of(null, null, null, CREATED_AT, Status.ACTIVE),
                Arguments.of(CREATED_AT, notBefore, expiresAt, CREATED_AT, Status.REVOKED),
                Arguments.of(CREATED_AT, notBefore, expiresAt, expiresAt, Status.REVOKED)
        );
    }

    @Test
    @DisplayName("만료 시각은 생성과 활성 시작 시각보다 뒤여야 한다")
    void rejectsInvalidTimeRange() {
        assertThatThrownBy(() -> link(null, CREATED_AT))
                .isInstanceOf(LinkValidationException.class);
        assertThatThrownBy(() -> link(
                CREATED_AT.plusSeconds(120),
                CREATED_AT.plusSeconds(60)
        )).isInstanceOf(LinkValidationException.class);
    }

    private SmartLink link(Instant notBefore, Instant expiresAt) {
        return new SmartLink(
                UUID.randomUUID(),
                CODE_HASH,
                TrustedTargetPolicy.requireAllowed(
                        TargetSystem.BATON,
                        LinkPurpose.NAVIGATION,
                        BATON_PATH
                ),
                notBefore,
                expiresAt,
                CREATED_AT
        );
    }
}
