package com.personal.batongo.adapter.out.external.link;

import com.personal.batongo.application.link.port.out.TargetUrlPort;
import com.personal.batongo.domain.link.HttpOrigin;
import com.personal.batongo.domain.link.TrustedTarget;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("baton-go.targets")
public final class TrustedTargetProperties implements TargetUrlPort {

    private final HttpOrigin batonOrigin;
    private final HttpOrigin roundOrigin;

    public TrustedTargetProperties(URI batonBaseUrl, URI roundBaseUrl) {
        this.batonOrigin = HttpOrigin.require(batonBaseUrl, "BATON base URL");
        this.roundOrigin = HttpOrigin.require(roundBaseUrl, "ROUND base URL");
        boolean localDevelopment = batonOrigin.isLoopback() && roundOrigin.isLoopback();
        // 같은 출처는 스킴·호스트가 같으므로 루프백 혼합과 HTTP 운영 출처도 함께 거부된다.
        if (!localDevelopment && !(batonOrigin.isHttps() && batonOrigin.sameOrigin(roundOrigin))) {
            throw new IllegalArgumentException("운영 BATON·ROUND 기본 URL은 같은 HTTPS 출처여야 합니다");
        }
    }

    public HttpOrigin batonOrigin() {
        return batonOrigin;
    }

    @Override
    public URI resolve(TrustedTarget target) {
        HttpOrigin origin = switch (target.targetSystem()) {
            case BATON -> batonOrigin;
            case ROUND -> roundOrigin;
        };
        return origin.resolve(target.targetPath());
    }
}
