package com.personal.batongo.application.link.error;

public class LinkCodeKeyBindingException extends RuntimeException {

    public LinkCodeKeyBindingException() {
        super("링크 코드 파생 키를 현재 데이터베이스에 안전하게 결합할 수 없습니다");
    }
}
