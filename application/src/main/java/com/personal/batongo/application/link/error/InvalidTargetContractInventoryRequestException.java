package com.personal.batongo.application.link.error;

public final class InvalidTargetContractInventoryRequestException extends RuntimeException {

    public InvalidTargetContractInventoryRequestException() {
        super("대상 계약 inventory 요청이 올바르지 않습니다");
    }
}
