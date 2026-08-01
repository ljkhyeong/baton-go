package com.personal.batongo.application.link.port.out;

import com.personal.batongo.application.link.LinkCodeDerivationIdentity;

public interface LinkCodeKeyGuardPort {

    void verifyOrBind(LinkCodeDerivationIdentity identity);

    void verifyBound(LinkCodeDerivationIdentity identity);
}
