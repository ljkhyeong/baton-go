package com.personal.batongo.domain.link;

import java.util.regex.Pattern;

public final class TrustedTargetPolicy {

    private static final String CANONICAL_UUID =
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
    private static final String ROUND_ROOM_ID =
            "[abcdefghjkmnpqrstuvwxyz23456789]{4}"
                    + "-[abcdefghjkmnpqrstuvwxyz23456789]{4}"
                    + "-[abcdefghjkmnpqrstuvwxyz23456789]{4}";

    private static final Pattern BATON_NAVIGATION_PATH = Pattern.compile(
            "\\A/teams/" + CANONICAL_UUID + "/seasons/" + CANONICAL_UUID + "\\z"
    );
    private static final Pattern ROUND_MEETING_ENTRY_PATH = Pattern.compile(
            "\\A/room/" + ROUND_ROOM_ID + "\\z"
    );

    private TrustedTargetPolicy() {
    }

    public static TrustedTarget requireAllowed(
            TargetSystem targetSystem,
            LinkPurpose purpose,
            String targetPath
    ) {
        if (targetSystem == null) {
            throw new LinkValidationException("대상 시스템은 필수입니다");
        }
        if (purpose == null) {
            throw new LinkValidationException("링크 목적은 필수입니다");
        }

        String validatedPath = TargetPath.requireSafe(targetPath);
        boolean allowed = switch (targetSystem) {
            case BATON -> purpose == LinkPurpose.NAVIGATION
                    && BATON_NAVIGATION_PATH.matcher(validatedPath).matches();
            case ROUND -> purpose == LinkPurpose.MEETING_ENTRY
                    && ROUND_MEETING_ENTRY_PATH.matcher(validatedPath).matches();
        };
        if (!allowed) {
            throw new LinkValidationException(
                    "대상 시스템, 목적과 경로가 v1 신뢰 대상 계약에 맞지 않습니다"
            );
        }
        return new TrustedTarget(targetSystem, purpose, validatedPath);
    }

    public static TrustedTarget requireAllowed(
            String targetSystem,
            String purpose,
            String targetPath
    ) {
        try {
            return requireAllowed(
                    TargetSystem.valueOf(targetSystem),
                    LinkPurpose.valueOf(purpose),
                    targetPath
            );
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new LinkValidationException(
                    "저장된 대상 시스템 또는 목적이 v1 신뢰 대상 계약에 맞지 않습니다"
            );
        }
    }
}
