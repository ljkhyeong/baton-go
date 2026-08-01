package com.personal.batongo.bootstrap;

import com.personal.batongo.application.link.LinkCodeKeyGuard;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class LinkCodeKeyStartupValidator implements ApplicationRunner {

    private final LinkCodeKeyGuard linkCodeKeyGuard;

    public LinkCodeKeyStartupValidator(LinkCodeKeyGuard linkCodeKeyGuard) {
        this.linkCodeKeyGuard = linkCodeKeyGuard;
    }

    @Override
    public void run(ApplicationArguments arguments) {
        linkCodeKeyGuard.verifyOrBind();
    }
}
