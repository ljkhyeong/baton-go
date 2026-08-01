package com.personal.batongo.application.link.port.out;

import com.personal.batongo.application.link.LinkCodeDerivationIdentity;

public interface LinkCodePort {

    LinkCodeDerivationIdentity derivationIdentity();

    IssuedLinkCode issue(String idempotencyKey);

    String hash(String rawCode);

    String hashIdempotencyKey(String idempotencyKey);
}
