package com.personal.batongo.application.link;

import com.personal.batongo.application.link.port.out.LinkCodeKeyGuardPort;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class LinkCodeKeyGuard {

    private final LinkCodePort linkCodePort;
    private final LinkCodeKeyGuardPort guardPort;

    public LinkCodeKeyGuard(
            LinkCodePort linkCodePort,
            LinkCodeKeyGuardPort guardPort
    ) {
        this.linkCodePort = linkCodePort;
        this.guardPort = guardPort;
    }

    @Transactional
    public void verifyOrBind() {
        guardPort.verifyOrBind(linkCodePort.derivationIdentity());
    }

    void verifyBound() {
        guardPort.verifyBound(linkCodePort.derivationIdentity());
    }
}
