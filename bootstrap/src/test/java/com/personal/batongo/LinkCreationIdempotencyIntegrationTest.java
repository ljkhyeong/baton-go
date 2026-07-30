package com.personal.batongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.application.link.CreationIdempotencyKey;
import com.personal.batongo.application.link.error.IdempotencyKeyConflictException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreateLinkCommand;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreatedLinkResult;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "baton-go.management.token=test-management-token-that-is-long-enough",
        "baton-go.link-code.secret=test-link-code-secret-that-is-separate-and-long-enough",
        "baton-go.public-base-url=https://go.example",
        "baton-go.targets.baton-base-url=https://baton.example",
        "baton-go.targets.round-base-url=https://round.example"
})
class LinkCreationIdempotencyIntegrationTest {

    private static final int CONCURRENCY = 8;
    private static final String IDEMPOTENCY_KEY =
            "8e448211-66ae-44ab-9888-c4960648c22b";

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private SmartLinkUseCase smartLinkUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("동시에 같은 생성 요청을 보내도 MySQL에는 링크와 예약이 한 건만 남는다")
    void serializesConcurrentCreation() throws Exception {
        CreateLinkCommand command = new CreateLinkCommand(
                new CreationIdempotencyKey(IDEMPOTENCY_KEY),
                TargetSystem.ROUND,
                "/room/abcd-efgh-jkmn",
                LinkPurpose.MEETING_ENTRY,
                null,
                null
        );
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);

        try {
            List<Future<CreatedLinkResult>> futures = new ArrayList<>();
            for (int index = 0; index < CONCURRENCY; index++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return smartLinkUseCase.createLink(command);
                }));
            }

            start.countDown();
            List<CreatedLinkResult> results = new ArrayList<>();
            for (Future<CreatedLinkResult> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }

            CreatedLinkResult first = results.getFirst();
            assertThat(results)
                    .extracting(result -> result.link().id())
                    .containsOnly(first.link().id());
            assertThat(results)
                    .extracting(CreatedLinkResult::rawCode)
                    .containsOnly(first.rawCode());
            assertThat(results)
                    .filteredOn(result -> !result.replayed())
                    .hasSize(1);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM smart_links",
                    Long.class
            )).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM link_creation_requests",
                    Long.class
            )).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT code_hash FROM smart_links",
                    String.class
            ))
                    .matches("^[0-9a-f]{64}$")
                    .isNotEqualTo(first.rawCode());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT idempotency_key_hash FROM link_creation_requests",
                    String.class
            ))
                    .matches("^[0-9a-f]{64}$")
                    .isNotEqualTo(IDEMPOTENCY_KEY);
            assertThatThrownBy(() -> smartLinkUseCase.createLink(new CreateLinkCommand(
                    new CreationIdempotencyKey(IDEMPOTENCY_KEY),
                    TargetSystem.ROUND,
                    "/room/qrst-uvwx-yz23",
                    LinkPurpose.MEETING_ENTRY,
                    null,
                    null
            )))
                    .isInstanceOf(IdempotencyKeyConflictException.class);
        } finally {
            executor.shutdownNow();
        }
    }
}
