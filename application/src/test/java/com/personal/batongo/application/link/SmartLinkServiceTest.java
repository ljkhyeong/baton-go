package com.personal.batongo.application.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.application.link.error.IdempotencyKeyConflictException;
import com.personal.batongo.application.link.error.InvalidCreationTimeException;
import com.personal.batongo.application.link.error.InvalidIdempotencyKeyException;
import com.personal.batongo.application.link.error.LinkCodeKeyBindingException;
import com.personal.batongo.application.link.error.LinkCodeReplayMismatchException;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.error.StoredTargetPolicyViolationException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreateLinkCommand;
import com.personal.batongo.application.link.port.out.IssuedLinkCode;
import com.personal.batongo.application.link.port.out.LinkCodeKeyGuardPort;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import com.personal.batongo.application.link.port.out.LinkCreationReservationPort;
import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkReplay;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkResolution;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkSnapshot;
import com.personal.batongo.application.link.port.out.TargetUrlPort;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.LinkValidationException;
import com.personal.batongo.domain.link.SmartLink;
import com.personal.batongo.domain.link.TargetSystem;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
    private static final String BATON_PATH =
            "/teams/8e448211-66ae-44ab-9888-c4960648c22b"
                    + "/seasons/713d9cb7-2842-4f9f-b3cc-e31d98c6238a";
    private static final String OTHER_BATON_PATH =
            "/teams/8e448211-66ae-44ab-9888-c4960648c22b"
                    + "/seasons/27e436c8-e696-4477-9fa2-45e4cf37a942";
    private static final String ROUND_PATH = "/room/abcd-efgh-jkmn";
    private static final LinkCodeDerivationIdentity DERIVATION_IDENTITY =
            new LinkCodeDerivationIdentity(
                    "hmac-sha256-link-code-v1",
                    "a".repeat(64)
            );
    private static final CreationIdempotencyKey IDEMPOTENCY_KEY =
            new CreationIdempotencyKey("8e448211-66ae-44ab-9888-c4960648c22b");

    private final InMemoryRepository repository = new InMemoryRepository();
    private final InMemoryReservationPort reservationPort = new InMemoryReservationPort();
    private final LinkCodePort linkCodePort = new FixedLinkCodePort();
    private final InMemoryLinkCodeKeyGuardPort keyGuardPort =
            new InMemoryLinkCodeKeyGuardPort(DERIVATION_IDENTITY);
    private final LinkCodeKeyGuard linkCodeKeyGuard =
            new LinkCodeKeyGuard(linkCodePort, keyGuardPort);
    private final RecordingTargetUrlPort targetUrlPort = new RecordingTargetUrlPort();
    private final SmartLinkService service = new SmartLinkService(
            repository,
            reservationPort,
            linkCodePort,
            linkCodeKeyGuard,
            targetUrlPort,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    @DisplayName("링크 생성은 원문 코드와 해시 저장을 분리한다")
    void separatesRawCodeFromStoredHash() {
        var created = service.createLink(new CreateLinkCommand(
                IDEMPOTENCY_KEY,
                TargetSystem.BATON,
                BATON_PATH,
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
    @DisplayName("비허용 대상 생성은 예약과 저장소 접근 전에 거부한다")
    void rejectsInvalidTargetBeforeReservationAndRepository() {
        assertThatThrownBy(() -> service.createLink(new CreateLinkCommand(
                IDEMPOTENCY_KEY,
                TargetSystem.BATON,
                ROUND_PATH,
                LinkPurpose.NAVIGATION,
                null,
                null
        )))
                .isInstanceOf(LinkValidationException.class);

        assertThat(reservationPort.reservations).isEmpty();
        assertThat(repository.links).isEmpty();
        assertThat(repository.saveCalls).isZero();
        assertThat(repository.findByIdCalls).isZero();
        assertThat(repository.replayLookupCalls).isZero();
        assertThat(repository.resolutionLookupCalls).isZero();
    }

    @Test
    @DisplayName("저장할 수 없는 생성 시각은 멱등성 예약과 링크 저장 전에 거부한다")
    void rejectsUnstorableCreationTimeBeforeReservationAndRepository() {
        CreateLinkCommand command = new CreateLinkCommand(
                IDEMPOTENCY_KEY,
                TargetSystem.BATON,
                BATON_PATH,
                LinkPurpose.NAVIGATION,
                null,
                CreationTimeStoragePolicy.MAXIMUM.plusSeconds(1)
        );

        assertThatThrownBy(() -> service.createLink(command))
                .isExactlyInstanceOf(InvalidCreationTimeException.class);

        assertThat(reservationPort.reservations).isEmpty();
        assertThat(repository.links).isEmpty();
        assertThat(repository.saveCalls).isZero();
        assertThat(repository.replayLookupCalls).isZero();
    }

    @Test
    @DisplayName("과거 UUID 표기는 기존 예약과 생성 요청이 일치할 때만 재생한다")
    void replaysHistoricallyAcceptedIdempotencyKeyOnlyForExistingReservation() {
        String legacyHeader = "00000000-0000-7000-8000-00000000000A";
        CreationIdempotencyKey legacyKey = CreationIdempotencyKey.parseRequest(legacyHeader);
        UUID linkId = UUID.fromString("d3014090-bd91-4bfc-8a42-89b7f1800c32");
        reservationPort.reservations.put(
                linkCodePort.hashIdempotencyKey(legacyKey.value()),
                linkId
        );
        repository.save(SmartLink.create(
                linkId,
                CODE_HASH,
                TargetSystem.ROUND,
                ROUND_PATH,
                LinkPurpose.MEETING_ENTRY,
                null,
                null,
                NOW
        ));

        var replay = service.createLink(new CreateLinkCommand(
                legacyKey,
                TargetSystem.ROUND,
                ROUND_PATH,
                LinkPurpose.MEETING_ENTRY,
                null,
                null
        ));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.link().id()).isEqualTo(linkId);
        assertThat(repository.saveCalls).isOne();
        assertThat(reservationPort.reserveCalls).isZero();
        assertThat(reservationPort.lookupCalls).isOne();
    }

    @Test
    @DisplayName("과거 UUID 표기는 기존 예약이 없으면 새 링크를 만들지 않고 거부한다")
    void rejectsHistoricallyAcceptedIdempotencyKeyWithoutExistingReservation() {
        CreationIdempotencyKey legacyKey = CreationIdempotencyKey.parseRequest(
                "00000000-0000-7000-8000-00000000000A"
        );

        assertThatThrownBy(() -> service.createLink(new CreateLinkCommand(
                legacyKey,
                TargetSystem.ROUND,
                ROUND_PATH,
                LinkPurpose.MEETING_ENTRY,
                null,
                null
        )))
                .isExactlyInstanceOf(InvalidIdempotencyKeyException.class);

        assertThat(reservationPort.reservations).isEmpty();
        assertThat(reservationPort.reserveCalls).isZero();
        assertThat(reservationPort.lookupCalls).isOne();
        assertThat(repository.saveCalls).isZero();
        assertThat(repository.replayLookupCalls).isZero();
    }

    @Test
    @DisplayName("과거 나노초 시각은 저장된 마이크로초 요청과 일치할 때만 재생한다")
    void replaysHistoricalSubMicrosecondTimeOnlyForExistingReservation() {
        Instant historicalTime = Instant.parse("2026-07-29T10:05:00.123456789Z");
        Instant storedTime = historicalTime.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        CreateLinkCommand storableCommand = new CreateLinkCommand(
                IDEMPOTENCY_KEY,
                TargetSystem.ROUND,
                ROUND_PATH,
                LinkPurpose.MEETING_ENTRY,
                null,
                storedTime
        );
        var created = service.createLink(storableCommand);

        var replay = service.createLink(new CreateLinkCommand(
                IDEMPOTENCY_KEY,
                TargetSystem.ROUND,
                ROUND_PATH,
                LinkPurpose.MEETING_ENTRY,
                null,
                historicalTime
        ));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.link().id()).isEqualTo(created.link().id());
        assertThat(replay.link().expiresAt()).isEqualTo(storedTime);
        assertThat(reservationPort.reserveCalls).isOne();
        assertThat(reservationPort.lookupCalls).isOne();
        assertThat(repository.saveCalls).isOne();
    }

    @Test
    @DisplayName("과거 나노초 시각은 기존 예약이 없으면 새 링크를 만들지 않고 거부한다")
    void rejectsHistoricalSubMicrosecondTimeWithoutExistingReservation() {
        Instant historicalTime = Instant.parse("2026-07-29T10:05:00.123456789Z");

        assertThatThrownBy(() -> service.createLink(new CreateLinkCommand(
                IDEMPOTENCY_KEY,
                TargetSystem.ROUND,
                ROUND_PATH,
                LinkPurpose.MEETING_ENTRY,
                null,
                historicalTime
        )))
                .isExactlyInstanceOf(InvalidCreationTimeException.class);

        assertThat(reservationPort.reservations).isEmpty();
        assertThat(reservationPort.reserveCalls).isZero();
        assertThat(reservationPort.lookupCalls).isOne();
        assertThat(repository.saveCalls).isZero();
        assertThat(repository.replayLookupCalls).isZero();
    }

    @Test
    @DisplayName("과거 UUID와 나노초 시각이 겹쳐도 키 오류로 끝나며 새 예약을 만들지 않는다")
    void preservesLegacyKeyErrorPriorityAcrossReplayOnlyRules() {
        CreationIdempotencyKey legacyKey = CreationIdempotencyKey.parseRequest(
                "00000000-0000-7000-8000-00000000000A"
        );

        assertThatThrownBy(() -> service.createLink(new CreateLinkCommand(
                legacyKey,
                TargetSystem.ROUND,
                ROUND_PATH,
                LinkPurpose.MEETING_ENTRY,
                null,
                Instant.parse("2026-07-29T10:05:00.123456789Z")
        )))
                .isExactlyInstanceOf(InvalidIdempotencyKeyException.class);

        assertThat(reservationPort.reservations).isEmpty();
        assertThat(reservationPort.reserveCalls).isZero();
        assertThat(reservationPort.lookupCalls).isOne();
        assertThat(repository.saveCalls).isZero();
        assertThat(repository.replayLookupCalls).isZero();
    }

    @Test
    @DisplayName("같은 멱등성 키와 요청은 현재 폐기 상태를 포함한 동일 링크를 다시 반환한다")
    void replaysSameCreation() {
        CreateLinkCommand command = new CreateLinkCommand(
                IDEMPOTENCY_KEY,
                TargetSystem.ROUND,
                ROUND_PATH,
                LinkPurpose.MEETING_ENTRY,
                null,
                NOW.plusSeconds(300)
        );

        var first = service.createLink(command);
        service.revokeLink(first.link().id());
        var replay = service.createLink(command);

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.link().id()).isEqualTo(first.link().id());
        assertThat(replay.rawCode()).isEqualTo(first.rawCode());
        assertThat(replay.link().revokedAt()).isEqualTo(NOW);
        assertThat(repository.replayLookupCalls).isOne();
        assertThat(repository.links).hasSize(1);
    }

    @Test
    @DisplayName("재생 projection은 알 수 없거나 공백이 붙은 저장 enum을 보정하거나 노출하지 않는다")
    void rejectsUnsafeStoredEnumsWithoutNormalizationOrExposure() {
        CreateLinkCommand command = new CreateLinkCommand(
                IDEMPOTENCY_KEY,
                TargetSystem.ROUND,
                ROUND_PATH,
                LinkPurpose.MEETING_ENTRY,
                null,
                NOW.plusSeconds(300)
        );
        var first = service.createLink(command);
        String rawTargetSystem = "ROUND_LEGACY";
        String rawTargetPath = "/room/private-credential";
        StoredLinkReplay unknownEnumReplay = new StoredLinkReplay(
                first.link().id(),
                rawTargetSystem,
                rawTargetPath,
                LinkPurpose.MEETING_ENTRY.name(),
                CODE_HASH,
                null,
                NOW.plusSeconds(300),
                null,
                NOW
        );
        repository.storeReplay(unknownEnumReplay);

        assertThat(unknownEnumReplay.toString())
                .isEqualTo("StoredLinkReplay[id=" + first.link().id() + "]")
                .doesNotContain(rawTargetSystem, rawTargetPath, CODE_HASH);
        assertThatThrownBy(() -> service.createLink(command))
                .isExactlyInstanceOf(IdempotencyKeyConflictException.class)
                .hasMessageNotContaining(rawTargetSystem)
                .hasMessageNotContaining(rawTargetPath)
                .hasMessageNotContaining(CODE_HASH);

        String rawPurpose = "MEETING_ENTRY ";
        repository.storeReplay(new StoredLinkReplay(
                first.link().id(),
                TargetSystem.ROUND.name(),
                ROUND_PATH,
                rawPurpose,
                CODE_HASH,
                null,
                NOW.plusSeconds(300),
                null,
                NOW
        ));

        assertThatThrownBy(() -> service.createLink(command))
                .isExactlyInstanceOf(IdempotencyKeyConflictException.class)
                .hasMessageNotContaining(rawPurpose)
                .hasMessageNotContaining(ROUND_PATH)
                .hasMessageNotContaining(CODE_HASH);
        assertThat(repository.findByIdCalls).isZero();
        assertThat(repository.replayLookupCalls).isEqualTo(2);
    }

    @Test
    @DisplayName("현재 파생 결과가 저장된 코드 해시와 다르면 생성 요청을 재생하지 않는다")
    void rejectsReplayWhenCodeDerivationChanges() {
        CreateLinkCommand command = new CreateLinkCommand(
                IDEMPOTENCY_KEY,
                TargetSystem.ROUND,
                ROUND_PATH,
                LinkPurpose.MEETING_ENTRY,
                null,
                NOW.plusSeconds(300)
        );
        var first = service.createLink(command);
        LinkCodePort changedDerivationPort =
                new FixedLinkCodePort("differentRawCodeValue1", "d".repeat(64));
        SmartLinkService changedSecretService = new SmartLinkService(
                repository,
                reservationPort,
                changedDerivationPort,
                new LinkCodeKeyGuard(changedDerivationPort, keyGuardPort),
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
    @DisplayName("미결합 HMAC 키는 생성 중 자동 결합하지 않고 예약 전에 요청을 차단한다")
    void rejectsUnboundKeyBeforeReservation() {
        LinkCodeKeyGuard failingGuard = new LinkCodeKeyGuard(
                linkCodePort,
                new InMemoryLinkCodeKeyGuardPort(null)
        );
        SmartLinkService guardedService = new SmartLinkService(
                repository,
                reservationPort,
                linkCodePort,
                failingGuard,
                targetUrlPort,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> guardedService.createLink(new CreateLinkCommand(
                IDEMPOTENCY_KEY,
                TargetSystem.BATON,
                BATON_PATH,
                LinkPurpose.NAVIGATION,
                null,
                null
        )))
                .isInstanceOf(LinkCodeKeyBindingException.class)
                .hasMessage("링크 코드 파생 키를 현재 데이터베이스에 안전하게 결합할 수 없습니다")
                .hasMessageNotContaining(DERIVATION_IDENTITY.hmacFingerprint());

        assertThat(reservationPort.reservations).isEmpty();
        assertThat(repository.links).isEmpty();
    }

    @Test
    @DisplayName("링크가 만료된 뒤 생성 요청을 재시도해도 기존 응답을 재생한다")
    void replaysCreationAfterExpiry() {
        CreateLinkCommand command = new CreateLinkCommand(
                IDEMPOTENCY_KEY,
                TargetSystem.BATON,
                BATON_PATH,
                LinkPurpose.NAVIGATION,
                null,
                NOW.plusSeconds(60)
        );
        var first = service.createLink(command);
        SmartLinkService expiredClockService = new SmartLinkService(
                repository,
                reservationPort,
                linkCodePort,
                linkCodeKeyGuard,
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
                BATON_PATH,
                LinkPurpose.NAVIGATION,
                null,
                NOW.plusSeconds(300)
        ));

        assertThatThrownBy(() -> service.createLink(new CreateLinkCommand(
                IDEMPOTENCY_KEY,
                TargetSystem.BATON,
                OTHER_BATON_PATH,
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
                BATON_PATH,
                LinkPurpose.NAVIGATION,
                null,
                null
        ));

        var resolved = service.resolveLink(created.rawCode());

        assertThat(resolved.id()).isEqualTo(created.link().id());
        assertThat(resolved.destination()).isEqualTo(URI.create("https://baton.example" + BATON_PATH));
        assertThat(targetUrlPort.calls).isEqualTo(1);
        assertThat(targetUrlPort.lastTargetSystem).isEqualTo(TargetSystem.BATON);
        assertThat(targetUrlPort.lastTargetPath).isEqualTo(BATON_PATH);
    }

    @Test
    @DisplayName("저장된 known enum 대상이 정책을 위반하면 수명주기보다 먼저 숨긴다")
    void hidesKnownStoredPolicyViolationBeforeLifecycleCheck() {
        UUID storedLinkId = UUID.fromString("de76ea51-f895-49bc-b345-30f429ebf4cc");
        repository.storeResolution(CODE_HASH, new StoredLinkResolution(
                storedLinkId,
                TargetSystem.BATON.name(),
                ROUND_PATH,
                LinkPurpose.NAVIGATION.name(),
                NOW.plusSeconds(60),
                NOW.plusSeconds(120),
                null
        ));

        assertThatThrownBy(() -> service.resolveLink(RAW_CODE))
                .isExactlyInstanceOf(StoredTargetPolicyViolationException.class)
                .extracting(exception ->
                        ((StoredTargetPolicyViolationException) exception).linkId())
                .isEqualTo(storedLinkId);

        assertThat(repository.resolutionLookupCalls).isEqualTo(1);
        assertThat(targetUrlPort.calls).isZero();
    }

    @Test
    @DisplayName("저장된 unknown enum 표식은 수명주기보다 먼저 존재를 숨긴다")
    void hidesUnknownStoredEnumBeforeLifecycleCheck() {
        UUID storedLinkId = UUID.fromString("922280cf-58fb-44d7-bb71-46c894878e3f");
        repository.storeResolution(CODE_HASH, new StoredLinkResolution(
                storedLinkId,
                "BATON_LEGACY",
                BATON_PATH,
                LinkPurpose.NAVIGATION.name(),
                NOW.plusSeconds(60),
                NOW.plusSeconds(120),
                null
        ));

        assertThatThrownBy(() -> service.resolveLink(RAW_CODE))
                .isExactlyInstanceOf(StoredTargetPolicyViolationException.class)
                .extracting(exception ->
                        ((StoredTargetPolicyViolationException) exception).linkId())
                .isEqualTo(storedLinkId);

        assertThat(repository.resolutionLookupCalls).isEqualTo(1);
        assertThat(targetUrlPort.calls).isZero();
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
                BATON_PATH,
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
                linkCodeKeyGuard,
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

    @Test
    @DisplayName("관리 조회와 폐기는 저장된 비허용 대상을 찾을 수 없음으로 숨긴다")
    void hidesStoredTargetPolicyViolationFromManagementOperations() {
        UUID linkId = UUID.fromString("de76ea51-f895-49bc-b345-30f429ebf4cc");
        repository.storeSnapshot(new StoredLinkSnapshot(
                linkId,
                TargetSystem.BATON.name(),
                ROUND_PATH,
                LinkPurpose.NAVIGATION.name(),
                null,
                null,
                null,
                NOW.minusSeconds(60),
                3L,
                false
        ));

        assertThatThrownBy(() -> service.getLink(linkId))
                .isExactlyInstanceOf(LinkNotFoundException.class);
        assertThatThrownBy(() -> service.revokeLink(linkId))
                .isExactlyInstanceOf(LinkNotFoundException.class);

        assertThat(repository.revokeStoredCalls).isZero();
    }

    @Test
    @DisplayName("관리 조회와 폐기는 저장된 알 수 없는 enum을 찾을 수 없음으로 숨긴다")
    void hidesUnknownStoredEnumFromManagementOperations() {
        UUID linkId = UUID.fromString("922280cf-58fb-44d7-bb71-46c894878e3f");
        repository.storeSnapshot(new StoredLinkSnapshot(
                linkId,
                "BATON_LEGACY",
                BATON_PATH,
                LinkPurpose.NAVIGATION.name(),
                null,
                null,
                null,
                NOW.minusSeconds(60),
                4L,
                true
        ));

        assertThatThrownBy(() -> service.getLink(linkId))
                .isExactlyInstanceOf(LinkNotFoundException.class);
        assertThatThrownBy(() -> service.revokeLink(linkId))
                .isExactlyInstanceOf(LinkNotFoundException.class);

        assertThat(repository.revokeStoredCalls).isZero();
    }

    @Test
    @DisplayName("관리 폐기는 서버 시각이 생성 시각보다 빠르면 저장하지 않는다")
    void rejectsManagedRevocationBeforeCreationTime() {
        UUID linkId = UUID.fromString("7b9358c1-cb15-4b06-b21c-1d3e4ee889ea");
        repository.storeSnapshot(new StoredLinkSnapshot(
                linkId,
                TargetSystem.BATON.name(),
                BATON_PATH,
                LinkPurpose.NAVIGATION.name(),
                null,
                null,
                null,
                NOW.plusSeconds(1),
                0L,
                true
        ));

        assertThatThrownBy(() -> service.revokeLink(linkId))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("폐기 시각은 생성 시각보다 빠를 수 없습니다");

        assertThat(repository.revokeStoredCalls).isZero();
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
        public LinkCodeDerivationIdentity derivationIdentity() {
            return DERIVATION_IDENTITY;
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

    private static final class InMemoryLinkCodeKeyGuardPort
            implements LinkCodeKeyGuardPort {

        private LinkCodeDerivationIdentity storedIdentity;

        private InMemoryLinkCodeKeyGuardPort(
                LinkCodeDerivationIdentity storedIdentity
        ) {
            this.storedIdentity = storedIdentity;
        }

        @Override
        public void verifyOrBind(LinkCodeDerivationIdentity identity) {
            if (storedIdentity == null) {
                storedIdentity = identity;
                return;
            }
            if (!storedIdentity.matches(identity)) {
                throw new LinkCodeKeyBindingException();
            }
        }

        @Override
        public void verifyBound(LinkCodeDerivationIdentity identity) {
            if (storedIdentity == null || !storedIdentity.matches(identity)) {
                throw new LinkCodeKeyBindingException();
            }
        }
    }

    private static final class InMemoryReservationPort
            implements LinkCreationReservationPort {

        private final Map<String, UUID> reservations = new HashMap<>();
        private int lookupCalls;
        private int reserveCalls;

        @Override
        public Optional<UUID> findLinkId(String idempotencyKeyHash) {
            lookupCalls++;
            return Optional.ofNullable(reservations.get(idempotencyKeyHash));
        }

        @Override
        public Reservation reserve(
                String idempotencyKeyHash,
                UUID proposedLinkId,
                Instant createdAt
        ) {
            reserveCalls++;
            UUID existing = reservations.putIfAbsent(idempotencyKeyHash, proposedLinkId);
            return existing == null
                    ? new Reservation(proposedLinkId, true)
                    : new Reservation(existing, false);
        }
    }

    private static final class RecordingTargetUrlPort implements TargetUrlPort {

        private int calls;
        private TargetSystem lastTargetSystem;
        private String lastTargetPath;

        @Override
        public URI resolve(TargetSystem targetSystem, String targetPath) {
            calls++;
            lastTargetSystem = targetSystem;
            lastTargetPath = targetPath;
            return URI.create("https://baton.example" + targetPath);
        }
    }

    private static final class InMemoryRepository implements SmartLinkRepository {

        private final Map<UUID, SmartLink> links = new HashMap<>();
        private final Map<UUID, StoredLinkReplay> storedReplays = new HashMap<>();
        private final Map<String, StoredLinkResolution> storedResolutions = new HashMap<>();
        private final Map<UUID, StoredLinkSnapshot> storedSnapshots = new HashMap<>();
        private final Map<UUID, Long> versions = new HashMap<>();
        private final Set<UUID> lockedLinkIds = new HashSet<>();
        private int saveCalls;
        private int findByIdCalls;
        private int replayLookupCalls;
        private int resolutionLookupCalls;
        private int revokeStoredCalls;

        @Override
        public SmartLink save(SmartLink smartLink) {
            saveCalls++;
            links.put(smartLink.getId(), smartLink);
            versions.putIfAbsent(smartLink.getId(), 0L);
            return smartLink;
        }

        public Optional<SmartLink> findById(UUID id) {
            findByIdCalls++;
            return Optional.ofNullable(links.get(id));
        }

        @Override
        public Optional<StoredLinkReplay> findReplayById(UUID id) {
            replayLookupCalls++;
            StoredLinkReplay replay = storedReplays.get(id);
            if (replay != null) {
                return Optional.of(replay);
            }
            return Optional.ofNullable(links.get(id)).map(link -> new StoredLinkReplay(
                    link.getId(),
                    link.getTargetSystem().name(),
                    link.getTargetPath(),
                    link.getPurpose().name(),
                    link.getCodeHash(),
                    link.getNotBefore(),
                    link.getExpiresAt(),
                    link.getRevokedAt(),
                    link.getCreatedAt()
            ));
        }

        @Override
        public Optional<StoredLinkResolution> findResolutionByCodeHash(String codeHash) {
            resolutionLookupCalls++;
            StoredLinkResolution storedResolution = storedResolutions.get(codeHash);
            if (storedResolution != null) {
                return Optional.of(storedResolution);
            }
            return links.values().stream()
                    .filter(link -> link.getCodeHash().equals(codeHash))
                    .map(link -> new StoredLinkResolution(
                            link.getId(),
                            link.getTargetSystem().name(),
                            link.getTargetPath(),
                            link.getPurpose().name(),
                            link.getNotBefore(),
                            link.getExpiresAt(),
                            link.getRevokedAt()
                    ))
                    .findFirst();
        }

        @Override
        public Optional<StoredLinkSnapshot> findStoredById(UUID id) {
            StoredLinkSnapshot snapshot = storedSnapshots.get(id);
            if (snapshot != null) {
                return Optional.of(snapshot);
            }
            return Optional.ofNullable(links.get(id)).map(link -> new StoredLinkSnapshot(
                    link.getId(),
                    link.getTargetSystem().name(),
                    link.getTargetPath(),
                    link.getPurpose().name(),
                    link.getNotBefore(),
                    link.getExpiresAt(),
                    link.getRevokedAt(),
                    link.getCreatedAt(),
                    versions.getOrDefault(link.getId(), 0L),
                    true
            ));
        }

        @Override
        public Optional<StoredLinkSnapshot> findStoredByIdForUpdate(UUID id) {
            lockedLinkIds.add(id);
            return findStoredById(id);
        }

        @Override
        public List<StoredLinkSnapshot> scanStoredAfter(UUID afterLinkId, int limit) {
            return links.keySet().stream()
                    .map(this::findStoredById)
                    .flatMap(Optional::stream)
                    .filter(snapshot -> afterLinkId == null
                            || compareUnsigned(snapshot.id(), afterLinkId) > 0)
                    .sorted(Comparator.comparing(
                            StoredLinkSnapshot::id,
                            InMemoryRepository::compareUnsigned
                    ))
                    .limit(limit)
                    .toList();
        }

        @Override
        public boolean revokeStoredIfVersion(
                UUID id,
                long expectedVersion,
                Instant revokedAt
        ) {
            revokeStoredCalls++;
            StoredLinkSnapshot snapshot = storedSnapshots.get(id);
            if (snapshot != null) {
                if (snapshot.version() != expectedVersion || snapshot.revokedAt() != null) {
                    return false;
                }
                storedSnapshots.put(id, new StoredLinkSnapshot(
                        snapshot.id(),
                        snapshot.targetSystem(),
                        snapshot.targetPath(),
                        snapshot.purpose(),
                        snapshot.notBefore(),
                        snapshot.expiresAt(),
                        revokedAt,
                        snapshot.createdAt(),
                        snapshot.version() + 1,
                        snapshot.creationRequestPresent()
                ));
                return true;
            }

            SmartLink link = links.get(id);
            long version = versions.getOrDefault(id, 0L);
            if (link == null || link.getRevokedAt() != null || version != expectedVersion) {
                return false;
            }
            link.revoke(revokedAt);
            versions.put(id, version + 1);
            return true;
        }

        private void storeResolution(
                String codeHash,
                StoredLinkResolution storedResolution
        ) {
            storedResolutions.put(codeHash, storedResolution);
        }

        private void storeReplay(StoredLinkReplay storedLinkReplay) {
            storedReplays.put(storedLinkReplay.id(), storedLinkReplay);
        }

        private void storeSnapshot(StoredLinkSnapshot storedLinkSnapshot) {
            storedSnapshots.put(storedLinkSnapshot.id(), storedLinkSnapshot);
        }

        private static int compareUnsigned(UUID left, UUID right) {
            int mostSignificant = Long.compareUnsigned(
                    left.getMostSignificantBits(),
                    right.getMostSignificantBits()
            );
            return mostSignificant != 0
                    ? mostSignificant
                    : Long.compareUnsigned(
                            left.getLeastSignificantBits(),
                            right.getLeastSignificantBits()
                    );
        }
    }
}
