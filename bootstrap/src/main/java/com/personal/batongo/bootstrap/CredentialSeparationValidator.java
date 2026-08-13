package com.personal.batongo.bootstrap;

import com.personal.batongo.adapter.in.web.ManagementProperties;
import com.personal.batongo.adapter.out.external.link.LinkCodeProperties;
import org.springframework.stereotype.Component;

@Component
public class CredentialSeparationValidator {

    public CredentialSeparationValidator(
            ManagementProperties managementProperties,
            LinkCodeProperties linkCodeProperties
    ) {
        String managementToken = managementProperties.token();
        String linkCodeSecret = linkCodeProperties.secret();

        if (managementToken.equals(linkCodeSecret)) {
            throw new IllegalStateException("관리 credential과 링크 코드 파생 비밀은 서로 달라야 합니다");
        }
    }
}
