package com.personal.batongo.application.link;

import com.personal.batongo.domain.link.HttpOrigin;
import java.net.URI;
import java.util.Objects;

/** 링크 생성 응답에 사용한 공개 origin을 멱등 재생 가능한 값으로 보존합니다. */
public final class PublicLinkOrigin {

    private static final int MAXIMUM_STORED_LENGTH = 255;
    private final HttpOrigin origin;

    public PublicLinkOrigin(URI value) {
        this.origin = HttpOrigin.require(value, "공개 base URL");
        if (!origin.isLoopback() && !origin.isHttps()) {
            throw new IllegalArgumentException(
                    "비로컬 공개 base URL은 HTTPS origin이어야 합니다"
            );
        }
        if (serialized().length() > MAXIMUM_STORED_LENGTH) {
            throw new IllegalArgumentException("공개 base URL이 저장 가능한 길이를 초과합니다");
        }
    }

    public static PublicLinkOrigin fromStored(String value) {
        PublicLinkOrigin origin = new PublicLinkOrigin(URI.create(value));
        if (!origin.serialized().equals(value)) {
            throw new IllegalArgumentException("저장된 공개 origin이 canonical 형식이 아닙니다");
        }
        return origin;
    }

    public HttpOrigin origin() {
        return origin;
    }

    public String serialized() {
        return origin.value().toASCIIString();
    }

    public URI shortUrl(String rawCode) {
        Objects.requireNonNull(rawCode, "공개 링크 코드는 필수입니다");
        return origin.resolve("/l/" + rawCode);
    }

    @Override
    public String toString() {
        return "PublicLinkOrigin[redacted]";
    }
}
