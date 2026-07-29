package com.personal.batongo.application.link.error;

public class LinkNotFoundException extends RuntimeException {

    public LinkNotFoundException() {
        super("링크를 찾을 수 없습니다");
    }
}
