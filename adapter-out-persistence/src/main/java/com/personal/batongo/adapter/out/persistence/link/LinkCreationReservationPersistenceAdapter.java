package com.personal.batongo.adapter.out.persistence.link;

import com.personal.batongo.application.link.port.out.LinkCreationReservationPort;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LinkCreationReservationPersistenceAdapter
        implements LinkCreationReservationPort {

    private final JdbcClient jdbcClient;

    public LinkCreationReservationPersistenceAdapter(
            JdbcClient jdbcClient
    ) {
        this.jdbcClient = jdbcClient;
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
        try {
            jdbcClient.sql("""
                            INSERT INTO link_creation_requests (
                                idempotency_key_hash, link_id, public_origin, key_id, created_at
                            ) VALUES (?, UUID_TO_BIN(?), ?, ?, ?)
                            """)
                    .params(idempotencyKeyHash, proposedLinkId.toString(), publicOrigin, keyId,
                            UtcDateTimes.write(createdAt))
                    .update();
            return new Reservation(proposedLinkId, publicOrigin, keyId, null, null, true);
        } catch (DuplicateKeyException exception) {
            return find(idempotencyKeyHash)
                    .orElseThrow(() -> new IllegalStateException("링크 생성 예약을 찾을 수 없습니다"));
        }
    }

    private Optional<Reservation> find(String idempotencyKeyHash) {
        return jdbcClient.sql("""
                        SELECT BIN_TO_UUID(link_id) AS link_id, public_origin, key_id,
                               purged_at, request_hash
                        FROM link_creation_requests
                        WHERE idempotency_key_hash = ? FOR SHARE
                        """)
                .param(idempotencyKeyHash)
                .query((row, index) -> new Reservation(
                        UUID.fromString(row.getString("link_id")),
                        row.getString("public_origin"),
                        row.getString("key_id"),
                        UtcDateTimes.read(row, "purged_at"),
                        row.getString("request_hash"),
                        false
                ))
                .optional();
    }
}
