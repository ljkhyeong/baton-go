package com.personal.batongo.application.link.error;

public final class TargetContractRemediationStaleException extends RuntimeException {

    public TargetContractRemediationStaleException() {
        super("링크가 변경되었습니다. 다시 조회한 뒤 요청해 주세요.");
    }
}
