package com.personal.batongo.application.link.error;

public class InvalidLinkCodeException extends RuntimeException {

    public InvalidLinkCodeException() {
        super("링크를 찾을 수 없습니다");
    }
}
