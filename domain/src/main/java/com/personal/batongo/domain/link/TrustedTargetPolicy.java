package com.personal.batongo.domain.link;

import java.util.Optional;
import java.util.regex.Pattern;

public final class TrustedTargetPolicy {

    private static final String CANONICAL_UUID =
            "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
    private static final String ROUND_ROOM_ID =
            "[abcdefghjkmnpqrstuvwxyz23456789]{4}"
                    + "-[abcdefghjkmnpqrstuvwxyz23456789]{4}"
                    + "-[abcdefghjkmnpqrstuvwxyz23456789]{4}";

    private static final Pattern BATON_NAVIGATION_PATH = Pattern.compile(
            "/teams/" + CANONICAL_UUID + "/seasons/" + CANONICAL_UUID
    );
    private static final Pattern ROUND_MEETING_ENTRY_PATH = Pattern.compile(
            "/room/" + ROUND_ROOM_ID
    );

    private TrustedTargetPolicy() {
    }

    public static TrustedTarget requireAllowed(
            TargetSystem targetSystem,
            LinkPurpose purpose,
            String targetPath
    ) {
        return findAllowed(
                targetSystem == null ? null : targetSystem.name(),
                purpose == null ? null : purpose.name(),
                targetPath
        ).orElseThrow(() -> new LinkValidationException("v1에서 허용하지 않는 대상 시스템·목적·경로 조합입니다"));
    }

    public static Optional<TrustedTarget> findAllowed(
            String targetSystem,
            String purpose,
            String targetPath
    ) {
        // 허용 조합은 정의된 열거형 이름만 포함하므로 확인 뒤 변환은 실패하지 않는다.
        return isAllowed(targetSystem, purpose, targetPath)
                ? Optional.of(new TrustedTarget(
                        TargetSystem.valueOf(targetSystem),
                        LinkPurpose.valueOf(purpose),
                        targetPath
                ))
                : Optional.empty();
    }

    public static boolean isAllowed(
            String targetSystem,
            String purpose,
            String targetPath
    ) {
        if (targetSystem == null || purpose == null || targetPath == null) {
            return false;
        }
        return switch (targetSystem) {
            case "BATON" -> "NAVIGATION".equals(purpose)
                    && BATON_NAVIGATION_PATH.matcher(targetPath).matches();
            case "ROUND" -> "MEETING_ENTRY".equals(purpose)
                    && ROUND_MEETING_ENTRY_PATH.matcher(targetPath).matches();
            default -> false;
        };
    }
}
