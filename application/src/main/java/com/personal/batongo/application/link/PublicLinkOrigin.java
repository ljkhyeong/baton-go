package com.personal.batongo.application.link;

import com.personal.batongo.domain.link.HttpOrigin;
import java.net.URI;
import java.util.Objects;

/** 재시도에도 같은 URL을 반환하도록 최초 발급에 사용한 출처를 보관한다. */
public final class PublicLinkOrigin {

    private static final int MAXIMUM_STORED_LENGTH = 255;
    private final HttpOrigin origin;

    public PublicLinkOrigin(URI value) {
        this.origin = HttpOrigin.require(value, "공개 기본 URL");
        if (!origin.isLoopback() && !origin.isHttps()) {
            throw new IllegalArgumentException(
                    "운영 공개 기본 URL은 HTTPS 출처여야 합니다"
            );
        }
        if (serialized().length() > MAXIMUM_STORED_LENGTH) {
            throw new IllegalArgumentException("공개 기본 URL은 255자 이하여야 합니다");
        }
    }

    public static PublicLinkOrigin fromStored(String value) {
        PublicLinkOrigin origin = new PublicLinkOrigin(URI.create(value));
        if (!origin.serialized().equals(value)) {
            throw new IllegalArgumentException("저장된 공개 출처는 표준 URL 형식이어야 합니다");
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
