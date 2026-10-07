package com.personal.batongo.adapter.out.external.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.application.link.LinkCodeHash;
import com.personal.batongo.application.link.error.LinkCodeReplayMismatchException;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecureLinkCodeAdapterTest {

    private static final String SECRET = "test-only-link-code-secret-that-is-long-enough";
    private static final String IDEMPOTENCY_KEY = "8e448211-66ae-44ab-9888-c4960648c22b";

    private final SecureLinkCodeAdapter adapter =
            new SecureLinkCodeAdapter(new LinkCodeProperties("default", Map.of("default", SECRET)));

    @Test
    @DisplayName("현재 발급 키를 바꿔도 기존 키로 만든 코드는 유지한다")
    void replaysWithStoredKeyVersionAfterRotation() {
        var rotated = new SecureLinkCodeAdapter(new LinkCodeProperties(
                "k202609", Map.of("default", SECRET, "k202609", "new-test-key-that-is-at-least-thirty-two-characters")
        ));
        assertThat(rotated.issue(IDEMPOTENCY_KEY, "default")).isEqualTo(adapter.issue(IDEMPOTENCY_KEY, "default"));
        assertThat(rotated.issue(IDEMPOTENCY_KEY, "k202609")).isNotEqualTo(adapter.issue(IDEMPOTENCY_KEY, "default"));
        assertThatThrownBy(() -> rotated.issue(IDEMPOTENCY_KEY, "missing"))
                .isInstanceOf(LinkCodeReplayMismatchException.class);
    }

    @Test
    @DisplayName("HMAC SHA-256 v1 파생 규약은 고정 벡터와 일치한다")
    void matchesVersionOneFixedVector() {
        String rawCode = adapter.issue(IDEMPOTENCY_KEY, "default");

        assertThat(rawCode).isEqualTo("WgRX_ulMUrIGxM0IYBOpqA");
        assertThat(LinkCodeHash.of(rawCode))
                .isEqualTo("cc1d2daca7a315a27cbccb3eac92571648228f5975376bca32da45b2f33af247");
    }

    @Test
    @DisplayName("HMAC 키 정보는 파생 버전과 전용 구분 문자열로 만든 고정 지문을 사용한다")
    void createsStableDerivationIdentity() {
        var identity = adapter.keyRingIdentity().keys().get("default");

        assertThat(identity.version()).isEqualTo("hmac-sha256-link-code-v1");
        assertThat(identity.hmacFingerprint())
                .isEqualTo("11dd631d8939f29fdaea9662caead21123dc1fa154068d8be607f3add28e4daa");
        assertThat(adapter.keyRingIdentity().toString()).doesNotContain(identity.hmacFingerprint());
    }

    @Test
    @DisplayName("시각적으로 같은 Unicode 비밀도 정규화하지 않고 서로 다른 키로 취급한다")
    void doesNotNormalizeUnicodeSecret() {
        String composed = "é".repeat(32);
        String decomposed = "e\u0301".repeat(32);

        var composedAdapter = new SecureLinkCodeAdapter(
                new LinkCodeProperties("default", Map.of("default", composed)));
        var decomposedAdapter = new SecureLinkCodeAdapter(
                new LinkCodeProperties("default", Map.of("default", decomposed)));

        assertThat(composedAdapter.keyRingIdentity().keys())
                .isNotEqualTo(decomposedAdapter.keyRingIdentity().keys());
    }
}
