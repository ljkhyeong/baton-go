package com.personal.batongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.personal.batongo.application.link.CreationIdempotencyKey;
import com.personal.batongo.application.link.PublicLinkOrigin;
import com.personal.batongo.application.link.error.IdempotencyKeyConflictException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreateLinkCommand;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreatedLinkResult;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import com.personal.batongo.application.link.port.out.LinkCreationReservationPort;
import com.personal.batongo.application.link.port.out.PublicLinkOriginPort;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("mysql")
@Testcontainers
@Import(LinkCreationConcurrencyIntegrationTest.ConcurrencyTestConfiguration.class)
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://identity.example/jwks",
        "spring.security.oauth2.resourceserver.jwt.audiences=baton-go",
        "baton-go.link-code.secret=test-link-code-secret-that-is-separate-and-long-enough",
        "baton-go.public-base-url=https://go.example",
        "baton-go.targets.baton-base-url=https://baton.example",
        "baton-go.targets.round-base-url=https://baton.example"
})
class LinkCreationConcurrencyIntegrationTest {

    private static final int CONCURRENCY = 8;
    private static final String IDEMPOTENCY_KEY =
            "8e448211-66ae-44ab-9888-c4960648c22b";
    private static final String ROLLBACK_IDEMPOTENCY_KEY =
            "7606bb52-2837-4359-bca4-d7f295b64fe4";
    private static final Instant FAR_FUTURE_NOW =
            Instant.parse("2040-06-01T12:34:56.123456Z");

    @Container
    @ServiceConnection(name = "mysql")
    static final MySQLContainer MYSQL = new MySQLContainer(MySqlTestImage.NAME)
            .withUrlParam("connectTimeout", "3000")
            .withUrlParam("socketTimeout", "30000");

    @Autowired
    private SmartLinkUseCase smartLinkUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LinkCodePort linkCodePort;

    @Autowired
    private ControllableReservationPort controllableReservationPort;

    @Autowired
    private ControllablePublicLinkOriginPort controllablePublicLinkOriginPort;

