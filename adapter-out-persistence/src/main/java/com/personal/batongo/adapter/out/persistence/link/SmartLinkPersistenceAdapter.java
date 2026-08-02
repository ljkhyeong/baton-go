package com.personal.batongo.adapter.out.persistence.link;

import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkResolution;
import com.personal.batongo.domain.link.SmartLink;
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
    public Optional<SmartLink> findByIdForUpdate(UUID id) {
        return repository.findByIdForUpdate(id);
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

    private Calendar utcCalendar() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
