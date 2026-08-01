package com.personal.batongo.application.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.application.link.error.IdempotencyKeyConflictException;
import com.personal.batongo.application.link.error.LinkCodeReplayMismatchException;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreateLinkCommand;
import com.personal.batongo.application.link.port.out.IssuedLinkCode;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import com.personal.batongo.application.link.port.out.LinkCreationReservationPort;
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
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SmartLinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-29T10:00:00Z");
    private static final String RAW_CODE = "abcdefghijklmnopqrstuv";
    private static final String CODE_HASH = "b".repeat(64);
    private static final CreationIdempotencyKey IDEMPOTENCY_KEY =
            new CreationIdempotencyKey("8e448211-66ae-44ab-9888-c4960648c22b");

    private final InMemoryRepository repository = new InMemoryRepository();
    private final InMemoryReservationPort reservationPort = new InMemoryReservationPort();
    private final LinkCodePort linkCodePort = new FixedLinkCodePort();
    private final TargetUrlPort targetUrlPort =
            (targetSystem, targetPath) -> URI.create("https://baton.example" + targetPath);
    private final SmartLinkService service = new SmartLinkService(
            repository,
            reservationPort,
            linkCodePort,
            targetUrlPort,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    @DisplayName("링크 생성은 원문 코드와 해시 저장을 분리한다")
    void separatesRawCodeFromStoredHash() {
        var created = service.createLink(new CreateLinkCommand(
                IDEMPOTENCY_KEY,
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
        assertThat(created.replayed()).isFalse();
    }

    @Test
    @DisplayName("같은 멱등성 키와 요청은 동일한 링크와 공개 코드를 다시 반환한다")
    void replaysSameCreation() {
        CreateLinkCommand command = new CreateLinkCommand(
                IDEMPOTENCY_KEY,
                TargetSystem.ROUND,
                "/room/abcd-efgh-jkmn",
                LinkPurpose.MEETING_ENTRY,
                null,
                NOW.plusSeconds(300)
        );

        var first = service.createLink(command);
        var replay = service.createLink(command);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.link().id()).isEqualTo(first.link().id());
        assertThat(replay.rawCode()).isEqualTo(first.rawCode());
        assertThat(repository.links).hasSize(1);
    }

    @Test
    @DisplayName("현재 파생 결과가 저장된 코드 해시와 다르면 생성 요청을 재생하지 않는다")
    void rejectsReplayWhenCodeDerivationChanges() {
        CreateLinkCommand command = new CreateLinkCommand(
                IDEMPOTENCY_KEY,
                TargetSystem.ROUND,
                "/room/abcd-efgh-jkmn",
                LinkPurpose.MEETING_ENTRY,
                null,
                NOW.plusSeconds(300)
        );
        var first = service.createLink(command);
        SmartLinkService changedSecretService = new SmartLinkService(
                repository,
                reservationPort,
                new FixedLinkCodePort("differentRawCodeValue1", "d".repeat(64)),
                targetUrlPort,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> changedSecretService.createLink(command))
                .isInstanceOf(LinkCodeReplayMismatchException.class)
                .hasMessage("현재 링크 코드 파생 설정으로 기존 링크를 재생할 수 없습니다");

        SmartLink stored = repository.findById(first.link().id()).orElseThrow();
        assertThat(stored.getCodeHash()).isEqualTo(CODE_HASH);
        assertThat(repository.links).hasSize(1);
    }

    @Test
    @DisplayName("링크가 만료된 뒤 생성 요청을 재시도해도 기존 응답을 재생한다")
    void replaysCreationAfterExpiry() {
        CreateLinkCommand command = new CreateLinkCommand(
                IDEMPOTENCY_KEY,
                TargetSystem.BATON,
                "/teams/team-1",
                LinkPurpose.NAVIGATION,
                null,
                NOW.plusSeconds(60)
        );
        var first = service.createLink(command);
        SmartLinkService expiredClockService = new SmartLinkService(
                repository,
                reservationPort,
                linkCodePort,
                targetUrlPort,
                Clock.fixed(NOW.plusSeconds(120), ZoneOffset.UTC)
        );

        var replay = expiredClockService.createLink(command);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.link().id()).isEqualTo(first.link().id());
        assertThat(replay.rawCode()).isEqualTo(first.rawCode());
    }

    @Test
    @DisplayName("같은 멱등성 키를 다른 요청에 재사용하면 충돌로 거부한다")
    void rejectsReusedKeyWithDifferentRequest() {
        service.createLink(new CreateLinkCommand(
                IDEMPOTENCY_KEY,
                TargetSystem.BATON,
                "/teams/team-1",
                LinkPurpose.NAVIGATION,
                null,
                NOW.plusSeconds(300)
        ));

        assertThatThrownBy(() -> service.createLink(new CreateLinkCommand(
                IDEMPOTENCY_KEY,
                TargetSystem.BATON,
                "/teams/team-2",
                LinkPurpose.NAVIGATION,
                null,
                NOW.plusSeconds(300)
        )))
                .isInstanceOf(IdempotencyKeyConflictException.class)
                .hasMessageContaining("다른 링크 생성 요청");
    }

    @Test
    @DisplayName("활성 링크 해석은 신뢰 대상 adapter가 만든 URL을 반환한다")
    void resolvesThroughTrustedTargetPort() {
        var created = service.createLink(new CreateLinkCommand(
                IDEMPOTENCY_KEY,
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

    @Test
    @DisplayName("관리 조회는 링크 상태를 반환하고 폐기는 잠금으로 최초 시각을 보존한다")
    void getsAndRevokesLinkWithLock() {
        var created = service.createLink(new CreateLinkCommand(
                IDEMPOTENCY_KEY,
                TargetSystem.BATON,
                "/teams/team-1",
                LinkPurpose.NAVIGATION,
                null,
                null
        ));

        var inspected = service.getLink(created.link().id());
        var revoked = service.revokeLink(created.link().id());
        SmartLinkService laterService = new SmartLinkService(
                repository,
                reservationPort,
                linkCodePort,
                targetUrlPort,
                Clock.fixed(NOW.plusSeconds(60), ZoneOffset.UTC)
        );
        var repeatedRevocation = laterService.revokeLink(created.link().id());

        assertThat(inspected.id()).isEqualTo(created.link().id());
        assertThat(revoked.revokedAt()).isEqualTo(NOW);
        assertThat(repeatedRevocation.revokedAt()).isEqualTo(NOW);
        assertThat(repository.lockedLinkIds).containsOnly(created.link().id());
    }

    @Test
    @DisplayName("없는 링크의 관리 조회와 폐기는 찾을 수 없음으로 끝난다")
    void rejectsMissingManagedLink() {
        UUID missingLinkId = UUID.fromString("27e436c8-e696-4477-9fa2-45e4cf37a942");

        assertThatThrownBy(() -> service.getLink(missingLinkId))
                .isInstanceOf(LinkNotFoundException.class);
        assertThatThrownBy(() -> service.revokeLink(missingLinkId))
                .isInstanceOf(LinkNotFoundException.class);
        assertThat(repository.lockedLinkIds).containsOnly(missingLinkId);
    }

    private static final class FixedLinkCodePort implements LinkCodePort {

        private final String rawCode;
        private final String codeHash;

        private FixedLinkCodePort() {
            this(RAW_CODE, CODE_HASH);
        }

        private FixedLinkCodePort(String rawCode, String codeHash) {
            this.rawCode = rawCode;
            this.codeHash = codeHash;
        }

        @Override
        public IssuedLinkCode issue(String idempotencyKey) {
            return new IssuedLinkCode(rawCode, codeHash);
        }

        @Override
        public String hash(String rawCode) {
            return codeHash;
        }

        @Override
        public String hashIdempotencyKey(String idempotencyKey) {
            return "c".repeat(64);
        }
    }

    private static final class InMemoryReservationPort
            implements LinkCreationReservationPort {

        private final Map<String, UUID> reservations = new HashMap<>();

        @Override
        public Reservation reserve(
                String idempotencyKeyHash,
                UUID proposedLinkId,
                Instant createdAt
        ) {
            UUID existing = reservations.putIfAbsent(idempotencyKeyHash, proposedLinkId);
            return existing == null
                    ? new Reservation(proposedLinkId, true)
                    : new Reservation(existing, false);
        }
    }

    private static final class InMemoryRepository implements SmartLinkRepository {

        private final Map<UUID, SmartLink> links = new HashMap<>();
        private final Set<UUID> lockedLinkIds = new HashSet<>();

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
            lockedLinkIds.add(id);
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
