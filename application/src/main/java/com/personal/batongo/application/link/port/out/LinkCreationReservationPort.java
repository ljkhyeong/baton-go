package com.personal.batongo.application.link.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface LinkCreationReservationPort {

    Optional<Reservation> find(String idempotencyKeyHash);

    Reservation reserve(
            String idempotencyKeyHash,
            UUID proposedLinkId,
            String publicOrigin,
            Instant createdAt
    );

    record Reservation(
            UUID linkId,
            String publicOrigin,
            boolean owner
    ) {

        @Override
        public String toString() {
            return "Reservation[linkId=" + linkId
                    + ", publicOrigin=redacted, owner=" + owner + "]";
        }
    }
}
