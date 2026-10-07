package com.personal.batongo.application.link;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class LinkCreationFingerprint {

    private LinkCreationFingerprint() {
    }

    static String of(String targetSystem, String purpose, String targetPath,
                     Instant notBefore, Instant expiresAt) {
        return sha256(Stream.of("v1", targetSystem, purpose, targetPath,
                        notBefore == null ? null : notBefore.toString(),
                        expiresAt == null ? null : expiresAt.toString())
                .map(value -> value == null ? "-1:" : value.length() + ":" + value)
                .collect(Collectors.joining()));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다", exception);
        }
    }
}
