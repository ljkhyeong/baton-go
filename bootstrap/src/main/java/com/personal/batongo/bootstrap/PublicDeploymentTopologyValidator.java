package com.personal.batongo.bootstrap;

import com.personal.batongo.adapter.in.web.PublicLinkProperties;
import com.personal.batongo.adapter.out.external.link.TrustedTargetProperties;
import com.personal.batongo.domain.link.HttpOrigin;
import org.springframework.stereotype.Component;

/** 공개 GO origin과 신뢰 target origin 사이의 배포 누락을 시작 단계에서 차단합니다. */
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
                    "비로컬 공개 base URL에는 loopback 신뢰 target을 사용할 수 없습니다"
            );
        }
    }
}
