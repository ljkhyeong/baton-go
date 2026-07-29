package com.personal.batongo.adapter.in.web;

public record ErrorResponse(
        String code,
        String message,
        String requestId
) {
}
