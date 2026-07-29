package com.personal.batongo.adapter.out.external.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.application.link.error.InvalidLinkCodeException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SecureLinkCodeAdapterTest {

    private final SecureLinkCodeAdapter adapter = new SecureLinkCodeAdapter();

    @Test
    @DisplayName("발급 코드는 128비트 URL 안전 문자열이며 원문과 해시가 다르다")
    void issuesUrlSafeCode() {
        var issued = adapter.issue();

        assertThat(issued.rawCode()).matches("^[A-Za-z0-9_-]{22}$");
        assertThat(issued.codeHash()).matches("^[0-9a-f]{64}$");
        assertThat(issued.codeHash()).isNotEqualTo(issued.rawCode());
        assertThat(adapter.hash(issued.rawCode())).isEqualTo(issued.codeHash());
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
