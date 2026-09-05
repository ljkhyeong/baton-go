package com.personal.batongo.adapter.in.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
public class PublicLinkErrorPage {

    private final String template;

    public PublicLinkErrorPage() throws IOException {
        this.template = new ClassPathResource("views/public-link-error.html")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    public ResponseEntity<String> render(ResponseEntity<ErrorResponse> error) {
        ErrorResponse body = error.getBody();
        String guidance = switch (body.code()) {
            case "LINK_NOT_ACTIVE" -> "링크를 보낸 사람에게 이용 가능한 시간을 확인해 주세요.";
            case "LINK_EXPIRED", "LINK_REVOKED" -> "링크를 보낸 사람에게 새 링크를 요청해 주세요.";
            case "RATE_LIMIT_EXCEEDED" -> "%s초 후에 다시 열어 주세요. 계속 실패하면 요청 번호를 전달해 주세요."
                    .formatted(error.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
            case "INTERNAL_ERROR", "RATE_LIMIT_UNAVAILABLE" -> "잠시 후 다시 열어 주세요. 계속 실패하면 요청 번호를 전달해 주세요.";
            default -> "주소가 올바른지 확인하거나 링크를 보낸 사람에게 새 링크를 요청해 주세요.";
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
                        HtmlUtils.htmlEscape(body.message()),
                        HtmlUtils.htmlEscape(guidance),
                        HtmlUtils.htmlEscape(Objects.toString(body.requestId(), ""))
                ));
    }
}
