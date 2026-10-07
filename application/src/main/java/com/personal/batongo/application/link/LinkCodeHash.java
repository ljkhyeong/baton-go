package com.personal.batongo.application.link;

import com.personal.batongo.application.link.error.LinkNotFoundException;
import java.util.regex.Pattern;

/** DB에는 공개 코드 원문 대신 이 SHA-256 해시만 저장하고 조회한다. */
public final class LinkCodeHash {

    // 공개 코드는 HMAC 결과 16바이트의 패딩 없는 Base64 URL 22자다.
    private static final Pattern RAW_CODE = Pattern.compile("[A-Za-z0-9_-]{22}");

    private LinkCodeHash() {
    }

    /** 형식이 다른 코드는 DB 조회 전에 없는 링크로 거부한다. */
    public static String of(String rawCode) {
        if (rawCode == null || !RAW_CODE.matcher(rawCode).matches()) {
            throw new LinkNotFoundException();
        }
        return LinkCreationFingerprint.sha256(rawCode);
    }
}
