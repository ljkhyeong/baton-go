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
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<Reservation> find(String idempotencyKeyHash) {
        return repository.findById(idempotencyKeyHash)
                .map(request -> toReservation(request, false));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Reservation reserve(
            String idempotencyKeyHash,
            UUID proposedLinkId,
            String publicOrigin,
            Instant createdAt
    ) {
        int inserted = repository.insertIfAbsent(
                idempotencyKeyHash,
                proposedLinkId.toString(),
                publicOrigin,
                createdAt
        );
        LinkCreationRequestEntity request = repository
                .findById(idempotencyKeyHash)
                .orElseThrow(() -> new IllegalStateException("링크 생성 예약을 찾을 수 없습니다"));
        return toReservation(request, inserted == 1);
    }

    private Reservation toReservation(LinkCreationRequestEntity request, boolean owner) {
        return new Reservation(request.getLinkId(), request.getPublicOrigin(), owner);
    }
}
