package com.personal.batongo.adapter.out.external.link;

import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("baton-go.link-code")
public record LinkCodeProperties(
        String secret
) {

    private static final Set<String> PUBLISHED_CREDENTIALS = Set.of(
            "replace-with-at-least-32-random-characters",
            "replace-with-a-separate-at-least-32-character-secret"
    );

    public LinkCodeProperties {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("링크 코드 파생 키는 32자 이상이어야 합니다");
        }
        requireSafe(secret);
    }

    private static void requireSafe(String secret) {
        if (PUBLISHED_CREDENTIALS.contains(secret)) {
            throw new IllegalArgumentException("공개 예시 credential은 사용할 수 없습니다");
        }
    }

    @Override
    public String toString() {
        return "LinkCodeProperties[secret=redacted]";
    }
}
