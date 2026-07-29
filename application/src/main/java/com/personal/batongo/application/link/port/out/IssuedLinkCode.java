package com.personal.batongo.application.link.port.out;

public record IssuedLinkCode(
        String rawCode,
        String codeHash
) {
}
