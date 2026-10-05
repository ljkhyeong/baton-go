package com.personal.batongo.adapter.out.external.link;

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
            throw new IllegalArgumentException("현재 발급 키 ID와 비밀값을 설정해야 합니다");
        }
        configured.forEach((keyId, value) -> {
            if (!KEY_ID.matcher(keyId).matches()) {
                throw new IllegalArgumentException(
                        "키 ID는 영문 소문자로 시작하며 소문자와 숫자만 사용해 32자 이내로 입력해야 합니다"
                );
            }
            if (value == null || value.length() < 32) {
                throw new IllegalArgumentException("링크 코드 파생 키는 32자 이상이어야 합니다");
            }
        });
        keys = Map.copyOf(configured);
    }

    @Override
    public String toString() {
        return "LinkCodeProperties[activeKeyId=" + activeKeyId + ", secrets=redacted]";
    }
}
