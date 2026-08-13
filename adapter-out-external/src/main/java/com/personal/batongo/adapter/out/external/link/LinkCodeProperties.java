package com.personal.batongo.adapter.out.external.link;

import com.personal.batongo.application.link.PublishedCredentialPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("baton-go.link-code")
public record LinkCodeProperties(
        String secret
) {

    public LinkCodeProperties {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("링크 코드 파생 키는 32자 이상이어야 합니다");
        }
        PublishedCredentialPolicy.requireSafe(secret);
    }

    @Override
    public String toString() {
        return "LinkCodeProperties[secret=redacted]";
    }
}
