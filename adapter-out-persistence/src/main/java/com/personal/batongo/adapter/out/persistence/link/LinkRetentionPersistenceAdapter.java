package com.personal.batongo.adapter.out.persistence.link;

import com.personal.batongo.application.link.port.out.LinkRetentionPort;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LinkRetentionPersistenceAdapter implements LinkRetentionPort {
    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public LinkRetentionPersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbc = JdbcClient.create(jdbcTemplate);
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<RetiredLink> lockRetiredLinks(Instant cutoff, int batchSize) {
        return jdbc.sql("""
                        SELECT BIN_TO_UUID(s.id) AS id, s.target_system, s.purpose, s.target_path,
                               s.not_before, s.expires_at
                        FROM smart_links s JOIN link_creation_requests r ON r.link_id = s.id
                        WHERE s.retired_at <= ? AND r.purged_at IS NULL
                        ORDER BY s.retired_at, s.id
                        LIMIT ? FOR UPDATE SKIP LOCKED
                        """)
                .params(UtcDateTimes.write(cutoff), batchSize)
                .query((row, index) -> new RetiredLink(UUID.fromString(row.getString("id")),
                        row.getString("target_system"), row.getString("purpose"), row.getString("target_path"),
                        UtcDateTimes.read(row, "not_before"), UtcDateTimes.read(row, "expires_at")))
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void purge(List<PurgedLink> links, Instant purgedAt) {
        if (links.isEmpty()) {
            return;
        }
        LocalDateTime storedPurgedAt = UtcDateTimes.write(purgedAt);
        jdbcTemplate.batchUpdate("""
                UPDATE link_creation_requests
                SET purged_at = ?, request_hash = ?, public_origin = NULL
                WHERE link_id = UUID_TO_BIN(?)
                """, links, links.size(), (statement, link) -> {
            statement.setObject(1, storedPurgedAt);
            statement.setString(2, link.requestHash());
            statement.setString(3, link.id().toString());
        });
        jdbcTemplate.batchUpdate("DELETE FROM smart_links WHERE id = UUID_TO_BIN(?)",
                links, links.size(), (statement, link) -> statement.setString(1, link.id().toString()));
    }
}
