package com.personal.batongo.adapter.out.external.link;

import com.personal.batongo.application.link.port.out.TargetUrlPort;
import com.personal.batongo.domain.link.HttpOrigin;
import com.personal.batongo.domain.link.TrustedTarget;
import java.net.URI;
import org.springframework.stereotype.Component;

@Component
public class ConfiguredTargetUrlAdapter implements TargetUrlPort {

    private final HttpOrigin batonOrigin;
    private final HttpOrigin roundOrigin;

    public ConfiguredTargetUrlAdapter(TrustedTargetProperties properties) {
        this.batonOrigin = properties.batonOrigin();
        this.roundOrigin = properties.roundOrigin();
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
