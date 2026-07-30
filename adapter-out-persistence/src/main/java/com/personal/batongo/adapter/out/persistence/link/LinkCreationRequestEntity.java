package com.personal.batongo.adapter.out.persistence.link;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "link_creation_requests")
class LinkCreationRequestEntity {

    @Id
    @Column(name = "idempotency_key_hash", nullable = false, length = 64)
    private String idempotencyKeyHash;

    @Column(name = "link_id", nullable = false, columnDefinition = "binary(16)")
    private UUID linkId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LinkCreationRequestEntity() {
    }

    UUID getLinkId() {
        return linkId;
    }
}
