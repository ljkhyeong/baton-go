package com.personal.batongo.adapter.out.persistence.link;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** 애플리케이션 시작 시 링크 생성 예약 테이블 스키마를 검증하는 JPA 엔티티입니다. */
@Entity
@Table(name = "link_creation_requests")
class LinkCreationRequestEntity {

    @Id
    @Column(name = "idempotency_key_hash", nullable = false, length = 64)
    private String idempotencyKeyHash;

    @Column(name = "link_id", nullable = false, columnDefinition = "binary(16)")
    private UUID linkId;

    @Column(name = "public_origin", length = 255)
    private String publicOrigin;

    @Column(name = "key_id", nullable = false, length = 32)
    private String keyId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "purged_at")
    private Instant purgedAt;

    @Column(name = "request_hash", length = 64)
    private String requestHash;

    protected LinkCreationRequestEntity() {
    }
}
