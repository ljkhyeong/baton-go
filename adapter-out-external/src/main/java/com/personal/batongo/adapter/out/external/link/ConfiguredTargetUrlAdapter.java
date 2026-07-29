package com.personal.batongo.adapter.out.external.link;

import com.personal.batongo.application.link.port.out.TargetUrlPort;
import com.personal.batongo.domain.link.TargetSystem;
import java.net.URI;
import org.springframework.stereotype.Component;

@Component
public class ConfiguredTargetUrlAdapter implements TargetUrlPort {

    private final TrustedTargetProperties properties;

    public ConfiguredTargetUrlAdapter(TrustedTargetProperties properties) {
        this.properties = properties;
    }

    @Override
    public URI resolve(TargetSystem targetSystem, String targetPath) {
        URI baseUrl = switch (targetSystem) {
            case BATON -> properties.batonBaseUrl();
            case ROUND -> properties.roundBaseUrl();
        };
        URI destination = baseUrl.resolve(targetPath);
        if (!sameOrigin(baseUrl, destination)) {
            throw new IllegalStateException("신뢰 대상 origin 밖으로 링크를 해석할 수 없습니다");
        }
        return destination;
    }

    private boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && effectivePort(left) == effectivePort(right);
    }

    private int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
