package com.personal.batongo.adapter.out.persistence.link;

import com.personal.batongo.application.link.LinkCreationFingerprint;
import com.personal.batongo.application.link.port.out.LinkRetentionPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LinkRetentionPersistenceAdapter implements LinkRetentionPort {
    private final JdbcClient jdbc;

    public LinkRetentionPersistenceAdapter(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int purgeRetiredLinks(Instant cutoff, Instant purgedAt, int batchSize) {
        var candidates = jdbc.sql("""
                        SELECT BIN_TO_UUID(s.id) AS id, s.target_system, s.purpose, s.target_path,
                               s.not_before, s.expires_at
                        FROM smart_links s JOIN link_creation_requests r ON r.link_id = s.id
                        WHERE s.retired_at <= ? AND r.purged_at IS NULL
                        ORDER BY s.retired_at, s.id
                        LIMIT ? FOR UPDATE SKIP LOCKED
                        """)
                .params(LocalDateTime.ofInstant(cutoff, ZoneOffset.UTC), batchSize)
                .query((row, index) -> new Candidate(row.getString("id"), LinkCreationFingerprint.of(
                        row.getString("target_system"), row.getString("purpose"), row.getString("target_path"),
                        instant(row, "not_before"), instant(row, "expires_at"))))
                .list();
        for (Candidate candidate : candidates) {
            jdbc.sql("""
                    UPDATE link_creation_requests
                    SET purged_at = ?, request_hash = ?, public_origin = NULL
                    WHERE link_id = UUID_TO_BIN(?)
                    """)
                    .params(LocalDateTime.ofInstant(purgedAt, ZoneOffset.UTC), candidate.requestHash(), candidate.id())
                    .update();
            jdbc.sql("DELETE FROM smart_links WHERE id = UUID_TO_BIN(?)").param(candidate.id()).update();
        }
        return candidates.size();
    }

    private Instant instant(ResultSet row, String column) throws SQLException {
        LocalDateTime value = row.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private record Candidate(String id, String requestHash) {
        @Override public String toString() { return "Candidate[id=" + id + "]"; }
    }
}
