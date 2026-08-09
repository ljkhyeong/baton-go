package com.personal.batongo.application.link;

import com.personal.batongo.domain.link.HttpOrigin;
import java.net.URI;
import java.util.Objects;

/** 링크 생성 응답에 사용한 공개 origin을 멱등 재생 가능한 값으로 보존합니다. */
public record PublicLinkOrigin(URI value) {

    private static final int MAXIMUM_STORED_LENGTH = 255;

    public PublicLinkOrigin {
        HttpOrigin origin = HttpOrigin.require(value, "공개 base URL");
        if (!origin.isLoopback() && !origin.isHttps()) {
            throw new IllegalArgumentException(
                    "비로컬 공개 base URL은 HTTPS origin이어야 합니다"
            );
        }
        value = origin.value();
        if (serialized(value).length() > MAXIMUM_STORED_LENGTH) {
            throw new IllegalArgumentException("공개 base URL이 저장 가능한 길이를 초과합니다");
        }
    }

    public static PublicLinkOrigin fromStored(String value) {
        Objects.requireNonNull(value, "저장된 공개 origin은 필수입니다");
        PublicLinkOrigin origin = new PublicLinkOrigin(URI.create(value));
        if (!origin.serialized().equals(value)) {
            throw new IllegalArgumentException("저장된 공개 origin이 canonical 형식이 아닙니다");
        }
        return origin;
    }

    public String serialized() {
        return serialized(value);
    }

    public URI shortUrl(String rawCode) {
        Objects.requireNonNull(rawCode, "공개 링크 코드는 필수입니다");
        return value.resolve("/l/" + rawCode);
    }

    private static String serialized(URI value) {
        return value.toASCIIString();
    }

    @Override
    public String toString() {
        return "PublicLinkOrigin[redacted]";
    }
}
