package com.personal.batongo.adapter.in.web;

import com.personal.batongo.application.link.error.InvalidLinkCodeException;
import com.personal.batongo.application.link.error.InvalidIdempotencyKeyException;
import com.personal.batongo.application.link.error.IdempotencyKeyConflictException;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.domain.link.LinkUnavailableException;
import com.personal.batongo.domain.link.LinkValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({InvalidLinkCodeException.class, LinkNotFoundException.class})
    public ResponseEntity<ErrorResponse> handleNotFound(
            RuntimeException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.NOT_FOUND, "LINK_NOT_FOUND", exception.getMessage(), request);
    }

    @ExceptionHandler(LinkUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleUnavailable(
            LinkUnavailableException exception,
            HttpServletRequest request
    ) {
        return switch (exception.reason()) {
            case NOT_ACTIVE -> error(
                    HttpStatus.NOT_FOUND,
                    "LINK_NOT_ACTIVE",
                    exception.getMessage(),
                    request
            );
            case EXPIRED -> error(
                    HttpStatus.GONE,
                    "LINK_EXPIRED",
                    exception.getMessage(),
                    request
            );
            case REVOKED -> error(
                    HttpStatus.GONE,
                    "LINK_REVOKED",
                    exception.getMessage(),
                    request
            );
        };
    }

    @ExceptionHandler(LinkValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            LinkValidationException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_LINK", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    public ResponseEntity<ErrorResponse> handleInvalidIdempotencyKey(
            InvalidIdempotencyKeyException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.BAD_REQUEST,
                "INVALID_IDEMPOTENCY_KEY",
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(IdempotencyKeyConflictException.class)
    public ResponseEntity<ErrorResponse> handleIdempotencyConflict(
            IdempotencyKeyConflictException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONFLICT,
                "IDEMPOTENCY_KEY_REUSED",
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleRequestValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + ": "
                        + Objects.requireNonNullElse(fieldError.getDefaultMessage(), "잘못된 값입니다"))
                .orElse("요청 값이 올바르지 않습니다");
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, request);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ErrorResponse> handleMalformedRequest(
            Exception exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.BAD_REQUEST,
                "INVALID_REQUEST",
                "요청 형식이 올바르지 않습니다",
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        String requestId = RequestIdFilter.requestId(request);
        LOG.error("예상하지 못한 요청 처리 오류 requestId={}", requestId, exception);
        return error(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "INTERNAL_ERROR",
                "서버에서 요청을 처리하지 못했습니다",
                request
        );
    }

    private ResponseEntity<ErrorResponse> error(
            HttpStatus status,
            String code,
            String message,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(new ErrorResponse(
                        code,
                        message,
                        RequestIdFilter.requestId(request)
                ));
    }
}
