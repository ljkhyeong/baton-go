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
        return new InvalidRequestException("대상 계약 점검 목록의 limit은 1~500이어야 합니다");
    }

    public static InvalidRequestException linkSearch() {
        return new InvalidRequestException(
                "limit은 1~500이고 createdBefore는 createdFrom보다 뒤여야 합니다"
        );
    }

    public static InvalidRequestException linkBatch() {
        return new InvalidRequestException("linkIds는 빈 값 없이 1~100개 지정해야 합니다");
    }

    public static InvalidRequestException targetContractRemediation() {
        return new InvalidRequestException("expectedVersion은 0~9223372036854775806이어야 합니다");
    }
}
