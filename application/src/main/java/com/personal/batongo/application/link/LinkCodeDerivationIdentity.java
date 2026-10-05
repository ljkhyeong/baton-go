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

    /** DB에 저장된 버전·지문과 같은 키 정보인지 확인합니다. */
    public boolean matches(String storedVersion, String storedHmacFingerprint) {
        return version.equals(storedVersion) && hmacFingerprint.equals(storedHmacFingerprint);
    }

    @Override
    public String toString() {
        return "LinkCodeDerivationIdentity[version=" + version
                + ", hmacFingerprint=<redacted>]";
    }
}
