package com.personal.batongo.adapter.out.persistence.link;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SmartLinkPersistenceAdapterTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final SmartLinkPersistenceAdapter adapter = new SmartLinkPersistenceAdapter(
            mock(SpringDataSmartLinkRepository.class),
            jdbcTemplate
    );

    @Test
    @DisplayName("저장 어댑터는 증가할 수 없는 version을 SQL 실행 전에 거부한다")
    void rejectsMaximumVersionBeforeExecutingSql() {
        assertThatThrownBy(() -> adapter.revokeStoredIfVersion(
                UUID.fromString("00000000-0000-4000-8000-000000000001"),
                Long.MAX_VALUE,
                Instant.parse("2026-08-03T01:02:03.123456Z")
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("expectedVersion은 증가 가능한 범위여야 합니다");

        verifyNoInteractions(jdbcTemplate);
    }
}
