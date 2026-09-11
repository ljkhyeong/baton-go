package com.personal.batongo.application.link.error;

public class PublicResolverQuotaUnavailableException extends RuntimeException {
    public PublicResolverQuotaUnavailableException() {
        super("지금은 링크 요청을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요", null, false, false);
    }
}
