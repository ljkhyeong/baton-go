package com.personal.batongo.adapter.out.persistence.link;

import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkResolution;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkSnapshot;
import com.personal.batongo.domain.link.SmartLink;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Calendar;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
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

    private final SpringDataSmartLinkRepository repository;
    private final JdbcTemplate jdbcTemplate;

    public SmartLinkPersistenceAdapter(
            SpringDataSmartLinkRepository repository,
            JdbcTemplate jdbcTemplate
    ) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public SmartLink save(SmartLink smartLink) {
        return repository.save(smartLink);
    }

    @Override
    public Optional<SmartLink> findById(UUID id) {
        return repository.findById(id);
    }

    @Override
    public Optional<StoredLinkResolution> findResolutionByCodeHash(String codeHash) {
        // 대상 필드는 enum hydration 전에 raw로 읽어 수동 적재된 알 수 없는 값도 404로 닫는다.
        List<StoredLinkResolution> rows = jdbcTemplate.query(
                """
                        SELECT BIN_TO_UUID(id) AS id,
                               target_system,
                               target_path,
                               purpose,
                               not_before,
                               expires_at,
                               revoked_at
                        FROM smart_links
                        WHERE code_hash = ?
                        """,
                (resultSet, rowNumber) -> new StoredLinkResolution(
                        UUID.fromString(resultSet.getString("id")),
                        resultSet.getString("target_system"),
                        resultSet.getString("target_path"),
                        resultSet.getString("purpose"),
                        instant(resultSet.getTimestamp("not_before", utcCalendar())),
                        instant(resultSet.getTimestamp("expires_at", utcCalendar())),
                        instant(resultSet.getTimestamp("revoked_at", utcCalendar()))
                ),
                codeHash
        );
        return rows.stream().findFirst();
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
            return jdbcTemplate.query(
                    STORED_SNAPSHOT_SELECT + "ORDER BY stored_link.id LIMIT ?",
                    this::storedSnapshot,
                    limit
            );
        }
        return jdbcTemplate.query(
                STORED_SNAPSHOT_SELECT
                        + "WHERE stored_link.id > UUID_TO_BIN(?) "
                        + "ORDER BY stored_link.id LIMIT ?",
                this::storedSnapshot,
                afterLinkId.toString(),
                limit
        );
    }

    @Override
    public boolean revokeStoredIfVersion(
            UUID id,
            long expectedVersion,
            Instant revokedAt
    ) {
        int updated = jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    UPDATE smart_links
                    SET revoked_at = ?, version = version + 1
                    WHERE id = UUID_TO_BIN(?)
                      AND version = ?
                      AND revoked_at IS NULL
                    """);
            statement.setTimestamp(1, Timestamp.from(revokedAt), utcCalendar());
            statement.setString(2, id.toString());
            statement.setLong(3, expectedVersion);
            return statement;
        });
        return updated == 1;
    }

    private Optional<StoredLinkSnapshot> queryStoredSnapshot(
            String sql,
            String linkId
    ) {
        List<StoredLinkSnapshot> rows = jdbcTemplate.query(
                sql,
                this::storedSnapshot,
                linkId
        );
        return rows.stream().findFirst();
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
                instant(resultSet.getTimestamp("not_before", utcCalendar())),
                instant(resultSet.getTimestamp("expires_at", utcCalendar())),
                instant(resultSet.getTimestamp("revoked_at", utcCalendar())),
                instant(resultSet.getTimestamp("created_at", utcCalendar())),
                resultSet.getLong("version"),
                resultSet.getBoolean("creation_request_present")
        );
    }

    private Calendar utcCalendar() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
