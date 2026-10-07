package com.personal.batongo.application.link.port.out;

import java.time.Instant;
import java.util.UUID;

public interface LinkCreationReservationPort {

    Reservation reserve(
            String idempotencyKeyHash,
            UUID proposedLinkId,
            String publicOrigin,
            String keyId,
            String requestHash,
            Instant createdAt
    );

    record Reservation(
            UUID linkId,
            String publicOrigin,
            String keyId,
            Instant purgedAt,
            String requestHash,
            boolean owner
    ) {

        @Override
        public String toString() {
            return "Reservation[linkId=" + linkId
                    + ", publicOrigin=redacted, owner=" + owner + "]";
        }
    }
}
