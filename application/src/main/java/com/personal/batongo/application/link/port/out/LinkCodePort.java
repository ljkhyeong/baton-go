package com.personal.batongo.application.link.port.out;

import com.personal.batongo.application.link.LinkCodeKeyRingIdentity;

public interface LinkCodePort {

    LinkCodeKeyRingIdentity keyRingIdentity();

    IssuedLinkCode issue(String idempotencyKey, String keyId);

    String hash(String rawCode);
}
