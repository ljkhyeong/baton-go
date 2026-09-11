package com.personal.batongo.bootstrap;

import com.personal.batongo.adapter.in.web.PublicLinkProperties;
import com.personal.batongo.adapter.out.external.link.TrustedTargetProperties;
import com.personal.batongo.domain.link.HttpOrigin;
import org.springframework.stereotype.Component;

/** 운영 공개 URL과 로컬 대상 URL을 함께 사용한 설정을 시작할 때 거부합니다. */
@Component
public class PublicDeploymentTopologyValidator {

    public PublicDeploymentTopologyValidator(
            PublicLinkProperties publicLinkProperties,
            TrustedTargetProperties trustedTargetProperties
    ) {
        HttpOrigin publicOrigin = publicLinkProperties.current().origin();
        HttpOrigin batonOrigin = trustedTargetProperties.batonOrigin();
        if (!publicOrigin.isLoopback() && batonOrigin.isLoopback()) {
            throw new IllegalArgumentException(
                    "운영 공개 기본 URL에는 로컬 BATON 대상 URL을 사용할 수 없습니다"
            );
        }
    }
}