    @Test
    @DisplayName("동시에 같은 생성 요청을 보내도 MySQL에는 링크와 예약이 한 건만 남는다")
    void serializesConcurrentCreation() throws Exception {
        CreateLinkCommand command = new CreateLinkCommand(
                CreationIdempotencyKey.parseRequest(IDEMPOTENCY_KEY),
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
            List<CreatedLinkResult> results = new ArrayList<>();
            for (Future<CreatedLinkResult> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }

            CreatedLinkResult first = results.getFirst();
            assertThat(results)
                    .extracting(result -> result.link().id())
                    .containsOnly(first.link().id());
            assertThat(results)
                    .extracting(CreatedLinkResult::shortUrl)
                    .containsOnly(first.shortUrl());
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
                    .matches("^[0-9a-f]{64}$");
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
                    .matches("^[0-9a-f]{64}$");
            assertThatThrownBy(() -> smartLinkUseCase.createLink(new CreateLinkCommand(
                    CreationIdempotencyKey.parseRequest(IDEMPOTENCY_KEY),
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
    @DisplayName("공개 출처가 다른 서버에서 동시에 생성해도 먼저 저장된 URL을 반환한다")
    void convergesOnStoredPublicOriginAcrossConcurrentReplicas() throws Exception {
        String idempotencyKey = "f14af1a6-9d56-4a41-8f47-c05f7c8898a1";
        CreateLinkCommand command = new CreateLinkCommand(
                CreationIdempotencyKey.parseRequest(idempotencyKey),
                TargetSystem.ROUND,
                "/room/wxyz-2345-6789",
                LinkPurpose.MEETING_ENTRY,
                null,
                null
        );
        ExecutorService executor = Executors.newFixedThreadPool(2);
        controllableReservationPort.arm(2, false);

        try {
            Future<CreatedLinkResult> firstFuture = executor.submit(() ->
                    controllablePublicLinkOriginPort.withOrigin(
                            "https://go-a.example",
                            () -> smartLinkUseCase.createLink(command)
                    ));
            Future<CreatedLinkResult> secondFuture = executor.submit(() ->
                    controllablePublicLinkOriginPort.withOrigin(
                            "https://go-b.example",
                            () -> smartLinkUseCase.createLink(command)
                    ));

            controllableReservationPort.awaitAllEntered();
            controllableReservationPort.awaitFirstOwnerReserved();
            awaitReservationInsertWaiters(1);
            controllableReservationPort.releaseFirstOwner();

            CreatedLinkResult first = firstFuture.get(20, TimeUnit.SECONDS);
            CreatedLinkResult second = secondFuture.get(20, TimeUnit.SECONDS);
            String storedOrigin = jdbcTemplate.queryForObject(
                    """
                            SELECT public_origin
                            FROM link_creation_requests
                            WHERE idempotency_key_hash = ?
                            """,
                    String.class,
                    linkCodePort.hashIdempotencyKey(idempotencyKey)
            );
            URI expectedShortUrl = URI.create(
                    storedOrigin + first.shortUrl().getRawPath()
            );

            assertThat(storedOrigin)
                    .isIn("https://go-a.example", "https://go-b.example");
            assertThat(first.link().id()).isEqualTo(second.link().id());
            assertThat(first.shortUrl()).isEqualTo(expectedShortUrl);
            assertThat(second.shortUrl()).isEqualTo(expectedShortUrl);
            assertThat(List.of(first, second))
                    .filteredOn(result -> !result.replayed())
                    .hasSize(1);
        } finally {
            controllableReservationPort.releaseFirstOwner();
            controllableReservationPort.reset();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("첫 생성 트랜잭션이 롤백되면 대기 요청 하나가 이어서 처리하고 나머지는 재시도할 수 있다")
    void transfersOwnershipAfterWinnerRollback() throws Exception {
        CreateLinkCommand command = new CreateLinkCommand(
                CreationIdempotencyKey.parseRequest(ROLLBACK_IDEMPOTENCY_KEY),
                TargetSystem.ROUND,
                "/room/mnpq-rstu-vwxy",
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
                                .isInstanceOf(TransientDataAccessException.class);
                        retryableFailures++;
                    }
                }
            }

            assertThat(rollbackFailures).isEqualTo(1);
            assertThat(retryableFailures).isBetween(0, CONCURRENCY - 2);
            assertThat(results).isNotEmpty();
            assertThat(results.size() + rollbackFailures + retryableFailures)
                    .isEqualTo(CONCURRENCY);
            CreatedLinkResult first = results.getFirst();
            assertThat(results)
                    .extracting(result -> result.link().id())
                    .containsOnly(first.link().id());
            assertThat(results)
                    .extracting(CreatedLinkResult::shortUrl)
                    .containsOnly(first.shortUrl());
            assertThat(results)
                    .filteredOn(result -> !result.replayed())
                    .hasSize(1);

            CreatedLinkResult replay = smartLinkUseCase.createLink(command);
            assertThat(replay.replayed()).isTrue();
            assertThat(replay.link().id()).isEqualTo(first.link().id());
            assertThat(replay.shortUrl()).isEqualTo(first.shortUrl());
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

    private void awaitReservationInsertWaiters(int expectedWaiters) {
        await().atMost(10, TimeUnit.SECONDS)
                .pollInterval(25, TimeUnit.MILLISECONDS)
                .until(() -> jdbcTemplate.queryForObject(
                        """
                                SELECT COUNT(*)
                                FROM information_schema.PROCESSLIST
                                WHERE DB = DATABASE()
                                  AND COMMAND = 'Query'
                                  AND INFO LIKE 'INSERT IGNORE INTO link_creation_requests%'
                                """,
                        Long.class
                ) >= expectedWaiters);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ConcurrencyTestConfiguration {

        @Bean
        @Primary
        Clock farFutureClock() {
            return Clock.fixed(FAR_FUTURE_NOW, ZoneOffset.UTC);
        }

        @Bean
        @Primary
        ControllableReservationPort controllableReservationPort(
                @Qualifier("linkCreationReservationPersistenceAdapter")
                LinkCreationReservationPort delegate
        ) {
            return new ControllableReservationPort(delegate);
        }

        @Bean
        @Primary
        ControllablePublicLinkOriginPort controllablePublicLinkOriginPort() {
            return new ControllablePublicLinkOriginPort();
        }
    }

    static final class ControllablePublicLinkOriginPort implements PublicLinkOriginPort {

        private static final PublicLinkOrigin DEFAULT_ORIGIN =
                new PublicLinkOrigin(URI.create("https://go.example"));

        private final ThreadLocal<PublicLinkOrigin> currentOrigin = new ThreadLocal<>();

        @Override
        public PublicLinkOrigin current() {
            PublicLinkOrigin configuredOrigin = currentOrigin.get();
            return configuredOrigin == null ? DEFAULT_ORIGIN : configuredOrigin;
        }

        <T> T withOrigin(String origin, Callable<T> action) throws Exception {
            currentOrigin.set(new PublicLinkOrigin(URI.create(origin)));
            try {
                return action.call();
            } finally {
                currentOrigin.remove();
            }
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

        @Override
        public java.util.Optional<Reservation> find(String idempotencyKeyHash) {
            return delegate.find(idempotencyKeyHash);
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
                String publicOrigin,
                String keyId,
                Instant createdAt
        ) {
            allEntered.countDown();
            Reservation reservation = delegate.reserve(
                    idempotencyKeyHash,
                    proposedLinkId,
                    publicOrigin,
                    keyId,
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
