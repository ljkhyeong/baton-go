package com.personal.batongo.application.link.error;

public class LinkPurgedException extends RuntimeException {
    public LinkPurgedException() {
        super("보존 기간이 지나 정리된 링크입니다. 새 링크가 필요하면 새 생성 요청을 사용해 주세요");
    }
}
