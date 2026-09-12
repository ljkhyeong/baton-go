package com.personal.batongo.adapter.in.web.link;

import com.personal.batongo.adapter.in.web.ErrorResponse;
import com.personal.batongo.adapter.in.web.GlobalExceptionHandler;
import com.personal.batongo.adapter.in.web.PublicLinkErrorPage;
import com.personal.batongo.adapter.in.web.PublicResolverRateLimitExceededException;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.error.PublicResolverQuotaUnavailableException;
import com.personal.batongo.application.link.error.StoredTargetPolicyViolationException;
import com.personal.batongo.domain.link.LinkUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(0)
@RestControllerAdvice(assignableTypes = LinkResolverController.class)
public class PublicLinkExceptionHandler {

    private final GlobalExceptionHandler errors;
    private final PublicLinkErrorPage errorPage;

    public PublicLinkExceptionHandler(GlobalExceptionHandler errors, PublicLinkErrorPage errorPage) {
        this.errors = errors;
        this.errorPage = errorPage;
    }

    @ExceptionHandler(
            value = LinkUnavailableException.class,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ErrorResponse> unavailableJson(
            LinkUnavailableException exception, HttpServletRequest request
    ) {
        return errors.handleUnavailable(exception, request);
    }

    @ExceptionHandler(
            value = LinkUnavailableException.class,
            produces = MediaType.TEXT_HTML_VALUE
    )
    public ResponseEntity<String> unavailableHtml(
            LinkUnavailableException exception, HttpServletRequest request
    ) {
        return errorPage.render(unavailableJson(exception, request), exception.notBefore());
    }

    @ExceptionHandler(
            value = PublicResolverRateLimitExceededException.class,
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.ALL_VALUE}
    )
    public ResponseEntity<ErrorResponse> rateLimitedJson(
            PublicResolverRateLimitExceededException exception,
            HttpServletRequest request
    ) {
        return errors.handlePublicResolverRateLimited(exception, request);
    }

    @ExceptionHandler(
            value = PublicResolverRateLimitExceededException.class,
            produces = MediaType.TEXT_HTML_VALUE
    )
    public ResponseEntity<String> rateLimitedHtml(
            PublicResolverRateLimitExceededException exception,
            HttpServletRequest request
    ) {
        return errorPage.render(rateLimitedJson(exception, request));
    }

    @ExceptionHandler(
            value = LinkNotFoundException.class,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ErrorResponse> notFoundJson(HttpServletRequest request) {
        return errors.handleNotFound(request);
    }

    @ExceptionHandler(value = LinkNotFoundException.class, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> notFoundHtml(HttpServletRequest request) {
        return errorPage.render(notFoundJson(request));
    }

    @ExceptionHandler(
            value = StoredTargetPolicyViolationException.class,
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ErrorResponse> storedTargetViolationJson(
            StoredTargetPolicyViolationException exception, HttpServletRequest request
    ) {
        return errors.handleStoredTargetPolicyViolation(exception, request);
    }

    @ExceptionHandler(
            value = StoredTargetPolicyViolationException.class,
            produces = MediaType.TEXT_HTML_VALUE
    )
    public ResponseEntity<String> storedTargetViolationHtml(
            StoredTargetPolicyViolationException exception, HttpServletRequest request
    ) {
        return errorPage.render(storedTargetViolationJson(exception, request));
    }

    @ExceptionHandler(value = PublicResolverQuotaUnavailableException.class,
            produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.ALL_VALUE})
    public ResponseEntity<ErrorResponse> quotaUnavailableJson(
            PublicResolverQuotaUnavailableException exception, HttpServletRequest request) {
        return errors.handleQuotaUnavailable(exception, request);
    }

    @ExceptionHandler(value = PublicResolverQuotaUnavailableException.class, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> quotaUnavailableHtml(
            PublicResolverQuotaUnavailableException exception, HttpServletRequest request) {
        return errorPage.render(quotaUnavailableJson(exception, request));
    }

    @ExceptionHandler(value = Exception.class, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ErrorResponse> unexpectedJson(
            Exception exception, HttpServletRequest request
    ) {
        return errors.handleUnexpected(exception, request);
    }

    @ExceptionHandler(value = Exception.class, produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> unexpectedHtml(
            Exception exception, HttpServletRequest request
    ) {
        return errorPage.render(unexpectedJson(exception, request));
    }
}
