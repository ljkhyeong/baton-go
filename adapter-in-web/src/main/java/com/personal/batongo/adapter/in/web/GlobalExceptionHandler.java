package com.personal.batongo.adapter.in.web;

import com.personal.batongo.application.link.error.InvalidLinkCodeException;
import com.personal.batongo.application.link.error.InvalidIdempotencyKeyException;
import com.personal.batongo.application.link.error.IdempotencyKeyConflictException;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.domain.link.LinkUnavailableException;
import com.personal.batongo.domain.link.LinkValidationException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import org.springframework.beans.TypeMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

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

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fieldError -> fieldError.getField() + ": "
                        + Objects.requireNonNullElse(fieldError.getDefaultMessage(), "잘못된 값입니다"))
                .orElse("요청 값이 올바르지 않습니다");
        return handleExceptionInternal(
                exception,
                errorBody("INVALID_REQUEST", message, request),
                headers,
                status,
                request
        );
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        return handleExceptionInternal(
                exception,
                errorBody("INVALID_REQUEST", "요청 형식이 올바르지 않습니다", request),
                headers,
                status,
                request
        );
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException exception,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        return handleExceptionInternal(
                exception,
                errorBody("INVALID_REQUEST", "요청 형식이 올바르지 않습니다", request),
                headers,
                status,
                request
        );
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.set(HttpHeaders.CACHE_CONTROL, "no-store");
        Object responseBody = body instanceof ErrorResponse
                ? body
                : frameworkError(status, request);
        if (status.is5xxServerError()) {
            logUnexpected(exception, servletRequest(request));
        }
        return super.handleExceptionInternal(
                exception,
                responseBody,
                responseHeaders,
                status,
                request
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request
    ) {
        logUnexpected(exception, request);
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

    private ErrorResponse frameworkError(HttpStatusCode status, WebRequest request) {
        return switch (status.value()) {
            case 400 -> errorBody("INVALID_REQUEST", "요청 형식이 올바르지 않습니다", request);
            case 404 -> errorBody("RESOURCE_NOT_FOUND", "요청한 경로를 찾을 수 없습니다", request);
            case 405 -> errorBody("METHOD_NOT_ALLOWED", "지원하지 않는 HTTP 메서드입니다", request);
            case 415 -> errorBody(
                    "UNSUPPORTED_MEDIA_TYPE",
                    "지원하지 않는 요청 본문 형식입니다",
                    request
            );
            default -> status.is4xxClientError()
                    ? errorBody("INVALID_REQUEST", "요청을 처리할 수 없습니다", request)
                    : errorBody("INTERNAL_ERROR", "서버에서 요청을 처리하지 못했습니다", request);
        };
    }

    private ErrorResponse errorBody(String code, String message, WebRequest request) {
        return new ErrorResponse(
                code,
                message,
                RequestIdFilter.requestId(servletRequest(request))
        );
    }

    private HttpServletRequest servletRequest(WebRequest request) {
        return request instanceof ServletWebRequest servletWebRequest
                ? servletWebRequest.getRequest()
                : null;
    }

    private void logUnexpected(Exception exception, HttpServletRequest request) {
        String requestId = request == null ? null : RequestIdFilter.requestId(request);
        LOG.error("예상하지 못한 요청 처리 오류 requestId={}", requestId, exception);
    }
}
