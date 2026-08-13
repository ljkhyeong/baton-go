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
    @DisplayName("로컬 공개 origin과 loopback 신뢰 target 조합은 개발 환경으로 허용한다")
    void acceptsLoopbackDevelopmentTopology() {
        assertThatCode(() -> new PublicDeploymentTopologyValidator(
                publicProperties("http://localhost:8080"),
                targetProperties("http://localhost:5173", "http://127.0.0.1:5174")
        )).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("비로컬 공개 origin이 loopback 신뢰 target으로 이동하는 설정은 거부한다")
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
