package com.personal.batongo.bootstrap.retention;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LinkRetentionPropertiesTest {
    @Test
    @DisplayName("자동 정리는 명시한 양수 보존 기간과 제한된 처리량을 요구한다")
    void requiresRetentionAndBoundedBatch() {
        assertThatThrownBy(() -> new LinkRetentionProperties(true, null, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LinkRetentionProperties(true, Duration.ZERO, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LinkRetentionProperties(true, Duration.ofDays(30), 501))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
