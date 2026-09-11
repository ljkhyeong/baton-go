package com.personal.batongo.application.link.error;

public class LinkCodeKeyBindingException extends RuntimeException {

    public LinkCodeKeyBindingException() {
        super("링크 코드 키가 데이터베이스의 등록 정보와 일치하지 않습니다");
    }
}
