package com.personal.batongo.application.link.error;

public class PublicLinkOriginReplayUnavailableException extends RuntimeException {

    public PublicLinkOriginReplayUnavailableException() {
        super("기존 링크 생성에 사용한 공개 origin을 복구할 수 없습니다");
    }
}
