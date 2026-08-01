package com.personal.batongo.bootstrap;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.adapter.in.web.ManagementProperties;
import com.personal.batongo.adapter.out.external.link.LinkCodeProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CredentialSeparationValidatorTest {

    private static final String MANAGEMENT_TOKEN =
            "actual-management-token-with-more-than-32-characters";
    private static final String LINK_CODE_SECRET =
            "actual-link-code-secret-with-more-than-32-characters";

    @Test
    @DisplayName("서로 다른 실제 credential이면 실행 설정을 허용한다")
    void acceptsDistinctRuntimeCredentials() {
        assertThatCode(() -> validate(MANAGEMENT_TOKEN, LINK_CODE_SECRET))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("공개된 관리 credential 예시값은 실행 설정에서 거부한다")
    void rejectsPublishedManagementCredentialPlaceholder() {
        assertThatThrownBy(() -> validate(
                "replace-with-a-deployment-specific-management-token",
                LINK_CODE_SECRET
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("공개 예시 credential은 실행 환경에서 사용할 수 없습니다");
    }

    @Test
    @DisplayName("공개된 링크 코드 비밀 예시값은 실행 설정에서 거부한다")
    void rejectsPublishedLinkCodeSecretPlaceholder() {
        assertThatThrownBy(() -> validate(
                MANAGEMENT_TOKEN,
                "replace-with-an-independent-code-derivation-secret"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("공개 예시 credential은 실행 환경에서 사용할 수 없습니다");
    }

    @Test
    @DisplayName("관리 credential과 링크 코드 비밀이 같으면 실행 설정을 거부한다")
    void rejectsSharedCredential() {
        String sharedCredential = "shared-credential-with-more-than-32-characters";

        assertThatThrownBy(() -> validate(sharedCredential, sharedCredential))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("관리 credential과 링크 코드 파생 비밀은 서로 달라야 합니다")
                .hasMessageNotContaining(sharedCredential);
    }

    private void validate(String managementToken, String linkCodeSecret) {
        new CredentialSeparationValidator(
                new ManagementProperties(managementToken),
                new LinkCodeProperties(linkCodeSecret)
        );
    }
}
