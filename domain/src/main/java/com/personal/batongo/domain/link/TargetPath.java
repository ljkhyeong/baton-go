package com.personal.batongo.domain.link;

import java.util.regex.Pattern;

public final class TargetPath {

    private static final int MAX_LENGTH = 1024;
    private static final Pattern SAFE_PATH = Pattern.compile("^/[A-Za-z0-9._~/-]*$");

    private TargetPath() {
    }

    public static String requireSafe(String value) {
        if (value == null) {
            throw new LinkValidationException("대상 경로는 필수입니다");
        }

        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            throw new LinkValidationException("대상 경로는 1자 이상 1024자 이하여야 합니다");
        }
        if (!SAFE_PATH.matcher(value).matches()
                || value.startsWith("//")
                || value.contains("//")
                || value.indexOf('\\') >= 0) {
            throw new LinkValidationException("대상 경로는 신뢰 시스템 안의 안전한 절대 경로여야 합니다");
        }

        for (String segment : value.split("/", -1)) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new LinkValidationException("대상 경로에는 상대 이동 구간을 사용할 수 없습니다");
            }
        }
        return value;
    }
}
