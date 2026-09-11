package com.personal.batongo.application.link.error;

public class PublicLinkOriginReplayUnavailableException extends RuntimeException {

    public PublicLinkOriginReplayUnavailableException() {
        super("기존 링크를 만들 때 사용한 공개 출처를 확인할 수 없습니다");
    }
}
