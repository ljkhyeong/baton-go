package com.personal.batongo.adapter.in.web.link;

import com.personal.batongo.adapter.in.web.ErrorResponse;
import com.personal.batongo.adapter.in.web.GlobalExceptionHandler;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.error.StoredTargetPolicyViolationException;
import com.personal.batongo.domain.link.LinkUnavailableException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.util.HtmlUtils;

@Order(0)
@RestControllerAdvice(assignableTypes = LinkResolverController.class)
public class PublicLinkExceptionHandler {

    private final GlobalExceptionHandler errors;
    private final String template;

    public PublicLinkExceptionHandler(GlobalExceptionHandler errors) throws IOException {
        this.errors = errors;
        this.template = new ClassPathResource("views/public-link-error.html")
                .getContentAsString(StandardCharsets.UTF_8);
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
        return html(unavailableJson(exception, request));
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
        return html(notFoundJson(request));
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
        return html(storedTargetViolationJson(exception, request));
    }

    private ResponseEntity<String> html(ResponseEntity<ErrorResponse> error) {
        var response = ResponseEntity.status(error.getStatusCode())
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .header("Content-Security-Policy",
                        "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; "
                                + "frame-ancestors 'none'; form-action 'none'")
                .header("X-Content-Type-Options", "nosniff")
                .varyBy(HttpHeaders.ACCEPT);
        ErrorResponse body = error.getBody();
        if (body == null) {
            return response.build();
        }
        String guidance = switch (body.code()) {
            case "LINK_NOT_ACTIVE" -> "링크를 보낸 사람에게 이용 가능한 시간을 확인해 주세요.";
            case "LINK_EXPIRED", "LINK_REVOKED" -> "링크를 보낸 사람에게 새 링크를 요청해 주세요.";
            default -> "주소가 올바른지 확인하거나 링크를 보낸 사람에게 새 링크를 요청해 주세요.";
        };
        return response.body(template.formatted(
                HtmlUtils.htmlEscape(body.message()),
                HtmlUtils.htmlEscape(guidance),
                HtmlUtils.htmlEscape(Objects.toString(body.requestId(), ""))
        ));
    }
}
