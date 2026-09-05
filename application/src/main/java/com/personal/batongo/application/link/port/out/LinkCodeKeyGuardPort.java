package com.personal.batongo.application.link.port.out;

import com.personal.batongo.application.link.LinkCodeKeyRingIdentity;

public interface LinkCodeKeyGuardPort {

    void verifyOrBind(LinkCodeKeyRingIdentity identity);

    void verifyBound(LinkCodeKeyRingIdentity identity);
}
