package com.personal.batongo.adapter.in.web;

import com.personal.batongo.application.link.PublicLinkOrigin;
import com.personal.batongo.application.link.port.out.PublicLinkOriginPort;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("baton-go")
public final class PublicLinkProperties implements PublicLinkOriginPort {

    private final PublicLinkOrigin publicLinkOrigin;

    public PublicLinkProperties(URI publicBaseUrl) {
        this.publicLinkOrigin = new PublicLinkOrigin(publicBaseUrl);
    }

    @Override
    public PublicLinkOrigin current() {
        return publicLinkOrigin;
    }
}
