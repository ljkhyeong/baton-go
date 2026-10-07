package com.personal.batongo.adapter.out.persistence.link;

import com.personal.batongo.application.link.port.out.LinkRetentionPort;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
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
    public List<UUID> lockRetiredLinks(Instant cutoff, int batchSize) {
        return jdbc.sql("""
                        SELECT BIN_TO_UUID(s.id) AS id
                        FROM smart_links s JOIN link_creation_requests r ON r.link_id = s.id
                        WHERE s.retired_at <= ? AND r.purged_at IS NULL
                        ORDER BY s.retired_at, s.id
                        LIMIT ? FOR UPDATE SKIP LOCKED
                        """)
                .params(UtcDateTimes.write(cutoff), batchSize)
                .query((row, index) -> UUID.fromString(row.getString("id")))
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void purge(List<UUID> linkIds, Instant purgedAt) {
        // rewriteBatchedStatements를 쓰지 않아 JDBC 배치도 문장마다 실행되므로 링크별로 실행한다.
        for (UUID linkId : linkIds) {
            String id = linkId.toString();
            jdbc.sql("""
                            UPDATE link_creation_requests
                            SET purged_at = ?, public_origin = NULL
                            WHERE link_id = UUID_TO_BIN(?)
                            """)
                    .params(UtcDateTimes.write(purgedAt), id)
                    .update();
            jdbc.sql("DELETE FROM smart_links WHERE id = UUID_TO_BIN(?)")
                    .param(id)
                    .update();
        }
    }
}
