package com.personal.batongo.application.link.port.in;

import java.net.URI;

/** 공개 링크 코드를 허용된 대상 URL로 바꾸는 읽기 전용 접속 처리입니다. */
public interface ResolveLinkUseCase {

    ResolvedLinkResult resolveLink(String rawCode);

    record ResolvedLinkResult(URI destination) {

        @Override
        public String toString() {
            return "ResolvedLinkResult[destination=redacted]";
        }
    }
}
