package com.personal.batongo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** 실행 계획 대신 같은 연결의 Handler_read 카운터로 문장이 실제로 읽은 행 수를 잰다. */
final class MySqlRowReads {

    private MySqlRowReads() {
    }

    static long during(JdbcTemplate jdbc, TransactionTemplate transactions, Runnable statement) {
        return transactions.execute(status -> {
            // 카운터 조회도 내부 임시 표를 읽으므로 그 증가분을 뺀다.
            long first = handlerReads(jdbc);
            long overhead = handlerReads(jdbc) - first;
            long before = handlerReads(jdbc);
            statement.run();
            return handlerReads(jdbc) - before - overhead;
        });
    }

    private static long handlerReads(JdbcTemplate jdbc) {
        return jdbc.query("SHOW SESSION STATUS LIKE 'Handler_read%'", (row, index) -> row.getLong(2))
                .stream().mapToLong(Long::longValue).sum();
    }
}
