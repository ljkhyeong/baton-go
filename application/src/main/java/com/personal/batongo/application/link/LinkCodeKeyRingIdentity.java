package com.personal.batongo.application.link;

import java.util.Map;

public record LinkCodeKeyRingIdentity(
        String activeKeyId,
        Map<String, LinkCodeDerivationIdentity> keys
) {
    public LinkCodeKeyRingIdentity {
        keys = Map.copyOf(keys);
        if (!keys.containsKey(activeKeyId)) {
            throw new IllegalArgumentException("현재 발급 키가 키 묶음에 있어야 합니다");
        }
    }

    @Override
    public String toString() {
        return "LinkCodeKeyRingIdentity[activeKeyId=" + activeKeyId + ", keys=redacted]";
    }
}
