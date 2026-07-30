package com.personal.batongo.application.link.port.out;

public interface LinkCodePort {

    IssuedLinkCode issue(String idempotencyKey);

    String hash(String rawCode);

    String hashIdempotencyKey(String idempotencyKey);
}
