package com.personal.batongo.application.link;

import java.util.Objects;
import java.util.regex.Pattern;

public record LinkCodeDerivationIdentity(
        String version,
        String hmacFingerprint
) {

    private static final Pattern VERSION = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern FINGERPRINT = Pattern.compile("[0-9a-f]{64}");

    public LinkCodeDerivationIdentity {
        Objects.requireNonNull(version, "링크 코드 파생 버전은 필수입니다");
        Objects.requireNonNull(hmacFingerprint, "HMAC fingerprint는 필수입니다");
        if (!VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("링크 코드 파생 버전 형식이 올바르지 않습니다");
        }
        if (!FINGERPRINT.matcher(hmacFingerprint).matches()) {
            throw new IllegalArgumentException("HMAC fingerprint 형식이 올바르지 않습니다");
        }
    }

    @Override
    public String toString() {
        return "LinkCodeDerivationIdentity[version=" + version
                + ", hmacFingerprint=<redacted>]";
    }
}
