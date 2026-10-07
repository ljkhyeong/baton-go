package com.personal.batongo.application.link.port.out;

import com.personal.batongo.application.link.LinkCodeKeyRingIdentity;

public interface LinkCodePort {

    LinkCodeKeyRingIdentity keyRingIdentity();

    String issue(String idempotencyKey, String keyId);
}
