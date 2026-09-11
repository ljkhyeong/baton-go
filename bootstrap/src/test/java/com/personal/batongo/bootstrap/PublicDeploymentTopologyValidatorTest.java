package com.personal.batongo.bootstrap;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.adapter.in.web.PublicLinkProperties;
import com.personal.batongo.adapter.out.external.link.TrustedTargetProperties;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PublicDeploymentTopologyValidatorTest {

    @Test
    @DisplayName("로컬 공개 출처와 루프백 대상 조합은 개발 환경에서 허용한다")
    void acceptsLoopbackDevelopmentTopology() {
        assertThatCode(() -> new PublicDeploymentTopologyValidator(
                publicProperties("http://localhost:8080"),
                targetProperties("http://localhost:5173", "http://127.0.0.1:5174")
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("운영 공개 출처가 루프백 대상으로 이동하는 설정은 거부한다")
    void rejectsRemotePublicOriginWithLoopbackTargets() {
        assertThatThrownBy(() -> new PublicDeploymentTopologyValidator(
                publicProperties("https://go.example"),
                targetProperties("http://localhost:5173", "http://127.0.0.1:5174")
        ))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private PublicLinkProperties publicProperties(String value) {
        return new PublicLinkProperties(URI.create(value));
    }

    private TrustedTargetProperties targetProperties(String baton, String round) {
        return new TrustedTargetProperties(URI.create(baton), URI.create(round));
    }
}
