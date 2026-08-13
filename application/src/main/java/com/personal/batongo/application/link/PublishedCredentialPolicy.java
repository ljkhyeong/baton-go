package com.personal.batongo.application.link;

import java.util.Set;

/** 이미 공개된 예시값이 실제 credential로 재사용되는 것을 차단합니다. */
public final class PublishedCredentialPolicy {

    private static final Set<String> PUBLISHED_CREDENTIALS = Set.of(
            "replace-with-at-least-32-random-characters",
            "replace-with-a-separate-at-least-32-character-secret"
    );

    private PublishedCredentialPolicy() {
    }

    public static void requireSafe(String credential) {
        if (PUBLISHED_CREDENTIALS.contains(credential)) {
            throw new IllegalArgumentException("공개 예시 credential은 사용할 수 없습니다");
        }
    }
}
