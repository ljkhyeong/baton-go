package com.personal.batongo.application.link;

import java.util.Map;

public record LinkCodeKeyRingIdentity(
        String activeKeyId,
        Map<String, LinkCodeDerivationIdentity> keys
) {
    public LinkCodeKeyRingIdentity {
        keys = Map.copyOf(keys);
    }

    public LinkCodeDerivationIdentity activeIdentity() {
        return keys.get(activeKeyId);
    }

    @Override
    public String toString() {
        return "LinkCodeKeyRingIdentity[activeKeyId=" + activeKeyId + ", keys=redacted]";
    }
}
