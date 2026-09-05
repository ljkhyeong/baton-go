package com.personal.batongo.adapter.out.persistence.link;

import com.personal.batongo.application.link.port.out.LinkCreationReservationPort;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LinkCreationReservationPersistenceAdapter
        implements LinkCreationReservationPort {

    private final SpringDataLinkCreationRequestRepository repository;

    public LinkCreationReservationPersistenceAdapter(
            SpringDataLinkCreationRequestRepository repository
    ) {
        this.repository = repository;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Reservation> find(String idempotencyKeyHash) {
        return repository.findCurrent(idempotencyKeyHash)
                .map(request -> new Reservation(
                        request.getLinkId(),
                        request.getPublicOrigin(),
                        request.getKeyId(),
                        request.getPurgedAt(),
                        request.getRequestHash(),
                        false
                ));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Reservation reserve(
            String idempotencyKeyHash,
            UUID proposedLinkId,
            String publicOrigin,
            String keyId,
            Instant createdAt
    ) {
        int inserted = repository.insertIfAbsent(
                idempotencyKeyHash,
                proposedLinkId.toString(),
                publicOrigin,
                keyId,
                createdAt
        );
        if (inserted == 1) {
            return new Reservation(proposedLinkId, publicOrigin, keyId, null, null, true);
        }

        return find(idempotencyKeyHash)
                .orElseThrow(() -> new IllegalStateException("링크 생성 예약을 찾을 수 없습니다"));
    }
}
