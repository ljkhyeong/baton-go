package com.personal.batongo.adapter.out.external.link;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("baton-go.link-code")
public record LinkCodeProperties(
        String secret,
        @DefaultValue("default") String activeKeyId,
        @DefaultValue Map<String, String> keys
) {

    private static final Pattern KEY_ID = Pattern.compile("[a-z][a-z0-9]{0,31}");

    public LinkCodeProperties {
        Map<String, String> configured = new LinkedHashMap<>(keys);
        if (secret != null && !secret.isEmpty()) {
            if (configured.putIfAbsent("default", secret) != null) {
                throw new IllegalArgumentException("기본 키는 secret 또는 keys.default 중 한 곳에서 설정해야 합니다");
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
