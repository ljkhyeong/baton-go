package com.personal.batongo.domain.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SmartLinkPolicyTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-29T10:00:00Z");
    private static final String CODE_HASH = "a".repeat(64);

    @Test
    @DisplayName("활성 시작 시각과 정확히 같으면 링크를 해석할 수 있다")
    void resolvesAtExactActivationBoundary() {
        SmartLink link = link(
                CREATED_AT.plusSeconds(60),
                CREATED_AT.plusSeconds(120)
        );

        link.requireResolvableAt(CREATED_AT.plusSeconds(60));
    }

    @Test
    @DisplayName("만료 시각과 정확히 같으면 링크는 만료된다")
    void expiresAtExactExpiryBoundary() {
        SmartLink link = link(null, CREATED_AT.plusSeconds(120));

        assertThatThrownBy(() -> link.requireResolvableAt(CREATED_AT.plusSeconds(120)))
                .isInstanceOf(LinkUnavailableException.class)
                .extracting(exception -> ((LinkUnavailableException) exception).reason())
                .isEqualTo(LinkUnavailableException.Reason.EXPIRED);
    }

    @Test
    @DisplayName("링크 폐기는 멱등이며 최초 폐기 시각을 유지한다")
    void revocationIsIdempotent() {
        SmartLink link = link(null, null);
        Instant firstRevocation = CREATED_AT.plusSeconds(30);

        link.revoke(firstRevocation);
        link.revoke(firstRevocation.plusSeconds(30));

        assertThat(link.getRevokedAt()).isEqualTo(firstRevocation);
        assertThatThrownBy(() -> link.requireResolvableAt(firstRevocation.plusSeconds(60)))
                .isInstanceOf(LinkUnavailableException.class)
                .extracting(exception -> ((LinkUnavailableException) exception).reason())
                .isEqualTo(LinkUnavailableException.Reason.REVOKED);
    }

    @Test
    @DisplayName("대상 경로에는 외부 URL과 상대 이동 구간을 사용할 수 없다")
    void rejectsUnsafeTargetPaths() {
        assertThatThrownBy(() -> createWithPath("https://evil.example/path"))
                .isInstanceOf(LinkValidationException.class);
        assertThatThrownBy(() -> createWithPath("/roles/../admin"))
                .isInstanceOf(LinkValidationException.class);
        assertThatThrownBy(() -> createWithPath("//evil.example/path"))
                .isInstanceOf(LinkValidationException.class);
        assertThatThrownBy(() -> createWithPath("/roles/1?token=secret"))
                .isInstanceOf(LinkValidationException.class);
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
        return SmartLink.create(
                UUID.randomUUID(),
                CODE_HASH,
                TargetSystem.BATON,
                "/teams/00000000-0000-0000-0000-000000000000",
                LinkPurpose.NAVIGATION,
                notBefore,
                expiresAt,
                CREATED_AT
        );
    }

    private SmartLink createWithPath(String path) {
        return SmartLink.create(
                UUID.randomUUID(),
                CODE_HASH,
                TargetSystem.BATON,
                path,
                LinkPurpose.NAVIGATION,
                null,
                null,
                CREATED_AT
        );
    }
}
