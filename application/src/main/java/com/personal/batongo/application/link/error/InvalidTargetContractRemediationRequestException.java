package com.personal.batongo.application.link.error;

public final class InvalidTargetContractRemediationRequestException extends RuntimeException {

    public InvalidTargetContractRemediationRequestException() {
        super("대상 계약 remediation 요청이 올바르지 않습니다");
    }
}
