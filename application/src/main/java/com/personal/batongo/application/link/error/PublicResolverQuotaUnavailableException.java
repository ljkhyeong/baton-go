package com.personal.batongo.application.link.error;

public class PublicResolverQuotaUnavailableException extends RuntimeException {
    public PublicResolverQuotaUnavailableException() {
        super("요청 처리 한도를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요", null, false, false);
    }
}
