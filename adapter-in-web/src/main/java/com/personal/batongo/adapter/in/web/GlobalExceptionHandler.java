package com.personal.batongo.adapter.in.web;

import com.personal.batongo.application.link.error.InvalidIdempotencyKeyException;
import com.personal.batongo.application.link.error.InvalidRequestException;
import com.personal.batongo.application.link.error.IdempotencyKeyConflictException;
import com.personal.batongo.application.link.error.LinkCodeKeyBindingException;
import com.personal.batongo.application.link.error.LinkCodeReplayMismatchException;
import com.personal.batongo.application.link.error.LinkCreationReplayUnavailableException;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.error.LinkPurgedException;
import com.personal.batongo.application.link.error.PublicResolverQuotaUnavailableException;
import com.personal.batongo.application.link.error.PublicLinkOriginReplayUnavailableException;
import com.personal.batongo.application.link.error.StoredTargetPolicyViolationException;
import com.personal.batongo.application.link.error.TargetContractRemediationNotApplicableException;
import com.personal.batongo.application.link.error.TargetContractRemediationStaleException;
import com.personal.batongo.domain.link.LinkUnavailableException;
import com.personal.batongo.domain.link.LinkValidationException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TARGET_POLICY_VIOLATION_METRIC =
            "baton.go.public.resolver.target.contract.violations";
    private static final int MAX_LOGGED_CAUSE_TYPES = 8;
    private static final int MAX_LOGGED_STACK_FRAMES = 12;

    private final Counter targetPolicyViolationCounter;
    private final Counter quotaFailureCounter;
    private final Map<String, Counter> linkRecoveryFailureCounters;

    public GlobalExceptionHandler(MeterRegistry meterRegistry) {
        this.quotaFailureCounter = meterRegistry.counter("baton.go.public.resolver.quota.failures");
        this.targetPolicyViolationCounter = meterRegistry.counter(TARGET_POLICY_VIOLATION_METRIC);
        this.linkRecoveryFailureCounters = Stream.of(
                "LINK_CREATION_REPLAY_UNAVAILABLE",
                "LINK_CODE_REPLAY_UNAVAILABLE",
                "LINK_CODE_CONFIGURATION_MISMATCH",
                "PUBLIC_LINK_ORIGIN_REPLAY_UNAVAILABLE"
        ).collect(Collectors.toUnmodifiableMap(
                code -> code,
                code -> meterRegistry.counter("baton.go.management.link.recovery.failures", "code", code)
        ));
    }

    @ExceptionHandler(StoredTargetPolicyViolationException.class)
    public ResponseEntity<ErrorResponse> handleStoredTargetPolicyViolation(
            StoredTargetPolicyViolationException exception,
            HttpServletRequest request
    ) {
        recordStoredTargetPolicyViolation(exception, request);
        return handleNotFound(request);
    }

    @ExceptionHandler(LinkNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.NOT_FOUND,
                "LINK_NOT_FOUND",
                "링크를 찾을 수 없습니다",
                request
        );
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

    @ExceptionHandler(PublicResolverRateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handlePublicResolverRateLimited(
            PublicResolverRateLimitExceededException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, Long.toString(exception.retryAfterSeconds()))
                .varyBy(HttpHeaders.ACCEPT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ErrorResponse(
                        "RATE_LIMIT_EXCEEDED",
                        "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요",
                        RequestIdFilter.requestId(request)
                ));
    }

    @ExceptionHandler(PublicResolverQuotaUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleQuotaUnavailable(
            PublicResolverQuotaUnavailableException exception, HttpServletRequest request) {
        quotaFailureCounter.increment();
        return error(HttpStatus.SERVICE_UNAVAILABLE, "RATE_LIMIT_UNAVAILABLE", exception.getMessage(), request);
    }

    @ExceptionHandler(LinkValidationException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            LinkValidationException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_LINK", exception.getMessage(), request);
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ErrorResponse> handleInvalidRequest(
            InvalidRequestException exception,
            HttpServletRequest request
    ) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", exception.getMessage(), request);
    }

    @ExceptionHandler(TargetContractRemediationNotApplicableException.class)
    public ResponseEntity<ErrorResponse> handleTargetContractRemediationNotApplicable(
            TargetContractRemediationNotApplicableException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONFLICT,
                "REMEDIATION_NOT_APPLICABLE",
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(TargetContractRemediationStaleException.class)
    public ResponseEntity<ErrorResponse> handleTargetContractRemediationStale(
            TargetContractRemediationStaleException exception,
            HttpServletRequest request
    ) {
        return error(
                HttpStatus.CONFLICT,
                "REMEDIATION_STALE",
                exception.getMessage(),
                request
        );
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

    @ExceptionHandler(LinkPurgedException.class)
    public ResponseEntity<ErrorResponse> handlePurged(LinkPurgedException exception, HttpServletRequest request) {
        return error(HttpStatus.GONE, "LINK_PURGED", exception.getMessage(), request);
    }

    @ExceptionHandler(LinkCreationReplayUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleLinkCreationReplayUnavailable(
            LinkCreationReplayUnavailableException exception,
            HttpServletRequest request
    ) {
        LOG.error(
                "기존 링크 생성 결과 누락 linkId={} requestId={}",
                exception.linkId(),
                RequestIdFilter.requestId(request)
        );
        return linkRecoveryFailure(
                "LINK_CREATION_REPLAY_UNAVAILABLE",
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(LinkCodeReplayMismatchException.class)
    public ResponseEntity<ErrorResponse> handleLinkCodeReplayMismatch(
            LinkCodeReplayMismatchException exception,
            HttpServletRequest request
    ) {
        logUnexpected(exception, request);
        return linkRecoveryFailure(
                "LINK_CODE_REPLAY_UNAVAILABLE",
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(LinkCodeKeyBindingException.class)
    public ResponseEntity<ErrorResponse> handleLinkCodeKeyBinding(
            LinkCodeKeyBindingException exception,
            HttpServletRequest request
    ) {
        logUnexpected(exception, request);
        return linkRecoveryFailure(
                "LINK_CODE_CONFIGURATION_MISMATCH",
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(PublicLinkOriginReplayUnavailableException.class)
    public ResponseEntity<ErrorResponse> handlePublicLinkOriginReplayUnavailable(
            PublicLinkOriginReplayUnavailableException exception,
            HttpServletRequest request
    ) {
        logUnexpected(exception, request);
        return linkRecoveryFailure(
                "PUBLIC_LINK_ORIGIN_REPLAY_UNAVAILABLE",
                exception.getMessage(),
                request
        );
    }

    private ResponseEntity<ErrorResponse> linkRecoveryFailure(
            String code, String message, HttpServletRequest request
    ) {
        linkRecoveryFailureCounters.get(code).increment();
        return error(HttpStatus.INTERNAL_SERVER_ERROR, code, message, request);
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
                .map(fieldError -> fieldError.getField() + ": 요청 값이 올바르지 않습니다")
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
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request
    ) {
        Object responseBody = body instanceof ErrorResponse
                ? body
                : frameworkError(status, request);
        if (status.is5xxServerError()) {
            logUnexpected(exception, servletRequest(request));
        }
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.putAll(headers);
        responseHeaders.setContentType(MediaType.APPLICATION_JSON);
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
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(new ErrorResponse(
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
        LOG.error(
                "예상하지 못한 요청 처리 오류 requestId={} exceptionType={} causeTypes={} stackFrames={}",
                requestId,
                exception.getClass().getName(),
                causeTypes(exception),
                stackFrames(exception)
        );
    }

    private List<String> causeTypes(Exception exception) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        visited.add(exception);
        return Stream.iterate(
                        (Throwable) exception,
                        Objects::nonNull,
                        Throwable::getCause
                )
                .skip(1)
                .takeWhile(visited::add)
                .limit(MAX_LOGGED_CAUSE_TYPES)
                .map(cause -> cause.getClass().getName())
                .toList();
    }

    private List<String> stackFrames(Exception exception) {
        return Arrays.stream(exception.getStackTrace())
                .limit(MAX_LOGGED_STACK_FRAMES)
                .map(StackTraceElement::toString)
                .toList();
    }

    private void recordStoredTargetPolicyViolation(
            StoredTargetPolicyViolationException exception,
            HttpServletRequest request
    ) {
        targetPolicyViolationCounter.increment();
        LOG.error(
                "저장된 링크 대상 계약 위반 linkId={} requestId={}",
                exception.linkId(),
                RequestIdFilter.requestId(request)
        );
    }
}
