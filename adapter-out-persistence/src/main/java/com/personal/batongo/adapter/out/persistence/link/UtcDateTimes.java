package com.personal.batongo.adapter.out.persistence.link;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 직접 작성한 SQL의 DATETIME(6) 값을 ADR-0007에 따라 UTC Instant로 읽고 씁니다. */
final class UtcDateTimes {

    private UtcDateTimes() {
    }

    static Instant read(ResultSet row, String column) throws SQLException {
        LocalDateTime value = row.getObject(column, LocalDateTime.class);
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    static LocalDateTime write(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
