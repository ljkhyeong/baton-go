package com.personal.batongo.bootstrap.retention;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LinkRetentionPropertiesTest {
    @Test
    @DisplayName("자동 정리의 보존 기간은 양수이고 실행당 링크 수는 1~500개여야 한다")
    void requiresRetentionAndBoundedBatch() {
        assertThatThrownBy(() -> new LinkRetentionProperties(true, null, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LinkRetentionProperties(true, Duration.ZERO, 100))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LinkRetentionProperties(true, Duration.ofDays(30), 501))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
