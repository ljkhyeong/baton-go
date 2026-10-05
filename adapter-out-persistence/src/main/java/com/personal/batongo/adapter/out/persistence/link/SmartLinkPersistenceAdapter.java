package com.personal.batongo.adapter.out.persistence.link;

import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.domain.link.SmartLink;
import com.personal.batongo.domain.link.TrustedTarget;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SmartLinkPersistenceAdapter implements SmartLinkRepository {

    private static final String SELECT_LINK = """
            SELECT BIN_TO_UUID(id) AS id, code_hash, target_system, target_path, purpose,
                   not_before, expires_at, revoked_at, created_at
            FROM smart_links
            """;

    private final JdbcClient jdbcClient;

    public SmartLinkPersistenceAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public void save(SmartLink smartLink) {
        TrustedTarget target = smartLink.trustedTarget();
        jdbcClient.sql("""
                        INSERT INTO smart_links (
                            id, code_hash, target_system, target_path, purpose, not_before, expires_at, created_at
                        ) VALUES (UUID_TO_BIN(?), ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(
                        smartLink.id().toString(),
                        smartLink.codeHash(),
                        target.targetSystem().name(),
                        target.targetPath(),
                        target.purpose().name(),
                        UtcDateTimes.write(smartLink.notBefore()),
                        UtcDateTimes.write(smartLink.expiresAt()),
                        UtcDateTimes.write(smartLink.createdAt())
                )
                .update();
    }

    @Override
    public Optional<StoredLink> findById(UUID id) {
        return findOne("WHERE id = UUID_TO_BIN(?)", id.toString());
    }

    @Override
    public Optional<StoredLink> findByIdForUpdate(UUID id) {
        return findOne("WHERE id = UUID_TO_BIN(?) FOR UPDATE", id.toString());
    }

    @Override
    public Optional<StoredLink> findByCodeHash(String codeHash) {
        return findOne("WHERE code_hash = ?", codeHash);
    }

    @Override
    public List<StoredLink> findByIds(List<UUID> ids) {
        String placeholders = String.join(", ", Collections.nCopies(ids.size(), "UUID_TO_BIN(?)"));
        return jdbcClient.sql(SELECT_LINK + "WHERE id IN (" + placeholders + ")")
                .params(ids.stream().map(UUID::toString).toList())
                .query(this::storedLink)
                .list();
    }

    @Override
    public List<StoredLink> scanAfter(UUID afterLinkId, int limit) {
        var query = jdbcClient.sql(SELECT_LINK
                + (afterLinkId == null ? "" : "WHERE id > UUID_TO_BIN(?) ")
                + "ORDER BY id LIMIT ?");
        if (afterLinkId != null) {
            query.param(afterLinkId.toString());
        }
        return query.param(limit)
                .query(this::storedLink)
                .list();
    }

    @Override
    public void revoke(UUID id, Instant revokedAt) {
        jdbcClient.sql("UPDATE smart_links SET revoked_at = ? WHERE id = UUID_TO_BIN(?)")
                .params(UtcDateTimes.write(revokedAt), id.toString())
                .update();
    }

    private Optional<StoredLink> findOne(String condition, String value) {
        return jdbcClient.sql(SELECT_LINK + condition)
                .param(value)
                .query(this::storedLink)
                .optional();
    }

    private StoredLink storedLink(ResultSet resultSet, int rowNumber) throws SQLException {
        return new StoredLink(
                UUID.fromString(resultSet.getString("id")),
                resultSet.getString("code_hash"),
                resultSet.getString("target_system"),
                resultSet.getString("target_path"),
                resultSet.getString("purpose"),
                UtcDateTimes.read(resultSet, "not_before"),
                UtcDateTimes.read(resultSet, "expires_at"),
                UtcDateTimes.read(resultSet, "revoked_at"),
                UtcDateTimes.read(resultSet, "created_at")
        );
    }
}
