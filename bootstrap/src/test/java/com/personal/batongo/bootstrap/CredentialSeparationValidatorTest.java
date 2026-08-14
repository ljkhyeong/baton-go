package com.personal.batongo.bootstrap;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.adapter.in.web.ManagementProperties;
import com.personal.batongo.adapter.out.external.link.LinkCodeProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CredentialSeparationValidatorTest {

    @Test
    @DisplayName("관리 credential과 링크 코드 비밀이 같으면 실행 설정을 거부한다")
    void rejectsSharedCredential() {
        String sharedCredential = "shared-credential-with-more-than-32-characters";

        assertThatThrownBy(() -> new CredentialSeparationValidator(
                new ManagementProperties(sharedCredential),
                new LinkCodeProperties(sharedCredential)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(sharedCredential);
    }
}
