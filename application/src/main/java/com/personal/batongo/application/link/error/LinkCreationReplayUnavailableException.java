package com.personal.batongo.application.link.error;

import java.util.UUID;

public final class LinkCreationReplayUnavailableException extends RuntimeException {

    private final UUID linkId;

    public LinkCreationReplayUnavailableException(UUID linkId) {
        super("기존 링크 생성 결과를 복구할 수 없습니다");
        this.linkId = linkId;
    }

    public UUID linkId() {
        return linkId;
    }
}
