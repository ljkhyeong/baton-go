package com.personal.batongo.adapter.out.external.link;

import static org.assertj.core.api.Assertions.assertThat;

import com.personal.batongo.domain.link.TargetSystem;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConfiguredTargetUrlAdapterTest {

    private final ConfiguredTargetUrlAdapter adapter = new ConfiguredTargetUrlAdapter(
            new TrustedTargetProperties(
                    URI.create("https://baton.example"),
                    URI.create("https://round.example")
            )
    );

    @Test
    @DisplayName("대상 시스템마다 설정된 신뢰 origin 안에서 경로를 해석한다")
    void resolvesWithinConfiguredOrigins() {
        assertThat(adapter.resolve(TargetSystem.BATON, "/teams/team-1"))
                .isEqualTo(URI.create("https://baton.example/teams/team-1"));
        assertThat(adapter.resolve(TargetSystem.ROUND, "/room/abcd-efgh-jkmp"))
                .isEqualTo(URI.create("https://round.example/room/abcd-efgh-jkmp"));
    }
}
