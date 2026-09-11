package com.personal.batongo.application.link.error;

public final class TargetContractRemediationNotApplicableException extends RuntimeException {

    public TargetContractRemediationNotApplicableException() {
        super("허용된 대상의 링크는 계약 위반 링크 폐기 대상이 아닙니다");
    }
}
