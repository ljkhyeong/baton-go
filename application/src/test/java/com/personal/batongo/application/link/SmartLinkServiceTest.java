package com.personal.batongo.application.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreateLinkCommand;
import com.personal.batongo.application.link.port.out.IssuedLinkCode;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.application.link.port.out.TargetUrlPort;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.SmartLink;
import com.personal.batongo.domain.link.TargetSystem;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SmartLinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-29T10:00:00Z");
    private static final String RAW_CODE = "abcdefghijklmnopqrstuv";
    private static final String CODE_HASH = "b".repeat(64);

    private final InMemoryRepository repository = new InMemoryRepository();
    private final LinkCodePort linkCodePort = new FixedLinkCodePort();
    private final TargetUrlPort targetUrlPort =
            (targetSystem, targetPath) -> URI.create("https://baton.example" + targetPath);
    private final SmartLinkService service = new SmartLinkService(
            repository,
            linkCodePort,
            targetUrlPort,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    @DisplayName("링크 생성은 원문 코드와 해시 저장을 분리한다")
    void separatesRawCodeFromStoredHash() {
        var created = service.createLink(new CreateLinkCommand(
                TargetSystem.BATON,
                "/teams/team-1",
                LinkPurpose.NAVIGATION,
                null,
                NOW.plusSeconds(300)
        ));

        SmartLink stored = repository.findById(created.link().id()).orElseThrow();
        assertThat(created.rawCode()).isEqualTo(RAW_CODE);
        assertThat(stored.getCodeHash()).isEqualTo(CODE_HASH);
        assertThat(stored.getCodeHash()).doesNotContain(RAW_CODE);
    }

    @Test
    @DisplayName("활성 링크 해석은 신뢰 대상 adapter가 만든 URL을 반환한다")
    void resolvesThroughTrustedTargetPort() {
        var created = service.createLink(new CreateLinkCommand(
                TargetSystem.BATON,
                "/roles/role-1",
                LinkPurpose.RESOURCE_OPEN,
                null,
                null
        ));

        var resolved = service.resolveLink(created.rawCode());

        assertThat(resolved.id()).isEqualTo(created.link().id());
        assertThat(resolved.destination()).isEqualTo(URI.create("https://baton.example/roles/role-1"));
    }

    @Test
    @DisplayName("없는 공개 코드는 링크 존재 여부를 드러내지 않고 찾을 수 없음으로 끝난다")
    void hidesMissingLink() {
        assertThatThrownBy(() -> service.resolveLink(RAW_CODE))
                .isInstanceOf(LinkNotFoundException.class)
                .hasMessage("링크를 찾을 수 없습니다");
    }

    private static final class FixedLinkCodePort implements LinkCodePort {

        @Override
        public IssuedLinkCode issue() {
            return new IssuedLinkCode(RAW_CODE, CODE_HASH);
        }

        @Override
        public String hash(String rawCode) {
            return CODE_HASH;
        }
    }

    private static final class InMemoryRepository implements SmartLinkRepository {

        private final Map<UUID, SmartLink> links = new HashMap<>();

        @Override
        public SmartLink save(SmartLink smartLink) {
            links.put(smartLink.getId(), smartLink);
            return smartLink;
        }

        @Override
        public Optional<SmartLink> findById(UUID id) {
            return Optional.ofNullable(links.get(id));
        }

        @Override
        public Optional<SmartLink> findByIdForUpdate(UUID id) {
            return findById(id);
        }

        @Override
        public Optional<SmartLink> findByCodeHash(String codeHash) {
            return links.values().stream()
                    .filter(link -> link.getCodeHash().equals(codeHash))
                    .findFirst();
        }
    }
}
