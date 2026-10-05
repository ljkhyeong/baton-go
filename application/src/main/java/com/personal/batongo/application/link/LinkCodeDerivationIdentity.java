package com.personal.batongo.application.link;

import java.util.Objects;

public record LinkCodeDerivationIdentity(
        String version,
        String hmacFingerprint
) {

    public LinkCodeDerivationIdentity {
        Objects.requireNonNull(version, "링크 코드 파생 버전은 필수입니다");
        Objects.requireNonNull(hmacFingerprint, "HMAC 키 지문은 필수입니다");
    }

    @Override
    public String toString() {
        return "LinkCodeDerivationIdentity[version=" + version
                + ", hmacFingerprint=<redacted>]";
    }
}
