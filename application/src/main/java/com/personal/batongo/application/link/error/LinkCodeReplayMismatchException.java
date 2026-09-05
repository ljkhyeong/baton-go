package com.personal.batongo.application.link.error;

public class LinkCodeReplayMismatchException extends RuntimeException {

    public LinkCodeReplayMismatchException() {
        super("현재 코드 생성 설정으로 기존 URL을 반환할 수 없습니다");
    }
}
