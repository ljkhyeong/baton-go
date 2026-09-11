package com.personal.batongo.application.link;

import java.util.Map;

public record LinkCodeKeyRingIdentity(
        String activeKeyId,
        Map<String, LinkCodeDerivationIdentity> keys
) {
    public LinkCodeKeyRingIdentity {
        keys = Map.copyOf(keys);
        if (!keys.containsKey(activeKeyId)) {
            throw new IllegalArgumentException("현재 발급 키 ID가 키 목록에 없습니다");
        }
    }

    @Override
    public String toString() {
        return "LinkCodeKeyRingIdentity[activeKeyId=" + activeKeyId + ", keys=redacted]";
    }
}
