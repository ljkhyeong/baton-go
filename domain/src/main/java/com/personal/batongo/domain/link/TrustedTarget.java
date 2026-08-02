package com.personal.batongo.domain.link;

import java.util.Objects;

public final class TrustedTarget {

    private final TargetSystem targetSystem;
    private final LinkPurpose purpose;
    private final String targetPath;

    TrustedTarget(
            TargetSystem targetSystem,
            LinkPurpose purpose,
            String targetPath
    ) {
        this.targetSystem = Objects.requireNonNull(targetSystem, "대상 시스템은 필수입니다");
        this.purpose = Objects.requireNonNull(purpose, "링크 목적은 필수입니다");
        this.targetPath = Objects.requireNonNull(targetPath, "대상 경로는 필수입니다");
    }

    public TargetSystem targetSystem() {
        return targetSystem;
    }

    public LinkPurpose purpose() {
        return purpose;
    }

    public String targetPath() {
        return targetPath;
    }
}
