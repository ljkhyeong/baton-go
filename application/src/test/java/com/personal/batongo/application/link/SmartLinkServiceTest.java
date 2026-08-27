package com.personal.batongo.application.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.personal.batongo.application.link.error.InvalidCreationTimeException;
import com.personal.batongo.application.link.error.InvalidIdempotencyKeyException;
import com.personal.batongo.application.link.error.LinkCodeReplayMismatchException;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.error.StoredTargetPolicyViolationException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreateLinkCommand;
import com.personal.batongo.application.link.port.out.IssuedLinkCode;
import com.personal.batongo.application.link.port.out.LinkCodeKeyGuardPort;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import com.personal.batongo.application.link.port.out.LinkCreationReservationPort;
import com.personal.batongo.application.link.port.out.PublicLinkOriginPort;
import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkReplay;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkResolution;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkSnapshot;
import com.personal.batongo.application.link.port.out.TargetUrlPort;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.LinkValidationException;
import com.personal.batongo.domain.link.TargetSystem;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SmartLinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-29T10:00:00Z");
    private static final Instant MAXIMUM_SUPPORTED_TIME =
            Instant.parse("9999-12-31T23:59:59.999999Z");
    private static final UUID LINK_ID = UUID.fromString(
            "d3014090-bd91-4bfc-8a42-89b7f1800c32"
    );
    private static final String RAW_CODE = "abcdefghijklmnopqrstuv";
    private static final String CODE_HASH = "b".repeat(64);
    private static final String IDEMPOTENCY_HASH = "c".repeat(64);
    private static final String BATON_PATH =
            "/teams/8e448211-66ae-44ab-9888-c4960648c22b"
                    + "/seasons/713d9cb7-2842-4f9f-b3cc-e31d98c6238a";
    private static final String ROUND_PATH = "/room/abcd-efgh-jkmn";
    private static final LinkCodeDerivationIdentity DERIVATION_IDENTITY =
            new LinkCodeDerivationIdentity(
                    "hmac-sha256-link-code-v1",
                    "a".repeat(64)
            );
    private static final CreationIdempotencyKey IDEMPOTENCY_KEY =
            CreationIdempotencyKey.parseRequest(
                    "8e448211-66ae-44ab-9888-c4960648c22b"
            );
    private static final PublicLinkOrigin PUBLIC_ORIGIN =
            new PublicLinkOrigin(URI.create("https://go.example"));

    private final SmartLinkRepository repository = mock(SmartLinkRepository.class);
    private final LinkCreationReservationPort reservationPort =
            mock(LinkCreationReservationPort.class);
    private final LinkCodePort linkCodePort = mock(LinkCodePort.class);
    private final LinkCodeKeyGuardPort keyGuardPort = mock(LinkCodeKeyGuardPort.class);
    private final PublicLinkOriginPort publicLinkOriginPort = mock(PublicLinkOriginPort.class);
    private final TargetUrlPort targetUrlPort = mock(TargetUrlPort.class);
    private final SmartLinkService service = service(
            linkCodePort,
            publicLinkOriginPort,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @BeforeEach
    void setUp() {
        stubLinkCodePort(linkCodePort, RAW_CODE, CODE_HASH);
        when(publicLinkOriginPort.current()).thenReturn(PUBLIC_ORIGIN);
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

        verifyNoInteractions(
                reservationPort,
                repository,
                keyGuardPort,
                publicLinkOriginPort,
                targetUrlPort
        );
    }

    @Test
    @DisplayName("저장할 수 없는 생성 시각은 외부 포트 호출 전에 거부한다")
    void rejectsUnstorableCreationTimeBeforePortCalls() {
        assertThatThrownBy(() -> service.createLink(new CreateLinkCommand(
                IDEMPOTENCY_KEY,
                TargetSystem.BATON,
                BATON_PATH,
                LinkPurpose.NAVIGATION,
                null,
                MAXIMUM_SUPPORTED_TIME.plusSeconds(1)
        )))
                .isExactlyInstanceOf(InvalidCreationTimeException.class);

        verifyNoInteractions(
                linkCodePort,
                reservationPort,
                repository,
                keyGuardPort,
                publicLinkOriginPort,
                targetUrlPort
        );
    }

    @Test
    @DisplayName("재생 전용 요청은 기존 예약만 조회하고 새 행을 만들지 않는다")
    void keepsReplayOnlyRequestOutOfCreationPorts() {
        assertThatThrownBy(() -> service.createLink(new CreateLinkCommand(
                CreationIdempotencyKey.parseRequest(
                        "8E448211-66AE-44AB-9888-C4960648C22B"
                ),
                TargetSystem.BATON,
                BATON_PATH,
                LinkPurpose.NAVIGATION,
                null,
                null
        ))).isExactlyInstanceOf(InvalidIdempotencyKeyException.class);
        verify(reservationPort).find(IDEMPOTENCY_HASH);
        verify(reservationPort, never()).reserve(anyString(), any(), anyString(), any());
        verifyNoInteractions(repository, keyGuardPort, publicLinkOriginPort, targetUrlPort);
    }

    @Test
    @DisplayName("현재 코드 파생 결과가 저장 해시와 다르면 재생하지 않는다")
    void rejectsReplayWhenCodeDerivationChanges() {
        Instant expiresAt = NOW.plusSeconds(300);
        configureReplay(PUBLIC_ORIGIN.serialized(), expiresAt);
        LinkCodePort changedLinkCodePort = mock(LinkCodePort.class);
        stubLinkCodePort(changedLinkCodePort, "differentRawCodeValue1", "d".repeat(64));

        assertThatThrownBy(() -> service(
                changedLinkCodePort,
                publicLinkOriginPort,
                Clock.fixed(NOW, ZoneOffset.UTC)
        ).createLink(command(expiresAt)))
                .isExactlyInstanceOf(LinkCodeReplayMismatchException.class);
    }

    @Test
    @DisplayName("링크가 만료된 뒤 생성 요청을 재시도해도 기존 응답을 재생한다")
    void replaysCreationAfterExpiry() {
        Instant expiresAt = NOW.plusSeconds(60);
        configureReplay(PUBLIC_ORIGIN.serialized(), expiresAt);

        var replay = service(
                linkCodePort,
                publicLinkOriginPort,
                Clock.fixed(NOW.plusSeconds(120), ZoneOffset.UTC)
        ).createLink(command(expiresAt));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.link().id()).isEqualTo(LINK_ID);
        assertThat(replay.shortUrl())
                .isEqualTo(URI.create("https://go.example/l/" + RAW_CODE));
    }

    @Test
    @DisplayName("활성 링크 해석은 신뢰 대상 포트가 만든 URL을 반환한다")
    void resolvesThroughTrustedTargetPort() {
        StoredLinkResolution resolution = new StoredLinkResolution(
                LINK_ID,
                TargetSystem.BATON.name(),
                BATON_PATH,
                LinkPurpose.NAVIGATION.name(),
                null,
                null,
                null
        );
        URI destination = URI.create("https://baton.example" + BATON_PATH);
        when(repository.findResolutionByCodeHash(CODE_HASH))
                .thenReturn(Optional.of(resolution));
        when(targetUrlPort.resolve(any()))
                .thenReturn(destination);

        var result = service.resolveLink(RAW_CODE);

        assertThat(result.destination()).isEqualTo(destination);
        verify(targetUrlPort).resolve(argThat(target ->
                target.targetSystem() == TargetSystem.BATON
                        && target.targetPath().equals(BATON_PATH)));
    }

    @Test
    @DisplayName("저장된 known enum 대상의 정책 위반은 수명주기보다 먼저 숨긴다")
    void hidesKnownStoredPolicyViolationBeforeLifecycleCheck() {
        when(repository.findResolutionByCodeHash(CODE_HASH)).thenReturn(Optional.of(
                new StoredLinkResolution(
                        LINK_ID,
                        TargetSystem.BATON.name(),
                        ROUND_PATH,
                        LinkPurpose.NAVIGATION.name(),
                        null,
                        null,
                        NOW.minusSeconds(1)
                )
        ));

        assertThatThrownBy(() -> service.resolveLink(RAW_CODE))
                .isExactlyInstanceOf(StoredTargetPolicyViolationException.class);

        verifyNoInteractions(targetUrlPort);
    }

    @Test
    @DisplayName("없는 공개 코드는 링크 존재 여부를 드러내지 않는다")
    void hidesMissingLink() {
        when(repository.findResolutionByCodeHash(CODE_HASH))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveLink(RAW_CODE))
                .isExactlyInstanceOf(LinkNotFoundException.class);

        verifyNoInteractions(targetUrlPort);
    }

    @Test
    @DisplayName("관리 조회와 폐기는 각각 일반 조회와 행 잠금 포트를 사용한다")
    void getsAndRevokesLinkWithLock() {
        StoredLinkSnapshot snapshot = storedSnapshot();
        when(repository.findStoredById(LINK_ID)).thenReturn(Optional.of(snapshot));
        when(repository.findStoredByIdForUpdate(LINK_ID)).thenReturn(Optional.of(snapshot));
        when(repository.revokeStoredIfVersion(LINK_ID, 3L, NOW)).thenReturn(true);

        var found = service.getLink(LINK_ID);
        var revoked = service.revokeLink(LINK_ID);

        assertThat(found.id()).isEqualTo(LINK_ID);
        assertThat(found.revokedAt()).isNull();
        assertThat(revoked.revokedAt()).isEqualTo(NOW);
        verify(repository).findStoredById(LINK_ID);
        verify(repository).findStoredByIdForUpdate(LINK_ID);
        verify(repository).revokeStoredIfVersion(LINK_ID, 3L, NOW);
    }

    @Test
    @DisplayName("관리 폐기의 조건부 갱신 실패는 성공으로 보정하지 않는다")
    void rejectsWhenManagedRevocationUpdateFails() {
        when(repository.findStoredByIdForUpdate(LINK_ID))
                .thenReturn(Optional.of(storedSnapshot()));
        when(repository.revokeStoredIfVersion(LINK_ID, 3L, NOW)).thenReturn(false);

        assertThatThrownBy(() -> service.revokeLink(LINK_ID))
                .isExactlyInstanceOf(IllegalStateException.class);

        verify(repository).findStoredByIdForUpdate(LINK_ID);
        verify(repository).revokeStoredIfVersion(LINK_ID, 3L, NOW);
    }

    @Test
    @DisplayName("없는 링크의 관리 조회와 폐기는 찾을 수 없음으로 끝난다")
    void rejectsMissingManagedLink() {
        when(repository.findStoredById(LINK_ID)).thenReturn(Optional.empty());
        when(repository.findStoredByIdForUpdate(LINK_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getLink(LINK_ID))
                .isExactlyInstanceOf(LinkNotFoundException.class);
        assertThatThrownBy(() -> service.revokeLink(LINK_ID))
                .isExactlyInstanceOf(LinkNotFoundException.class);

        verify(repository).findStoredById(LINK_ID);
        verify(repository).findStoredByIdForUpdate(LINK_ID);
        verify(repository, never()).revokeStoredIfVersion(any(), anyLong(), any());
    }

    private void configureReplay(String publicOrigin, Instant expiresAt) {
        when(reservationPort.reserve(
                eq(IDEMPOTENCY_HASH),
                any(UUID.class),
                anyString(),
                any(Instant.class)
        )).thenReturn(new LinkCreationReservationPort.Reservation(
                LINK_ID,
                publicOrigin,
                false
        ));
        when(repository.findReplayById(LINK_ID)).thenReturn(Optional.of(
                new StoredLinkReplay(
                        LINK_ID,
                        TargetSystem.BATON.name(),
                        BATON_PATH,
                        LinkPurpose.NAVIGATION.name(),
                        CODE_HASH,
                        null,
                        expiresAt,
                        null,
                        NOW
                )
        ));
    }

    private CreateLinkCommand command(Instant expiresAt) {
        return new CreateLinkCommand(
                IDEMPOTENCY_KEY,
                TargetSystem.BATON,
                BATON_PATH,
                LinkPurpose.NAVIGATION,
                null,
                expiresAt
        );
    }

    private StoredLinkSnapshot storedSnapshot() {
        return new StoredLinkSnapshot(
                LINK_ID,
                TargetSystem.BATON.name(),
                BATON_PATH,
                LinkPurpose.NAVIGATION.name(),
                null,
                NOW.plusSeconds(300),
                null,
                NOW.minusSeconds(60),
                3L,
                true
        );
    }

    private SmartLinkService service(
            LinkCodePort configuredLinkCodePort,
            PublicLinkOriginPort configuredPublicOriginPort,
            Clock clock
    ) {
        return new SmartLinkService(
                repository,
                reservationPort,
                configuredLinkCodePort,
                new LinkCodeKeyGuard(configuredLinkCodePort, keyGuardPort),
                configuredPublicOriginPort,
                targetUrlPort,
                clock
        );
    }

    private void stubLinkCodePort(
            LinkCodePort configuredLinkCodePort,
            String rawCode,
            String codeHash
    ) {
        when(configuredLinkCodePort.derivationIdentity()).thenReturn(DERIVATION_IDENTITY);
        when(configuredLinkCodePort.issue(anyString()))
                .thenReturn(new IssuedLinkCode(rawCode, codeHash));
        when(configuredLinkCodePort.hash(anyString())).thenReturn(codeHash);
        when(configuredLinkCodePort.hashIdempotencyKey(anyString()))
                .thenReturn(IDEMPOTENCY_HASH);
    }
}
