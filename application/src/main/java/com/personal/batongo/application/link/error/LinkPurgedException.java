package com.personal.batongo.application.link.error;

public class LinkPurgedException extends RuntimeException {
    public LinkPurgedException() {
        super("보존 기간이 지나 삭제된 링크입니다. 새 링크는 새 Idempotency-Key로 생성해 주세요.");
    }
}
