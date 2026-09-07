package com.personal.batongo.adapter.out.external.link;

import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("baton-go.link-code")
public record LinkCodeProperties(
        String secret,
        String activeKeyId,
        Map<String, String> keys
) {

    private static final Pattern KEY_ID = Pattern.compile("[a-z][a-z0-9]{0,31}");
    private static final Set<String> PUBLISHED_CREDENTIALS = Set.of(
            "replace-with-at-least-32-random-characters",
            "replace-with-a-separate-at-least-32-character-secret"
    );

    public LinkCodeProperties(String secret) {
        this(secret, "legacy", Map.of());
    }

    @ConstructorBinding
    public LinkCodeProperties {
        activeKeyId = activeKeyId == null ? "legacy" : activeKeyId;
        Map<String, String> configured = new LinkedHashMap<>(keys == null ? Map.of() : keys);
        if (secret != null && !secret.isEmpty()) {
            if (configured.putIfAbsent("legacy", secret) != null) {
                throw new IllegalArgumentException("기존 키는 secret 또는 keys.legacy 중 한 곳에서 설정해야 합니다");
            }
        }
        if (!configured.containsKey(activeKeyId)) {
            throw new IllegalArgumentException("현재 발급 키와 해당 비밀값을 설정해야 합니다");
        }
        configured.forEach((keyId, value) -> {
            if (!KEY_ID.matcher(keyId).matches()) {
                throw new IllegalArgumentException("키 식별자는 영문 소문자로 시작하는 32자 이내의 소문자와 숫자여야 합니다");
            }
            requireSafe(value);
        });
        keys = Map.copyOf(configured);
    }

    private static void requireSafe(String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("링크 코드 파생 키는 32자 이상이어야 합니다");
        }
        if (PUBLISHED_CREDENTIALS.contains(secret)) {
            throw new IllegalArgumentException("예제에 공개된 비밀값은 사용할 수 없습니다");
        }
    }

    @Override
    public String toString() {
        return "LinkCodeProperties[activeKeyId=" + activeKeyId + ", secrets=redacted]";
    }
}
