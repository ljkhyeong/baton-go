package com.personal.batongo.bootstrap;

import com.personal.batongo.adapter.in.web.ManagementProperties;
import com.personal.batongo.adapter.out.external.link.LinkCodeProperties;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class CredentialSeparationValidator {

    private static final Set<String> PUBLISHED_CREDENTIALS = Set.of(
            "replace-with-at-least-32-random-characters",
            "replace-with-a-separate-at-least-32-character-secret"
    );

    public CredentialSeparationValidator(
            ManagementProperties managementProperties,
            LinkCodeProperties linkCodeProperties
    ) {
        String managementToken = managementProperties.token();
        String linkCodeSecret = linkCodeProperties.secret();

        if (PUBLISHED_CREDENTIALS.contains(managementToken)
                || PUBLISHED_CREDENTIALS.contains(linkCodeSecret)) {
            throw new IllegalStateException("공개 예시 credential은 실행 환경에서 사용할 수 없습니다");
        }
        if (managementToken.equals(linkCodeSecret)) {
            throw new IllegalStateException("관리 credential과 링크 코드 파생 비밀은 서로 달라야 합니다");
        }
    }
}
