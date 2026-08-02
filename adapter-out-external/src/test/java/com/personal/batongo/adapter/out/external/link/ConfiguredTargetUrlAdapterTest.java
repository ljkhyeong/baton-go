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
        String batonTarget = "/teams/8e448211-66ae-44ab-9888-c4960648c22b"
                + "/seasons/713d9cb7-2842-4f9f-b3cc-e31d98c6238a";

        assertThat(adapter.resolve(TargetSystem.BATON, batonTarget))
                .isEqualTo(URI.create("https://baton.example" + batonTarget));
        assertThat(adapter.resolve(TargetSystem.ROUND, "/room/abcd-efgh-jkmp"))
                .isEqualTo(URI.create("https://round.example/room/abcd-efgh-jkmp"));
    }
}
