package com.personal.batongo.adapter.in.web;

import com.personal.batongo.application.link.PublicLinkOrigin;
import com.personal.batongo.application.link.port.out.PublicLinkOriginPort;
import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("baton-go")
public record PublicLinkProperties(
        URI publicBaseUrl
) implements PublicLinkOriginPort {

    public PublicLinkProperties {
        publicBaseUrl = new PublicLinkOrigin(publicBaseUrl).value();
    }

    @Override
    public PublicLinkOrigin current() {
        return new PublicLinkOrigin(publicBaseUrl);
    }
}
