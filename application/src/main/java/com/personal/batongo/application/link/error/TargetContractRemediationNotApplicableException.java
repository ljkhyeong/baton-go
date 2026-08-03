package com.personal.batongo.application.link.error;

public final class TargetContractRemediationNotApplicableException extends RuntimeException {

    public TargetContractRemediationNotApplicableException() {
        super("현재 대상 계약을 준수하는 링크에는 remediation을 적용할 수 없습니다");
    }
}
