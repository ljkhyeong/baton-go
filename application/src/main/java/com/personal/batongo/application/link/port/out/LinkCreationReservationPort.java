package com.personal.batongo.application.link.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface LinkCreationReservationPort {

    Optional<UUID> findLinkId(String idempotencyKeyHash);

    Reservation reserve(String idempotencyKeyHash, UUID proposedLinkId, Instant createdAt);

    record Reservation(
            UUID linkId,
            boolean owner
    ) {
    }
}
