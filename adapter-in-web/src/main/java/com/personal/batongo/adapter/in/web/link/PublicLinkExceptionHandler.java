package com.personal.batongo.adapter.in.web.link;

import com.personal.batongo.adapter.in.web.ErrorResponse;
import com.personal.batongo.adapter.in.web.GlobalExceptionHandler;
import com.personal.batongo.adapter.in.web.PublicLinkErrorPage;
import com.personal.batongo.domain.link.LinkUnavailableException;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 공개 링크 오류를 HTML 우선 요청에는 안내 화면으로, 나머지에는 기존 JSON으로 응답한다.
 *
 * <p>두 매핑이 같은 advice 안에 있어야 Spring이 {@code Accept}를 함께 협상해 헤더가 없거나
 * 모든 형식을 허용하는 요청에 JSON을 고른다.</p>
 */
@Order(0)
@RestControllerAdvice(assignableTypes = LinkResolverController.class)
public class PublicLinkExceptionHandler {

    private final GlobalExceptionHandler errors;
    private final PublicLinkErrorPage errorPage;

    public PublicLinkExceptionHandler(GlobalExceptionHandler errors, PublicLinkErrorPage errorPage) {
        this.errors = errors;
        this.errorPage = errorPage;
    }

    @ExceptionHandler(produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.ALL_VALUE})
    public ResponseEntity<ErrorResponse> json(Exception exception) {
        return errors.handle(exception);
    }

    @ExceptionHandler(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> html(Exception exception) {
        return errorPage.render(
                json(exception),
                exception instanceof LinkUnavailableException unavailable ? unavailable.notBefore() : null
        );
    }
}
