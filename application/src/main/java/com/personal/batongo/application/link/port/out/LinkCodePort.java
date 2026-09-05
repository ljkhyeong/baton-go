package com.personal.batongo.application.link.port.out;

import com.personal.batongo.application.link.LinkCodeDerivationIdentity;
import com.personal.batongo.application.link.LinkCodeKeyRingIdentity;

public interface LinkCodePort {

    LinkCodeDerivationIdentity derivationIdentity();

    LinkCodeKeyRingIdentity keyRingIdentity();

    IssuedLinkCode issue(String idempotencyKey);

    IssuedLinkCode issue(String idempotencyKey, String keyId);

    String hash(String rawCode);

    String hashIdempotencyKey(String idempotencyKey);
}
