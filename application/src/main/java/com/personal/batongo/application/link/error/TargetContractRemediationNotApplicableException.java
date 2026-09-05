package com.personal.batongo.application.link.error;

public final class TargetContractRemediationNotApplicableException extends RuntimeException {

    public TargetContractRemediationNotApplicableException() {
        super("허용된 대상의 링크는 이 기능으로 폐기할 수 없습니다");
    }
}
