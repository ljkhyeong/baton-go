package com.personal.batongo.application.link.port.out;

import java.time.Instant;
import java.util.UUID;

public interface LinkCreationReservationPort {

    Reservation reserve(String idempotencyKeyHash, UUID proposedLinkId, Instant createdAt);

    record Reservation(
            UUID linkId,
            boolean owner
    ) {
    }
}
