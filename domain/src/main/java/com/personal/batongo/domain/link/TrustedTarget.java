package com.personal.batongo.domain.link;

public final class TrustedTarget {

    private final TargetSystem targetSystem;
    private final LinkPurpose purpose;
    private final String targetPath;

    TrustedTarget(
            TargetSystem targetSystem,
            LinkPurpose purpose,
            String targetPath
    ) {
        this.targetSystem = targetSystem;
        this.purpose = purpose;
        this.targetPath = targetPath;
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
