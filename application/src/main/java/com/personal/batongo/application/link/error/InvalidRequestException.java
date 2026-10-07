package com.personal.batongo.application.link.error;

public final class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
