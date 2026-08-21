package com.personal.batongo.adapter.out.external.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.application.link.error.LinkNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecureLinkCodeAdapterTest {

    private static final String SECRET = "test-only-link-code-secret-that-is-long-enough";
    private static final String IDEMPOTENCY_KEY = "8e448211-66ae-44ab-9888-c4960648c22b";

    private final SecureLinkCodeAdapter adapter =
            new SecureLinkCodeAdapter(new LinkCodeProperties(SECRET));

    @Test
    @DisplayName("HMAC SHA-256 v1 파생 규약은 고정 벡터와 일치한다")
    void matchesVersionOneFixedVector() {
        var issued = adapter.issue(IDEMPOTENCY_KEY);

        assertThat(issued.rawCode()).isEqualTo("WgRX_ulMUrIGxM0IYBOpqA");
        assertThat(issued.codeHash())
                .isEqualTo("cc1d2daca7a315a27cbccb3eac92571648228f5975376bca32da45b2f33af247");
        assertThat(adapter.hash(issued.rawCode())).isEqualTo(issued.codeHash());
    }

    @Test
    @DisplayName("HMAC 키 identity는 파생 버전과 별도 context의 고정 fingerprint를 사용한다")
    void createsStableDerivationIdentity() {
        var identity = adapter.derivationIdentity();

        assertThat(identity.version()).isEqualTo("hmac-sha256-link-code-v1");
        assertThat(identity.hmacFingerprint())
                .isEqualTo("11dd631d8939f29fdaea9662caead21123dc1fa154068d8be607f3add28e4daa");
        assertThat(identity.toString()).doesNotContain(identity.hmacFingerprint());
    }

    @Test
    @DisplayName("시각적으로 같은 Unicode 비밀도 정규화하지 않고 서로 다른 키로 취급한다")
    void doesNotNormalizeUnicodeSecret() {
        String composed = "é".repeat(32);
        String decomposed = "e\u0301".repeat(32);

        var composedAdapter = new SecureLinkCodeAdapter(new LinkCodeProperties(composed));
        var decomposedAdapter = new SecureLinkCodeAdapter(new LinkCodeProperties(decomposed));

        assertThat(composedAdapter.derivationIdentity())
                .isNotEqualTo(decomposedAdapter.derivationIdentity());
    }

    @Test
    @DisplayName("정확한 형식이 아닌 공개 코드는 해시하지 않는다")
    void rejectsMalformedCode() {
        assertThatThrownBy(() -> adapter.hash("short"))
                .isInstanceOf(LinkNotFoundException.class);
        assertThatThrownBy(() -> adapter.hash("a".repeat(21) + "/"))
                .isInstanceOf(LinkNotFoundException.class);
    }
}
