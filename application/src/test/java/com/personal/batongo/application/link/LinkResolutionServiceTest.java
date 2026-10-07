package com.personal.batongo.application.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.error.StoredTargetPolicyViolationException;
import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLink;
import com.personal.batongo.application.link.port.out.TargetUrlPort;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LinkResolutionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-29T10:00:00Z");
    private static final UUID LINK_ID = UUID.fromString("d3014090-bd91-4bfc-8a42-89b7f1800c32");
    private static final String RAW_CODE = "abcdefghijklmnopqrstuv";
    private static final String CODE_HASH = LinkCodeHash.of(RAW_CODE);
    private static final String BATON_PATH =
            "/teams/8e448211-66ae-44ab-9888-c4960648c22b"
                    + "/seasons/713d9cb7-2842-4f9f-b3cc-e31d98c6238a";
    private static final String ROUND_PATH = "/room/abcd-efgh-jkmn";

    private final SmartLinkRepository repository = mock(SmartLinkRepository.class);
    private final TargetUrlPort targetUrlPort = mock(TargetUrlPort.class);
    private final LinkResolutionService service = new LinkResolutionService(
            repository, targetUrlPort, Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    @DisplayName("활성 링크 접속은 대상 URL 생성 포트가 만든 URL을 반환한다")
    void resolvesThroughTrustedTargetPort() {
        URI destination = URI.create("https://baton.example" + BATON_PATH);
        when(repository.findByCodeHash(CODE_HASH)).thenReturn(Optional.of(new StoredLink(
                LINK_ID, CODE_HASH, TargetSystem.BATON.name(), BATON_PATH, LinkPurpose.NAVIGATION.name(),
                null, null, null, NOW
        )));
        when(targetUrlPort.resolve(any())).thenReturn(destination);

        var result = service.resolveLink(RAW_CODE);

        assertThat(result.destination()).isEqualTo(destination);
        verify(targetUrlPort).resolve(argThat(target ->
                target.targetSystem() == TargetSystem.BATON
                        && target.targetPath().equals(BATON_PATH)));
    }

    @Test
    @DisplayName("저장 대상이 규칙을 위반하면 활성·만료 확인 전에 거부한다")
    void hidesKnownStoredPolicyViolationBeforeLifecycleCheck() {
        when(repository.findByCodeHash(CODE_HASH)).thenReturn(Optional.of(new StoredLink(
                LINK_ID, CODE_HASH, TargetSystem.BATON.name(), ROUND_PATH, LinkPurpose.NAVIGATION.name(),
                null, null, NOW.minusSeconds(1), NOW.minusSeconds(60)
        )));

        assertThatThrownBy(() -> service.resolveLink(RAW_CODE))
                .isExactlyInstanceOf(StoredTargetPolicyViolationException.class);

        verifyNoInteractions(targetUrlPort);
    }

    @Test
    @DisplayName("없는 공개 코드는 링크 존재 여부를 드러내지 않는다")
    void hidesMissingLink() {
        when(repository.findByCodeHash(CODE_HASH)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveLink(RAW_CODE))
                .isExactlyInstanceOf(LinkNotFoundException.class);

        verifyNoInteractions(targetUrlPort);
    }

    @Test
    @DisplayName("22자 Base64 URL 형식이 아닌 공개 코드는 DB 조회 전에 없는 링크로 거부한다")
    void rejectsMalformedCodeBeforeLookup() {
        assertThatThrownBy(() -> service.resolveLink("short"))
                .isExactlyInstanceOf(LinkNotFoundException.class);
        assertThatThrownBy(() -> service.resolveLink("a".repeat(21) + "/"))
                .isExactlyInstanceOf(LinkNotFoundException.class);

        verifyNoInteractions(repository, targetUrlPort);
    }
}
