package com.personal.batongo.application.link.port.out;

public interface LinkCodePort {

    IssuedLinkCode issue();

    String hash(String rawCode);
}
