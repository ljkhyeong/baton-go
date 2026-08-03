package com.personal.batongo.application.link.error;

public final class TargetContractRemediationStaleException extends RuntimeException {

    public TargetContractRemediationStaleException() {
        super("대상 계약 remediation 요청의 링크 버전이 오래되었습니다");
    }
}
