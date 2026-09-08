package com.personal.batongo.application.link.error;

public final class InvalidRequestException extends RuntimeException {

    private InvalidRequestException(String message) {
        super(message);
    }

    public static InvalidRequestException creationTime() {
        return new InvalidRequestException(
                "notBefore와 expiresAt은 1582-10-15T00:00:00Z 이상 "
                        + "9999-12-31T23:59:59.999999Z 이하의 마이크로초 단위여야 합니다"
        );
    }

    public static InvalidRequestException targetContractInventory() {
        return new InvalidRequestException("링크 점검 목록 조회 조건이 올바르지 않습니다");
    }

    public static InvalidRequestException linkSearch() {
        return new InvalidRequestException(
                "limit은 1..500이어야 하며 createdBefore는 createdFrom보다 뒤여야 합니다"
        );
    }

    public static InvalidRequestException linkBatch() {
        return new InvalidRequestException("linkIds에는 비어 있지 않은 링크 ID를 1개 이상 100개 이하로 지정해야 합니다");
    }

    public static InvalidRequestException targetContractRemediation() {
        return new InvalidRequestException("비허용 링크 폐기 요청이 올바르지 않습니다");
    }
}
