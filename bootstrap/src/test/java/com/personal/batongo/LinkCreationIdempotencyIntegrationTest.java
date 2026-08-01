package com.personal.batongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.personal.batongo.application.link.CreationIdempotencyKey;
import com.personal.batongo.application.link.error.IdempotencyKeyConflictException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreateLinkCommand;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreatedLinkResult;
import com.personal.batongo.application.link.port.out.LinkCreationReservationPort;
import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.SmartLink;
import com.personal.batongo.domain.link.TargetSystem;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("mysql")
@Testcontainers
@AutoConfigureMockMvc
@Import(LinkCreationIdempotencyIntegrationTest.ConcurrencyTestConfiguration.class)
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
    private static final String ROLLBACK_IDEMPOTENCY_KEY =
            "7606bb52-2837-4359-bca4-d7f295b64fe4";

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private SmartLinkUseCase smartLinkUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private SmartLinkRepository smartLinkRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ControllableReservationPort controllableReservationPort;

    @Autowired
    private MockMvc mockMvc;

    @Test
    @Transactional
    @DisplayName("할당 UUID와 null 버전의 새 링크는 persist 대상으로 저장된다")
    void persistsNewLinkWithAssignedId() {
        SmartLink candidate = SmartLink.create(
                UUID.fromString("7f7386b7-8a34-46c9-ae20-606d95a63bb2"),
                "a".repeat(64),
                TargetSystem.BATON,
                "/teams/persist-check",
                LinkPurpose.RESOURCE_OPEN,
                null,
                null,
                Instant.parse("2026-07-31T00:00:00Z")
        );

        SmartLink saved = smartLinkRepository.save(candidate);
        entityManager.flush();

        assertThat(saved).isSameAs(candidate);
        assertThat(entityManager.contains(candidate)).isTrue();
    }

    @Test
    @DisplayName("실제 Spring 조립은 관리 인증과 request ID filter 순서를 적용한다")
    void assemblesManagementAuthenticationFilters() throws Exception {
        UUID missingLinkId = UUID.fromString("27e436c8-e696-4477-9fa2-45e4cf37a942");

        mockMvc.perform(get("/api/v1/links/{linkId}", missingLinkId))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(
                        HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer realm=\"baton-go-management\""
                ))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        mockMvc.perform(get("/api/v1/links/{linkId}", missingLinkId)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "bEaReR test-management-token-that-is-long-enough"
                        ))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LINK_NOT_FOUND"));
    }

    @Test
    @DisplayName("정의되지 않은 링크 생성 필드는 저장 전에 400으로 거부한다")
    void rejectsUnknownCreationFieldBeforePersistence() throws Exception {
        String targetPath = "/room/unknown-json-field";

        mockMvc.perform(post("/api/v1/links")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer test-management-token-that-is-long-enough"
                        )
                        .header(
                                "Idempotency-Key",
                                "09ef0b69-9004-47ed-a056-5f6b720dce23"
                        )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetSystem": "ROUND",
                                  "targetPath": "%s",
                                  "purpose": "MEETING_ENTRY",
                                  "expireAt": "2026-08-01T00:00:00Z"
                                }
                                """.formatted(targetPath)))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM smart_links WHERE target_path = ?",
                Long.class,
                targetPath
        )).isZero();
    }

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
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        controllableReservationPort.arm(CONCURRENCY, false);

        try {
            List<Future<CreatedLinkResult>> futures = submitConcurrentCreations(
                    executor,
                    command
            );

            controllableReservationPort.awaitAllEntered();
            controllableReservationPort.awaitFirstOwnerReserved();
            awaitReservationInsertWaiters(CONCURRENCY - 1);
            assertThat(futures).allMatch(future -> !future.isDone());

            controllableReservationPort.releaseFirstOwner();
            List<CreatedLinkResult> results = successfulResults(futures);

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
                    "SELECT COUNT(*) FROM smart_links WHERE target_path = ?",
                    Long.class,
                    command.targetPath()
            )).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    """
                            SELECT COUNT(*)
                            FROM link_creation_requests request
                            JOIN smart_links link ON link.id = request.link_id
                            WHERE link.target_path = ?
                            """,
                    Long.class,
                    command.targetPath()
            )).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT code_hash FROM smart_links WHERE target_path = ?",
                    String.class,
                    command.targetPath()
            ))
                    .matches("^[0-9a-f]{64}$")
                    .isNotEqualTo(first.rawCode());
            assertThat(jdbcTemplate.queryForObject(
                    """
                            SELECT request.idempotency_key_hash
                            FROM link_creation_requests request
                            JOIN smart_links link ON link.id = request.link_id
                            WHERE link.target_path = ?
                            """,
                    String.class,
                    command.targetPath()
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
            controllableReservationPort.releaseFirstOwner();
            controllableReservationPort.reset();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("첫 생성 transaction이 rollback되면 대기 요청 하나가 승계하고 나머지는 재시도할 수 있다")
    void transfersOwnershipAfterWinnerRollback() throws Exception {
        CreateLinkCommand command = new CreateLinkCommand(
                new CreationIdempotencyKey(ROLLBACK_IDEMPOTENCY_KEY),
                TargetSystem.ROUND,
                "/room/rollback-owner",
                LinkPurpose.MEETING_ENTRY,
                null,
                null
        );
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENCY);
        controllableReservationPort.arm(CONCURRENCY, true);

        try {
            List<Future<CreatedLinkResult>> futures = submitConcurrentCreations(
                    executor,
                    command
            );

            controllableReservationPort.awaitAllEntered();
            controllableReservationPort.awaitFirstOwnerReserved();
            awaitReservationInsertWaiters(CONCURRENCY - 1);
            assertThat(futures).allMatch(future -> !future.isDone());

            controllableReservationPort.releaseFirstOwner();
            List<CreatedLinkResult> results = new ArrayList<>();
            int rollbackFailures = 0;
            int retryableFailures = 0;
            for (Future<CreatedLinkResult> future : futures) {
                try {
                    results.add(future.get(20, TimeUnit.SECONDS));
                } catch (ExecutionException exception) {
                    if (exception.getCause() instanceof ForcedReservationRollbackException) {
                        rollbackFailures++;
                    } else {
                        assertThat(exception.getCause())
                                .isInstanceOf(CannotAcquireLockException.class);
                        retryableFailures++;
                    }
                }
            }

            assertThat(rollbackFailures).isEqualTo(1);
            assertThat(retryableFailures).isBetween(1, CONCURRENCY - 2);
            assertThat(results).isNotEmpty();
            assertThat(results.size() + rollbackFailures + retryableFailures)
                    .isEqualTo(CONCURRENCY);
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

            CreatedLinkResult replay = smartLinkUseCase.createLink(command);
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.link().id()).isEqualTo(first.link().id());
            assertThat(replay.rawCode()).isEqualTo(first.rawCode());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM smart_links WHERE target_path = ?",
                    Long.class,
                    command.targetPath()
            )).isEqualTo(1L);
            assertThat(jdbcTemplate.queryForObject(
                    """
                            SELECT COUNT(*)
                            FROM link_creation_requests request
                            JOIN smart_links link ON link.id = request.link_id
                            WHERE link.target_path = ?
                            """,
                    Long.class,
                    command.targetPath()
            )).isEqualTo(1L);
        } finally {
            controllableReservationPort.releaseFirstOwner();
            controllableReservationPort.reset();
            executor.shutdownNow();
        }
    }

    private List<Future<CreatedLinkResult>> submitConcurrentCreations(
            ExecutorService executor,
            CreateLinkCommand command
    ) {
        List<Future<CreatedLinkResult>> futures = new ArrayList<>();
        for (int index = 0; index < CONCURRENCY; index++) {
            futures.add(executor.submit(() -> smartLinkUseCase.createLink(command)));
        }
        return futures;
    }

    private List<CreatedLinkResult> successfulResults(
            List<Future<CreatedLinkResult>> futures
    ) throws Exception {
        List<CreatedLinkResult> results = new ArrayList<>();
        for (Future<CreatedLinkResult> future : futures) {
            results.add(future.get(20, TimeUnit.SECONDS));
        }
        return results;
    }

    private void awaitReservationInsertWaiters(int expectedWaiters)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long observedWaiters = 0;
        while (System.nanoTime() < deadline) {
            observedWaiters = jdbcTemplate.queryForObject(
                    """
                            SELECT COUNT(*)
                            FROM information_schema.PROCESSLIST
                            WHERE DB = DATABASE()
                              AND COMMAND = 'Query'
                              AND INFO LIKE 'INSERT IGNORE INTO link_creation_requests%'
                            """,
                    Long.class
            );
            if (observedWaiters >= expectedWaiters) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        throw new AssertionError(
                "예약 INSERT lock waiter가 모두 관찰되지 않았습니다: expected="
                        + expectedWaiters
                        + ", observed="
                        + observedWaiters
        );
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ConcurrencyTestConfiguration {

        @Bean
        @Primary
        ControllableReservationPort controllableReservationPort(
                @Qualifier("linkCreationReservationPersistenceAdapter")
                LinkCreationReservationPort delegate
        ) {
            return new ControllableReservationPort(delegate);
        }
    }

    static final class ControllableReservationPort implements LinkCreationReservationPort {

        private final LinkCreationReservationPort delegate;
        private final AtomicBoolean firstOwnerHandled = new AtomicBoolean();

        private volatile CountDownLatch allEntered = new CountDownLatch(0);
        private volatile CountDownLatch firstOwnerReserved = new CountDownLatch(0);
        private volatile CountDownLatch releaseFirstOwner = new CountDownLatch(0);
        private volatile boolean failFirstOwner;

        private ControllableReservationPort(LinkCreationReservationPort delegate) {
            this.delegate = delegate;
        }

        void arm(int expectedRequests, boolean failFirstOwner) {
            this.allEntered = new CountDownLatch(expectedRequests);
            this.firstOwnerReserved = new CountDownLatch(1);
            this.releaseFirstOwner = new CountDownLatch(1);
            this.failFirstOwner = failFirstOwner;
            this.firstOwnerHandled.set(false);
        }

        @Override
        public Reservation reserve(
                String idempotencyKeyHash,
                UUID proposedLinkId,
                Instant createdAt
        ) {
            allEntered.countDown();
            Reservation reservation = delegate.reserve(
                    idempotencyKeyHash,
                    proposedLinkId,
                    createdAt
            );
            if (reservation.owner() && firstOwnerHandled.compareAndSet(false, true)) {
                firstOwnerReserved.countDown();
                await(releaseFirstOwner, "첫 승자 transaction 해제를 기다리지 못했습니다");
                if (failFirstOwner) {
                    throw new ForcedReservationRollbackException();
                }
            }
            return reservation;
        }

        void awaitAllEntered() {
            await(allEntered, "모든 동시 생성 요청이 reservation port에 진입하지 못했습니다");
        }

        void awaitFirstOwnerReserved() {
            await(firstOwnerReserved, "첫 생성 승자가 reservation을 확보하지 못했습니다");
        }

        void releaseFirstOwner() {
            releaseFirstOwner.countDown();
        }

        void reset() {
            allEntered = new CountDownLatch(0);
            firstOwnerReserved = new CountDownLatch(0);
            releaseFirstOwner = new CountDownLatch(0);
            failFirstOwner = false;
            firstOwnerHandled.set(false);
        }

        private void await(CountDownLatch latch, String message) {
            try {
                if (!latch.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(message);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(message, exception);
            }
        }
    }

    static final class ForcedReservationRollbackException extends RuntimeException {
    }
}
