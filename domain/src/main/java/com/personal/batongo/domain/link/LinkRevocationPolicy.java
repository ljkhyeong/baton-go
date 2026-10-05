package com.personal.batongo.domain.link;

import java.time.Instant;
import java.util.Objects;

public final class LinkRevocationPolicy {

    private LinkRevocationPolicy() {
    }

    /** 생성한 Pod보다 시계가 늦어도 폐기를 거부하지 않고 생성 시각을 폐기 시각으로 사용한다. */
    public static Instant firstRevocationAt(Instant createdAt, Instant now) {
        Objects.requireNonNull(createdAt, "생성 시각은 필수입니다");
        Objects.requireNonNull(now, "폐기 시각은 필수입니다");
        return now.isBefore(createdAt) ? createdAt : now;
    }
}
