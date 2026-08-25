package com.personal.batongo.adapter.out.persistence.link;

import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkReplay;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkResolution;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkSnapshot;
import com.personal.batongo.domain.link.SmartLink;
import jakarta.persistence.EntityManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class SmartLinkPersistenceAdapter implements SmartLinkRepository {

    private static final String STORED_SNAPSHOT_SELECT = """
            SELECT BIN_TO_UUID(stored_link.id) AS id,
                   stored_link.target_system,
                   stored_link.target_path,
                   stored_link.purpose,
                   stored_link.not_before,
                   stored_link.expires_at,
                   stored_link.revoked_at,
                   stored_link.created_at,
                   stored_link.version,
                   EXISTS(
                       SELECT 1
                       FROM link_creation_requests creation_request
                       WHERE creation_request.link_id = stored_link.id
                   ) AS creation_request_present
            FROM smart_links stored_link
            """;

    private final EntityManager entityManager;
    private final JdbcClient jdbcClient;

    public SmartLinkPersistenceAdapter(
            EntityManager entityManager,
            JdbcClient jdbcClient
    ) {
        this.entityManager = entityManager;
        this.jdbcClient = jdbcClient;
    }

    @Override
    public void save(SmartLink smartLink) {
        entityManager.persist(smartLink);
    }

    @Override
    public Optional<StoredLinkReplay> findReplayById(UUID id) {
        return jdbcClient.sql("""
                        SELECT BIN_TO_UUID(id) AS id,
                               target_system,
                               target_path,
                               purpose,
                               code_hash,
                               not_before,
                               expires_at,
                               revoked_at,
                               created_at
                        FROM smart_links
                        WHERE id = UUID_TO_BIN(?)
                        """)
                .param(id.toString())
                .query((resultSet, rowNumber) -> new StoredLinkReplay(
                        UUID.fromString(resultSet.getString("id")),
                        resultSet.getString("target_system"),
                        resultSet.getString("target_path"),
                        resultSet.getString("purpose"),
                        resultSet.getString("code_hash"),
                        instant(resultSet, "not_before"),
                        instant(resultSet, "expires_at"),
                        instant(resultSet, "revoked_at"),
                        instant(resultSet, "created_at")
                ))
                .optional();
    }

    @Override
    public Optional<StoredLinkResolution> findResolutionByCodeHash(String codeHash) {
        // 대상 필드는 enum hydration 전에 raw로 읽어 수동 적재된 알 수 없는 값도 404로 닫는다.
        return jdbcClient.sql("""
                        SELECT BIN_TO_UUID(id) AS id,
                               target_system,
                               target_path,
                               purpose,
                               not_before,
                               expires_at,
                               revoked_at
                        FROM smart_links
                        WHERE code_hash = ?
                        """)
                .param(codeHash)
                .query((resultSet, rowNumber) -> new StoredLinkResolution(
                        UUID.fromString(resultSet.getString("id")),
                        resultSet.getString("target_system"),
                        resultSet.getString("target_path"),
                        resultSet.getString("purpose"),
                        instant(resultSet, "not_before"),
                        instant(resultSet, "expires_at"),
                        instant(resultSet, "revoked_at")
                ))
                .optional();
    }

    @Override
    public Optional<StoredLinkSnapshot> findStoredById(UUID id) {
        return queryStoredSnapshot(
                STORED_SNAPSHOT_SELECT + "WHERE stored_link.id = UUID_TO_BIN(?)",
                id.toString()
        );
    }

    @Override
    public Optional<StoredLinkSnapshot> findStoredByIdForUpdate(UUID id) {
        return queryStoredSnapshot(
                STORED_SNAPSHOT_SELECT
                        + "WHERE stored_link.id = UUID_TO_BIN(?) FOR UPDATE",
                id.toString()
        );
    }

    @Override
    public List<StoredLinkSnapshot> scanStoredAfter(UUID afterLinkId, int limit) {
        if (afterLinkId == null) {
            return jdbcClient.sql(
                            STORED_SNAPSHOT_SELECT + "ORDER BY stored_link.id LIMIT ?"
                    )
                    .param(limit)
                    .query(this::storedSnapshot)
                    .list();
        }
        return jdbcClient.sql(
                        STORED_SNAPSHOT_SELECT
                                + "WHERE stored_link.id > UUID_TO_BIN(?) "
                                + "ORDER BY stored_link.id LIMIT ?"
                )
                .params(afterLinkId.toString(), limit)
                .query(this::storedSnapshot)
                .list();
    }

    @Override
    public boolean revokeStoredIfVersion(
            UUID id,
            long expectedVersion,
            Instant revokedAt
    ) {
        long nextVersion = Math.incrementExact(expectedVersion);
        int updated = jdbcClient.sql("""
                        UPDATE smart_links
                        SET revoked_at = ?, version = ?
                        WHERE id = UUID_TO_BIN(?)
                          AND version = ?
                          AND revoked_at IS NULL
                        """)
                .params(
                        LocalDateTime.ofInstant(revokedAt, ZoneOffset.UTC),
                        nextVersion,
                        id.toString(),
                        expectedVersion
                )
                .update();
        return updated == 1;
    }

    private Optional<StoredLinkSnapshot> queryStoredSnapshot(
            String sql,
            String linkId
    ) {
        return jdbcClient.sql(sql)
                .param(linkId)
                .query(this::storedSnapshot)
                .optional();
    }

    private StoredLinkSnapshot storedSnapshot(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new StoredLinkSnapshot(
                UUID.fromString(resultSet.getString("id")),
                resultSet.getString("target_system"),
                resultSet.getString("target_path"),
                resultSet.getString("purpose"),
                instant(resultSet, "not_before"),
                instant(resultSet, "expires_at"),
                instant(resultSet, "revoked_at"),
                instant(resultSet, "created_at"),
                resultSet.getLong("version"),
                resultSet.getBoolean("creation_request_present")
        );
    }

    private Instant instant(ResultSet resultSet, String columnName) throws SQLException {
        LocalDateTime value = resultSet.getObject(columnName, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
