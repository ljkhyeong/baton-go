package com.personal.batongo.application.link.error;

public class LinkCodeReplayMismatchException extends RuntimeException {

    public LinkCodeReplayMismatchException() {
        super("현재 링크 코드 파생 설정으로 기존 링크를 재생할 수 없습니다");
    }
}
