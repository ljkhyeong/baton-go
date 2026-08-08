package com.personal.batongo.adapter.out.persistence.link;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SpringDataLinkCreationRequestRepository
        extends JpaRepository<LinkCreationRequestEntity, String> {

    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT IGNORE INTO link_creation_requests (
                idempotency_key_hash,
                link_id,
                created_at
            ) VALUES (
                :idempotencyKeyHash,
                :linkId,
                :createdAt
            )
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("idempotencyKeyHash") String idempotencyKeyHash,
            @Param("linkId") byte[] linkId,
            @Param("createdAt") Instant createdAt
    );
}
