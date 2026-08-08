package com.personal.batongo.adapter.in.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("baton-go.management")
public record ManagementProperties(
        String token
) {

    public ManagementProperties {
        if (token == null || token.length() < 32) {
            throw new IllegalArgumentException("관리 credential은 32자 이상이어야 합니다");
        }
    }

    @Override
    public String toString() {
        return "ManagementProperties[token=redacted]";
    }
}
