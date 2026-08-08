package com.personal.batongo.adapter.out.external.link;

import com.personal.batongo.application.link.port.out.TargetUrlPort;
import com.personal.batongo.domain.link.HttpOrigin;
import com.personal.batongo.domain.link.TargetSystem;
import java.net.URI;
import org.springframework.stereotype.Component;

@Component
public class ConfiguredTargetUrlAdapter implements TargetUrlPort {

    private final HttpOrigin batonOrigin;
    private final HttpOrigin roundOrigin;

    public ConfiguredTargetUrlAdapter(TrustedTargetProperties properties) {
        this.batonOrigin = HttpOrigin.require(properties.batonBaseUrl(), "BATON base URL");
        this.roundOrigin = HttpOrigin.require(properties.roundBaseUrl(), "ROUND base URL");
    }

    @Override
    public URI resolve(TargetSystem targetSystem, String targetPath) {
        HttpOrigin origin = switch (targetSystem) {
            case BATON -> batonOrigin;
            case ROUND -> roundOrigin;
        };
        URI destination = origin.resolve(targetPath);
        if (!origin.sameOrigin(destination)) {
            throw new IllegalStateException("신뢰 대상 origin 밖으로 링크를 해석할 수 없습니다");
        }
        return destination;
    }
}
