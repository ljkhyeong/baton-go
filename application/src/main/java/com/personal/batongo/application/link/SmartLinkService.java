package com.personal.batongo.application.link;

import com.personal.batongo.application.link.error.IdempotencyKeyConflictException;
import com.personal.batongo.application.link.error.InvalidRequestException;
import com.personal.batongo.application.link.error.LinkCodeReplayMismatchException;
import com.personal.batongo.application.link.error.LinkCreationReplayUnavailableException;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.error.LinkPurgedException;
import com.personal.batongo.application.link.error.PublicLinkOriginReplayUnavailableException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.out.IssuedLinkCode;
import com.personal.batongo.application.link.port.out.LinkCodeKeyGuardPort;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import com.personal.batongo.application.link.port.out.LinkCreationReservationPort;
import com.personal.batongo.application.link.port.out.PublicLinkOriginPort;
import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLink;
import com.personal.batongo.domain.link.LinkRevocationPolicy;
import com.personal.batongo.domain.link.SmartLink;
import com.personal.batongo.domain.link.TrustedTarget;
import com.personal.batongo.domain.link.TrustedTargetPolicy;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SmartLinkService implements SmartLinkUseCase {

    private static final int MAX_SEARCH_LIMIT = 500;

    private final SmartLinkRepository repository;
    private final LinkCreationReservationPort reservationPort;
    private final LinkCodePort linkCodePort;
    private final LinkCodeKeyGuardPort keyGuardPort;
    private final PublicLinkOriginPort publicLinkOriginPort;
    private final Clock clock;

    public SmartLinkService(
            SmartLinkRepository repository,
            LinkCreationReservationPort reservationPort,
            LinkCodePort linkCodePort,
            LinkCodeKeyGuardPort keyGuardPort,
            PublicLinkOriginPort publicLinkOriginPort,
            Clock clock
    ) {
        this.repository = repository;
        this.reservationPort = reservationPort;
        this.linkCodePort = linkCodePort;
        this.keyGuardPort = keyGuardPort;
        this.publicLinkOriginPort = publicLinkOriginPort;
        this.clock = clock;
    }

    @Override
    @Transactional
    public CreatedLinkResult createLink(CreateLinkCommand command) {
        CreationRequestAdmissionPolicy.requireStorableTimes(command.notBefore(), command.expiresAt());
        String idempotencyKeyHash = command.idempotencyKey().hash();
        TrustedTarget requestedTarget = TrustedTargetPolicy.requireAllowed(
                command.targetSystem(), command.purpose(), command.targetPath()
        );
        String requestHash = LinkCreationFingerprint.of(
                requestedTarget.targetSystem().name(), requestedTarget.purpose().name(),
                requestedTarget.targetPath(), command.notBefore(), command.expiresAt()
        );
        LinkCodeKeyRingIdentity keyRing = linkCodePort.keyRingIdentity();
        keyGuardPort.verifyBound(keyRing);
        PublicLinkOrigin currentOrigin = publicLinkOriginPort.current();
        Instant now = clock.instant();
        LinkCreationReservationPort.Reservation reservation = reservationPort.reserve(
                idempotencyKeyHash,
                UUID.randomUUID(),
                currentOrigin.serialized(),
                keyRing.activeKeyId(),
                requestHash,
                now
        );
        if (reservation.purgedAt() != null) {
            // 정리된 예약은 같은 요청이면 삭제 안내, 다른 요청이면 키 재사용 충돌로 구분한다.
            throw requestHash.equals(reservation.requestHash())
                    ? new LinkPurgedException()
                    : new IdempotencyKeyConflictException();
        }
        IssuedLinkCode issuedCode = linkCodePort.issue(
                command.idempotencyKey().value(), reservation.keyId()
        );

        if (!reservation.owner()) {
            return replayCreation(reservation, requestedTarget, requestHash, issuedCode);
        }

        repository.save(new SmartLink(
                reservation.linkId(),
                issuedCode.codeHash(),
                requestedTarget,
                command.notBefore(),
                command.expiresAt(),
                now
        ));
        return new CreatedLinkResult(
                new LinkResult(
                        reservation.linkId(),
                        requestedTarget.targetSystem(),
                        requestedTarget.targetPath(),
                        requestedTarget.purpose(),
                        command.notBefore(),
                        command.expiresAt(),
                        null,
                        now,
                        now
                ),
                currentOrigin.shortUrl(issuedCode.rawCode()),
                false
        );
    }

    private CreatedLinkResult replayCreation(
            LinkCreationReservationPort.Reservation reservation,
            TrustedTarget requestedTarget,
            String requestHash,
            IssuedLinkCode issuedCode
    ) {
        StoredLink existing = repository.findById(reservation.linkId())
                .orElseThrow(() -> new LinkCreationReplayUnavailableException(
                        reservation.linkId()
                ));
        String storedHash = LinkCreationFingerprint.of(existing.targetSystem(), existing.purpose(),
                existing.targetPath(), existing.notBefore(), existing.expiresAt());
        if (!requestHash.equals(storedHash)) {
            throw new IdempotencyKeyConflictException();
        }
        if (!existing.codeHash().equals(issuedCode.codeHash())) {
            throw new LinkCodeReplayMismatchException();
        }
        PublicLinkOrigin storedOrigin = requireReplayOrigin(reservation.publicOrigin());
        return new CreatedLinkResult(
                toResult(existing, requestedTarget, existing.revokedAt(), clock.instant()),
                storedOrigin.shortUrl(issuedCode.rawCode()),
                true
        );
    }

    private PublicLinkOrigin requireReplayOrigin(String storedOrigin) {
        // 정리 전 예약의 공개 출처는 DB 제약으로 항상 저장되며, 정규 형식이 아니면 현재 설정으로 대체하지 않는다.
        try {
            return PublicLinkOrigin.fromStored(storedOrigin);
        } catch (IllegalArgumentException exception) {
            throw new PublicLinkOriginReplayUnavailableException();
        }
    }

    @Override
    public LinkResult getLink(UUID linkId) {
        StoredLink storedLink = repository.findById(linkId)
                .orElseThrow(LinkNotFoundException::new);
        TrustedTarget target = storedLink.trustedTarget().orElseThrow(LinkNotFoundException::new);
        return toResult(storedLink, target, storedLink.revokedAt(), clock.instant());
    }

    @Override
    public LinkBatchResult getLinks(List<UUID> linkIds) {
        if (linkIds == null || linkIds.isEmpty() || linkIds.size() > 100
                || linkIds.stream().anyMatch(Objects::isNull)) {
            throw new InvalidRequestException("linkIds는 빈 값 없이 1~100개 지정해야 합니다");
        }
        List<UUID> uniqueIds = linkIds.stream().distinct().toList();
        List<StoredLink> stored = repository.findByIds(uniqueIds);
        Instant evaluatedAt = clock.instant();
        Map<UUID, LinkResult> found = stored.stream()
                .flatMap(link -> link.trustedTarget()
                        .map(target -> toResult(link, target, link.revokedAt(), evaluatedAt)).stream())
                .collect(Collectors.toMap(LinkResult::id, Function.identity()));
        return new LinkBatchResult(
                uniqueIds.stream().map(found::get).filter(Objects::nonNull).toList(),
                uniqueIds.stream().filter(id -> !found.containsKey(id)).toList(),
                evaluatedAt
        );
    }

    @Override
    public LinkSearchResult searchLinks(LinkSearchQuery query) {
        int limit = query.limit();
        if (limit < 1 || limit > MAX_SEARCH_LIMIT
                || !isOrderedRange(query.createdFrom(), query.createdBefore())
                || !isOrderedRange(query.expiresFrom(), query.expiresBefore())) {
            throw new InvalidRequestException(
                    "limit은 1~" + MAX_SEARCH_LIMIT + "이고 생성·만료 기간의 끝은 시작보다 뒤여야 합니다");
        }
        // 다음 행 확인용으로 한 건을 더 읽는다. 반환할 항목이 없어도 마지막으로 검사한 행 다음부터 이어서 조회한다.
        List<StoredLink> scanned = repository.scanAfter(query.afterLinkId(), limit + 1);
        boolean hasMore = scanned.size() > limit;
        List<StoredLink> rows = hasMore ? scanned.subList(0, limit) : scanned;
        Instant evaluatedAt = clock.instant();
        List<LinkResult> items = rows.stream()
                .filter(stored -> query.targetSystem() == null
                        || query.targetSystem().name().equals(stored.targetSystem()))
                .filter(stored -> isInRange(stored.createdAt(), query.createdFrom(), query.createdBefore()))
                .filter(stored -> isInRange(stored.expiresAt(), query.expiresFrom(), query.expiresBefore()))
                .flatMap(stored -> stored.trustedTarget()
                        .map(target -> toResult(stored, target, stored.revokedAt(), evaluatedAt)).stream())
                .filter(link -> query.status() == null || link.status() == query.status())
                .toList();
        return new LinkSearchResult(items, hasMore ? rows.getLast().id() : null, hasMore, evaluatedAt);
    }

    @Override
    @Transactional
    public RevokedLinkResult revokeLink(UUID linkId) {
        StoredLink storedLink = repository.findByIdForUpdate(linkId)
                .orElseThrow(LinkNotFoundException::new);
        TrustedTarget target = storedLink.trustedTarget().orElseThrow(LinkNotFoundException::new);
        Instant now = clock.instant();
        if (storedLink.revokedAt() != null) {
            return new RevokedLinkResult(toResult(storedLink, target, storedLink.revokedAt(), now), true);
        }
        Instant revokedAt = LinkRevocationPolicy.firstRevocationAt(storedLink.createdAt(), now);
        repository.revoke(storedLink.id(), revokedAt);
        return new RevokedLinkResult(toResult(storedLink, target, revokedAt, now), false);
    }

    private static boolean isOrderedRange(Instant from, Instant before) {
        return from == null || before == null || before.isAfter(from);
    }

    /** 시작은 포함하고 끝은 제외한다. 기간을 지정하면 값이 없는 행(만료 없음)은 제외한다. */
    private static boolean isInRange(Instant value, Instant from, Instant before) {
        return (from == null || (value != null && !value.isBefore(from)))
                && (before == null || (value != null && value.isBefore(before)));
    }

    private LinkResult toResult(
            StoredLink storedLink,
            TrustedTarget target,
            Instant revokedAt,
            Instant evaluatedAt
    ) {
        return new LinkResult(
                storedLink.id(),
                target.targetSystem(),
                target.targetPath(),
                target.purpose(),
                storedLink.notBefore(),
                storedLink.expiresAt(),
                revokedAt,
                storedLink.createdAt(),
                evaluatedAt
        );
    }
}
