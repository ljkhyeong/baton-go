package com.personal.batongo.application.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.personal.batongo.application.link.error.InvalidTargetContractInventoryRequestException;
import com.personal.batongo.application.link.error.InvalidTargetContractRemediationRequestException;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.error.TargetContractRemediationNotApplicableException;
import com.personal.batongo.application.link.error.TargetContractRemediationStaleException;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.InventoryQuery;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.RemediationCommand;
import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkSnapshot;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TargetContractOperationsServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T01:02:03.123456Z");
    private static final UUID LINK_ID = UUID.fromString(
            "00000000-0000-4000-8000-000000000008"
    );
    private static final String ROUND_PATH = "/room/abcd-efgh-jkmn";

    private final SmartLinkRepository repository = mock(SmartLinkRepository.class);
    private final TargetContractOperationsService service = new TargetContractOperationsService(
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    @DisplayName("inventory는 허용 범위를 벗어난 limit을 저장소 호출 전에 거부한다")
    void rejectsInvalidInventoryLimitBeforeScanning() {
        assertThatThrownBy(() -> service.inventory(new InventoryQuery(null, 0)))
                .isExactlyInstanceOf(InvalidTargetContractInventoryRequestException.class);
        assertThatThrownBy(() -> service.inventory(new InventoryQuery(null, 501)))
                .isExactlyInstanceOf(InvalidTargetContractInventoryRequestException.class);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("remediation은 잘못된 명령을 행 잠금 전에 거부한다")
    void rejectsInvalidCommandBeforeLocking() {
        assertThatThrownBy(() -> service.remediate(new RemediationCommand(LINK_ID, -1L)))
                .isExactlyInstanceOf(InvalidTargetContractRemediationRequestException.class);
        assertThatThrownBy(() -> service.remediate(
                new RemediationCommand(LINK_ID, Long.MAX_VALUE)
        )).isExactlyInstanceOf(InvalidTargetContractRemediationRequestException.class);

        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("remediation은 없는 링크를 찾을 수 없음으로 거부한다")
    void rejectsMissingLink() {
        when(repository.findStoredByIdForUpdate(LINK_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.remediate(new RemediationCommand(LINK_ID, 0L)))
                .isExactlyInstanceOf(LinkNotFoundException.class);

        verify(repository).findStoredByIdForUpdate(LINK_ID);
    }

    @Test
    @DisplayName("계약을 준수하는 링크는 remediation 대상이 아니다")
    void rejectsCompliantLink() {
        when(repository.findStoredByIdForUpdate(LINK_ID)).thenReturn(Optional.of(
                new StoredLinkSnapshot(
                        LINK_ID,
                        "ROUND",
                        ROUND_PATH,
                        "MEETING_ENTRY",
                        null,
                        NOW.plusSeconds(3600),
                        null,
                        NOW.minusSeconds(3600),
                        2L,
                        true
                )
        ));

        assertThatThrownBy(() -> service.remediate(new RemediationCommand(LINK_ID, 2L)))
                .isExactlyInstanceOf(TargetContractRemediationNotApplicableException.class);

        verify(repository).findStoredByIdForUpdate(LINK_ID);
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("remediation 조건부 갱신 실패는 오래된 요청으로 거부한다")
    void rejectsWhenConditionalUpdateLosesRace() {
        StoredLinkSnapshot snapshot = new StoredLinkSnapshot(
                LINK_ID,
                "BATON",
                ROUND_PATH,
                "NAVIGATION",
                null,
                NOW.plusSeconds(3600),
                null,
                NOW.minusSeconds(3600),
                2L,
                false
        );
        when(repository.findStoredByIdForUpdate(LINK_ID))
                .thenReturn(Optional.of(snapshot));
        when(repository.revokeStoredIfVersion(eq(LINK_ID), eq(2L), any(Instant.class)))
                .thenReturn(false);

        assertThatThrownBy(() -> service.remediate(new RemediationCommand(LINK_ID, 2L)))
                .isExactlyInstanceOf(TargetContractRemediationStaleException.class);

        ArgumentCaptor<Instant> revokedAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).revokeStoredIfVersion(eq(LINK_ID), eq(2L), revokedAt.capture());
        assertThat(revokedAt.getValue()).isEqualTo(NOW);
    }
}
