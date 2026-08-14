package com.personal.batongo.domain.link;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SmartLinkPolicyTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-29T10:00:00Z");
    private static final String CODE_HASH = "a".repeat(64);
    private static final String BATON_PATH =
            "/teams/8e448211-66ae-44ab-9888-c4960648c22b"
                    + "/seasons/713d9cb7-2842-4f9f-b3cc-e31d98c6238a";

    @Test
    @DisplayName("활성 시작 시각과 정확히 같으면 링크를 해석할 수 있다")
    void resolvesAtExactActivationBoundary() {
        LinkAvailabilityPolicy.requireResolvableAt(
                null,
                CREATED_AT.plusSeconds(60),
                CREATED_AT.plusSeconds(120),
                CREATED_AT.plusSeconds(60)
        );
    }

    @Test
    @DisplayName("활성 시작 전에는 링크를 해석할 수 없다")
    void rejectsBeforeActivation() {
        Instant notBefore = CREATED_AT.plusSeconds(60);

        assertThatThrownBy(() -> LinkAvailabilityPolicy.requireResolvableAt(
                null,
                notBefore,
                null,
                notBefore.minusNanos(1)
        ))
                .isInstanceOf(LinkUnavailableException.class)
                .extracting(exception -> ((LinkUnavailableException) exception).reason())
                .isEqualTo(LinkUnavailableException.Reason.NOT_ACTIVE);
    }

    @Test
    @DisplayName("만료 시각과 정확히 같으면 링크는 만료된다")
    void expiresAtExactExpiryBoundary() {
        assertThatThrownBy(() -> LinkAvailabilityPolicy.requireResolvableAt(
                null,
                null,
                CREATED_AT.plusSeconds(120),
                CREATED_AT.plusSeconds(120)
        ))
                .isInstanceOf(LinkUnavailableException.class)
                .extracting(exception -> ((LinkUnavailableException) exception).reason())
                .isEqualTo(LinkUnavailableException.Reason.EXPIRED);
    }

    @Test
    @DisplayName("폐기된 링크는 해석할 수 없다")
    void rejectsRevokedLink() {
        Instant firstRevocation = CREATED_AT.plusSeconds(30);

        assertThatThrownBy(() -> LinkAvailabilityPolicy.requireResolvableAt(
                firstRevocation,
                null,
                null,
                firstRevocation.plusSeconds(60)
        ))
                .isInstanceOf(LinkUnavailableException.class)
                .extracting(exception -> ((LinkUnavailableException) exception).reason())
                .isEqualTo(LinkUnavailableException.Reason.REVOKED);
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
