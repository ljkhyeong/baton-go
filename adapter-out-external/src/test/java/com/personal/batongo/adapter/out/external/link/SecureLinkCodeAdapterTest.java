package com.personal.batongo.adapter.out.external.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.application.link.error.InvalidLinkCodeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecureLinkCodeAdapterTest {

    private static final String SECRET = "test-only-link-code-secret-that-is-long-enough";
    private static final String IDEMPOTENCY_KEY = "8e448211-66ae-44ab-9888-c4960648c22b";

    private final SecureLinkCodeAdapter adapter =
            new SecureLinkCodeAdapter(new LinkCodeProperties(SECRET));

    @Test
    @DisplayName("발급 코드는 멱등성 키에서 파생된 128비트 URL 안전 문자열이다")
    void issuesUrlSafeCode() {
        var issued = adapter.issue(IDEMPOTENCY_KEY);

        assertThat(issued.rawCode()).matches("^[A-Za-z0-9_-]{22}$");
        assertThat(issued.codeHash()).matches("^[0-9a-f]{64}$");
        assertThat(issued.codeHash()).isNotEqualTo(issued.rawCode());
        assertThat(adapter.hash(issued.rawCode())).isEqualTo(issued.codeHash());
    }

    @Test
    @DisplayName("같은 비밀과 멱등성 키는 재시작 후에도 같은 공개 코드를 만든다")
    void derivesStableCode() {
        var restarted = new SecureLinkCodeAdapter(new LinkCodeProperties(SECRET));

        assertThat(restarted.issue(IDEMPOTENCY_KEY))
                .isEqualTo(adapter.issue(IDEMPOTENCY_KEY));
        assertThat(adapter.issue("7606bb52-2837-4359-bca4-d7f295b64fe4"))
                .isNotEqualTo(adapter.issue(IDEMPOTENCY_KEY));
        assertThat(adapter.hashIdempotencyKey(IDEMPOTENCY_KEY))
                .matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("HMAC SHA-256 v1 파생 규약은 고정 벡터와 일치한다")
    void matchesVersionOneFixedVector() {
        var issued = adapter.issue(IDEMPOTENCY_KEY);

        assertThat(issued.rawCode()).isEqualTo("WgRX_ulMUrIGxM0IYBOpqA");
        assertThat(issued.codeHash())
                .isEqualTo("cc1d2daca7a315a27cbccb3eac92571648228f5975376bca32da45b2f33af247");
    }

    @Test
    @DisplayName("정확한 형식이 아닌 공개 코드는 해시하지 않는다")
    void rejectsMalformedCode() {
        assertThatThrownBy(() -> adapter.hash("short"))
                .isInstanceOf(InvalidLinkCodeException.class);
        assertThatThrownBy(() -> adapter.hash("a".repeat(21) + "/"))
                .isInstanceOf(InvalidLinkCodeException.class);
    }
}
