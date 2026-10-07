package com.personal.batongo.adapter.in.web.link;

import com.personal.batongo.adapter.in.web.ErrorResponse;
import com.personal.batongo.adapter.in.web.GlobalExceptionHandler;
import com.personal.batongo.domain.link.LinkUnavailableException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Locale;
import java.util.Objects;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.util.HtmlUtils;

/**
 * 공개 링크 오류를 HTML 우선 요청에는 안내 화면으로, 나머지에는 기존 JSON으로 응답한다.
 *
 * <p>두 매핑이 같은 advice 안에 있어야 Spring이 {@code Accept}를 함께 협상해 헤더가 없거나
 * 모든 형식을 허용하는 요청에 JSON을 고른다.</p>
 */
@Order(0)
@RestControllerAdvice(assignableTypes = LinkResolverController.class)
public class PublicLinkExceptionHandler {

    private static final DateTimeFormatter START_TIME = new DateTimeFormatterBuilder()
            .appendPattern("uuuu-MM-dd HH:mm:ss")
            .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
            .toFormatter(Locale.ROOT)
            .withZone(ZoneId.of("Asia/Seoul"));

    private final GlobalExceptionHandler errors;
    private final String template;

    public PublicLinkExceptionHandler(GlobalExceptionHandler errors) throws IOException {
        this.errors = errors;
        this.template = new ClassPathResource("views/public-link-error.html")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    @ExceptionHandler(produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.ALL_VALUE})
    public ResponseEntity<ErrorResponse> json(Exception exception) {
        return errors.handle(exception);
    }

    @ExceptionHandler(produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> html(Exception exception) {
        ResponseEntity<ErrorResponse> error = json(exception);
        Instant notBefore = exception instanceof LinkUnavailableException unavailable ? unavailable.notBefore() : null;
        ErrorResponse body = error.getBody();
        String title = "RATE_LIMIT_UNAVAILABLE".equals(body.code())
                ? "지금은 링크를 열 수 없습니다."
                : body.message();
        String guidance = switch (body.code()) {
            case "LINK_NOT_ACTIVE" -> notBefore == null
                    ? "링크를 보낸 사람에게 이용 가능한 시간을 확인해 주세요."
                    : "%s (한국 시간)부터 이용할 수 있습니다.".formatted(START_TIME.format(notBefore));
            case "LINK_EXPIRED", "LINK_REVOKED" -> "링크를 보낸 사람에게 새 링크를 요청해 주세요.";
            case "RATE_LIMIT_EXCEEDED" -> "%s초 후에 다시 열어 주세요. 계속 실패하면 요청 번호를 전달해 주세요."
                    .formatted(error.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
            case "INTERNAL_ERROR", "RATE_LIMIT_UNAVAILABLE" -> "잠시 후 다시 열어 주세요. 계속 실패하면 요청 번호를 전달해 주세요.";
            default -> "주소가 올바른지 확인하거나 링크를 보낸 사람에게 새 링크를 요청해 주세요.";
        };
        String retryAction = switch (body.code()) {
            case "LINK_NOT_ACTIVE", "RATE_LIMIT_EXCEEDED", "INTERNAL_ERROR", "RATE_LIMIT_UNAVAILABLE" ->
                    "<a class=\"retry\" href=\"\">다시 열기</a>";
            default -> "";
        };
        return ResponseEntity.status(error.getStatusCode())
                .headers(error.getHeaders())
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .header("Content-Security-Policy",
                        "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; "
                                + "frame-ancestors 'none'; form-action 'none'")
                .header("X-Content-Type-Options", "nosniff")
                .varyBy(HttpHeaders.ACCEPT)
                .body(template.formatted(
                        HtmlUtils.htmlEscape(title),
                        HtmlUtils.htmlEscape(guidance),
                        HtmlUtils.htmlEscape(Objects.toString(body.requestId(), "")),
                        retryAction
                ));
    }
}
