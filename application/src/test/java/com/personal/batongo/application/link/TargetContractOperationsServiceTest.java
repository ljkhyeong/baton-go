package com.personal.batongo.application.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.application.link.error.InvalidTargetContractInventoryRequestException;
import com.personal.batongo.application.link.error.InvalidTargetContractRemediationRequestException;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.error.TargetContractRemediationNotApplicableException;
import com.personal.batongo.application.link.error.TargetContractRemediationStaleException;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.Compliance;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.CreationRequestState;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.InventoryQuery;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.RemediationCommand;
import com.personal.batongo.application.link.port.in.TargetContractOperationsUseCase.RemediationState;
import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkResolution;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkSnapshot;
import com.personal.batongo.domain.link.SmartLink;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TargetContractOperationsServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T01:02:03.123456789Z");
    private static final String BATON_PATH =
            "/teams/8e448211-66ae-44ab-9888-c4960648c22b"
                    + "/seasons/713d9cb7-2842-4f9f-b3cc-e31d98c6238a";
    private static final String ROUND_PATH = "/room/abcd-efgh-jkmn";

    private final InMemoryRepository repository = new InMemoryRepository();
    private final TargetContractOperationsService service = new TargetContractOperationsService(
            repository,
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    @DisplayName("inventory는 raw 저장 행을 분류하고 응답에서 대상 정보를 제외한다")
    void classifiesRawRowsWithoutExposingTargets() {
        StoredLinkSnapshot compliant = snapshot(
                1,
                "BATON",
                BATON_PATH,
                "NAVIGATION",
                null,
                2L,
                true
        );
        StoredLinkSnapshot nonCompliant = snapshot(
                2,
                "BATON",
                ROUND_PATH,
                "NAVIGATION",
                null,
                3L,
                false
        );
        StoredLinkSnapshot unknownRevoked = snapshot(
                3,
                "LEGACY_SYSTEM",
                BATON_PATH,
                "NAVIGATION",
                NOW.minusSeconds(30),
                4L,
                true
        );
        repository.store(compliant, nonCompliant, unknownRevoked);

        var result = service.inventory(new InventoryQuery(null, 2));

        assertThat(result.contractVersion()).isEqualTo("v1");
        assertThat(result.hasMore()).isTrue();
        assertThat(result.nextAfterLinkId()).isEqualTo(nonCompliant.id());
        assertThat(repository.lastLimit).isEqualTo(3);
        assertThat(result.items()).satisfiesExactly(
                item -> {
                    assertThat(item.linkId()).isEqualTo(compliant.id());
                    assertThat(item.compliance()).isEqualTo(Compliance.COMPLIANT);
                    assertThat(item.remediationState()).isEqualTo(RemediationState.NOT_REQUIRED);
                    assertThat(item.creationRequestState()).isEqualTo(CreationRequestState.PRESENT);
                    assertThat(item.version()).isEqualTo(2L);
                },
                item -> {
                    assertThat(item.linkId()).isEqualTo(nonCompliant.id());
                    assertThat(item.compliance()).isEqualTo(Compliance.NON_COMPLIANT);
                    assertThat(item.remediationState()).isEqualTo(RemediationState.UNREVOKED);
                    assertThat(item.creationRequestState()).isEqualTo(CreationRequestState.MISSING);
                    assertThat(item.version()).isEqualTo(3L);
                }
        );
        assertThat(result.toString())
                .doesNotContain(BATON_PATH)
                .doesNotContain(ROUND_PATH)
                .doesNotContain("LEGACY_SYSTEM");
        assertThat(nonCompliant.toString())
                .isEqualTo("StoredLinkSnapshot[id=" + nonCompliant.id() + "]")
                .doesNotContain(ROUND_PATH);

        var lastPage = service.inventory(new InventoryQuery(nonCompliant.id(), 2));
        assertThat(lastPage.items()).singleElement().satisfies(item -> {
            assertThat(item.linkId()).isEqualTo(unknownRevoked.id());
            assertThat(item.compliance()).isEqualTo(Compliance.NON_COMPLIANT);
            assertThat(item.remediationState()).isEqualTo(RemediationState.REVOKED);
        });
        assertThat(lastPage.hasMore()).isFalse();
        assertThat(lastPage.nextAfterLinkId()).isNull();
    }

    @Test
    @DisplayName("inventory는 허용 범위를 벗어난 limit을 거부한다")
    void rejectsInvalidInventoryLimit() {
        assertThatThrownBy(() -> service.inventory(null))
                .isExactlyInstanceOf(InvalidTargetContractInventoryRequestException.class);
        assertThatThrownBy(() -> service.inventory(new InventoryQuery(null, 0)))
                .isExactlyInstanceOf(InvalidTargetContractInventoryRequestException.class);
        assertThatThrownBy(() -> service.inventory(new InventoryQuery(null, 501)))
                .isExactlyInstanceOf(InvalidTargetContractInventoryRequestException.class);
        assertThat(repository.scanCalls).isZero();
    }

    @Test
    @DisplayName("준수 링크 remediation은 적용 불가로 거부한다")
    void rejectsRemediationForCompliantLink() {
        StoredLinkSnapshot compliant = snapshot(
                4,
                "ROUND",
                ROUND_PATH,
                "MEETING_ENTRY",
                null,
                1L,
                true
        );
        repository.store(compliant);

        assertThatThrownBy(() -> service.remediate(
                new RemediationCommand(compliant.id(), 1L)
        )).isExactlyInstanceOf(TargetContractRemediationNotApplicableException.class);

        assertThat(repository.updateCalls).isZero();
    }

    @Test
    @DisplayName("이미 폐기된 위반 링크 remediation은 최초 시각을 보존해 멱등 응답한다")
    void returnsAlreadyRevokedViolationWithoutVersionConflict() {
        Instant firstRevokedAt = NOW.minusSeconds(120);
        StoredLinkSnapshot revoked = snapshot(
                5,
                "UNKNOWN",
                BATON_PATH,
                "NAVIGATION",
                firstRevokedAt,
                7L,
                false
        );
        repository.store(revoked);

        var result = service.remediate(new RemediationCommand(revoked.id(), 999L));

        assertThat(result.linkId()).isEqualTo(revoked.id());
        assertThat(result.contractVersion()).isEqualTo("v1");
        assertThat(result.remediationState()).isEqualTo(RemediationState.REVOKED);
        assertThat(result.revokedAt()).isEqualTo(firstRevokedAt);
        assertThat(result.alreadyRevoked()).isTrue();
        assertThat(repository.updateCalls).isZero();
    }

    @Test
    @DisplayName("미폐기 위반 링크 remediation은 오래된 version을 거부한다")
    void rejectsStaleRemediationVersion() {
        StoredLinkSnapshot violation = snapshot(
                6,
                "BATON",
                ROUND_PATH,
                "NAVIGATION",
                null,
                8L,
                true
        );
        repository.store(violation);

        assertThatThrownBy(() -> service.remediate(
                new RemediationCommand(violation.id(), 7L)
        )).isExactlyInstanceOf(TargetContractRemediationStaleException.class);

        assertThat(repository.updateCalls).isZero();
    }

    @Test
    @DisplayName("미폐기 위반 링크 remediation은 마이크로초 시각과 version 조건으로 폐기한다")
    void revokesViolationAtMicrosecondPrecision() {
        StoredLinkSnapshot violation = snapshot(
                7,
                "ROUND",
                BATON_PATH,
                "MEETING_ENTRY",
                null,
                9L,
                false
        );
        repository.store(violation);

        var result = service.remediate(new RemediationCommand(violation.id(), 9L));

        Instant expectedRevokedAt = Instant.parse("2026-08-03T01:02:03.123456Z");
        assertThat(result.revokedAt()).isEqualTo(expectedRevokedAt);
        assertThat(result.alreadyRevoked()).isFalse();
        assertThat(repository.findStoredById(violation.id()).orElseThrow().version())
                .isEqualTo(10L);
        assertThat(repository.findStoredById(violation.id()).orElseThrow().revokedAt())
                .isEqualTo(expectedRevokedAt);
    }

    @Test
    @DisplayName("remediation 조건부 갱신이 실패하면 오래된 요청으로 거부한다")
    void rejectsWhenConditionalUpdateLosesRace() {
        StoredLinkSnapshot violation = snapshot(
                8,
                "BATON",
                ROUND_PATH,
                "NAVIGATION",
                null,
                2L,
                false
        );
        repository.store(violation);
        repository.forceUpdateFailure = true;

        assertThatThrownBy(() -> service.remediate(
                new RemediationCommand(violation.id(), 2L)
        )).isExactlyInstanceOf(TargetContractRemediationStaleException.class);
    }

    @Test
    @DisplayName("remediation은 잘못된 명령과 없는 링크를 구분해 거부한다")
    void rejectsInvalidCommandAndMissingLink() {
        UUID missingId = uuid(9);

        assertThatThrownBy(() -> service.remediate(null))
                .isExactlyInstanceOf(InvalidTargetContractRemediationRequestException.class);
        assertThatThrownBy(() -> service.remediate(new RemediationCommand(null, 0L)))
                .isExactlyInstanceOf(InvalidTargetContractRemediationRequestException.class);
        assertThatThrownBy(() -> service.remediate(new RemediationCommand(missingId, -1L)))
                .isExactlyInstanceOf(InvalidTargetContractRemediationRequestException.class);
        assertThatThrownBy(() -> service.remediate(new RemediationCommand(missingId, 0L)))
                .isExactlyInstanceOf(LinkNotFoundException.class);
    }

    @Test
    @DisplayName("remediation은 서버 시각이 생성 시각보다 빠르면 저장하지 않는다")
    void rejectsRemediationBeforeCreationTime() {
        StoredLinkSnapshot violation = new StoredLinkSnapshot(
                uuid(10),
                "UNKNOWN",
                BATON_PATH,
                "NAVIGATION",
                null,
                null,
                null,
                NOW.plusSeconds(1),
                0L,
                false
        );
        repository.store(violation);

        assertThatThrownBy(() -> service.remediate(
                new RemediationCommand(violation.id(), 0L)
        )).isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("폐기 시각은 생성 시각보다 빠를 수 없습니다");

        assertThat(repository.updateCalls).isZero();
    }

    private StoredLinkSnapshot snapshot(
            int id,
            String targetSystem,
            String targetPath,
            String purpose,
            Instant revokedAt,
            long version,
            boolean creationRequestPresent
    ) {
        return new StoredLinkSnapshot(
                uuid(id),
                targetSystem,
                targetPath,
                purpose,
                null,
                NOW.plusSeconds(3600),
                revokedAt,
                NOW.minusSeconds(3600),
                version,
                creationRequestPresent
        );
    }

    private static UUID uuid(int value) {
        return UUID.fromString("00000000-0000-4000-8000-%012x".formatted(value));
    }

    private static final class InMemoryRepository implements SmartLinkRepository {

        private final Map<UUID, StoredLinkSnapshot> snapshots = new HashMap<>();
        private int scanCalls;
        private int updateCalls;
        private int lastLimit;
        private boolean forceUpdateFailure;

        private void store(StoredLinkSnapshot... rows) {
            for (StoredLinkSnapshot row : rows) {
                snapshots.put(row.id(), row);
            }
        }

        @Override
        public SmartLink save(SmartLink smartLink) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<SmartLink> findById(UUID id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StoredLinkResolution> findResolutionByCodeHash(String codeHash) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<StoredLinkSnapshot> findStoredById(UUID id) {
            return Optional.ofNullable(snapshots.get(id));
        }

        @Override
        public Optional<StoredLinkSnapshot> findStoredByIdForUpdate(UUID id) {
            return findStoredById(id);
        }

        @Override
        public List<StoredLinkSnapshot> scanStoredAfter(UUID afterLinkId, int limit) {
            scanCalls++;
            lastLimit = limit;
            return snapshots.values().stream()
                    .filter(row -> afterLinkId == null
                            || compareUnsigned(row.id(), afterLinkId) > 0)
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
            updateCalls++;
            if (forceUpdateFailure) {
                return false;
            }
            StoredLinkSnapshot current = snapshots.get(id);
            if (current == null
                    || current.revokedAt() != null
                    || current.version() != expectedVersion) {
                return false;
            }
            snapshots.put(id, new StoredLinkSnapshot(
                    current.id(),
                    current.targetSystem(),
                    current.targetPath(),
                    current.purpose(),
                    current.notBefore(),
                    current.expiresAt(),
                    revokedAt,
                    current.createdAt(),
                    current.version() + 1,
                    current.creationRequestPresent()
            ));
            return true;
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
