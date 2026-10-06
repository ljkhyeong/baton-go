package com.personal.batongo.application.link;

public record LinkCodeDerivationIdentity(
        String version,
        String hmacFingerprint
) {

    @Override
    public String toString() {
        return "LinkCodeDerivationIdentity[version=" + version
                + ", hmacFingerprint=<redacted>]";
    }
}
