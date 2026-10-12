package com.personal.batongo.domain.link;

/** 생성 시각에 이미 지난 만료 시각입니다. 같은 요청을 다시 보내도 링크를 만들 수 없습니다. */
public class ExpiresAtAlreadyPassedException extends LinkValidationException {

    public ExpiresAtAlreadyPassedException() {
        super("요청한 만료 시각이 이미 지나 링크를 만들지 않았습니다");
    }
}
