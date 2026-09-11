package com.personal.batongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.adapter.out.external.link.LinkCodeProperties;
import com.personal.batongo.adapter.out.external.link.SecureLinkCodeAdapter;
import com.personal.batongo.application.link.CreationIdempotencyKey;
import com.personal.batongo.application.link.LinkCodeKeyGuard;
import com.personal.batongo.application.link.SmartLinkService;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreateLinkCommand;
import com.personal.batongo.application.link.port.out.LinkCodeKeyGuardPort;
import com.personal.batongo.application.link.port.out.LinkCreationReservationPort;
import com.personal.batongo.application.link.port.out.PublicLinkOriginPort;
import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.application.link.port.out.TargetUrlPort;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.personal.batongo.application.link.error.LinkPurgedException;
import com.personal.batongo.application.link.error.LinkNotFoundException;
import com.personal.batongo.application.link.error.IdempotencyKeyConflictException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@Tag("mysql")
@Testcontainers
@SpringBootTest(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://identity.example/jwks",
        "spring.security.oauth2.resourceserver.jwt.audiences=baton-go",
        "baton-go.link-code.secret=test-legacy-key-with-at-least-thirty-two-characters",
        "baton-go.link-code.keys.k202609=test-current-key-with-at-least-thirty-two-characters",
        "baton-go.public-base-url=https://go.example",
        "baton-go.targets.baton-base-url=https://baton.example",
        "baton-go.targets.round-base-url=https://baton.example"
})
class LinkRetentionIntegrationTest {

    private static final String LEGACY = "test-legacy-key-with-at-least-thirty-two-characters";
    private static final String CURRENT = "test-current-key-with-at-least-thirty-two-characters";

    @Container
    @ServiceConnection(name = "mysql")
    static final MySQLContainer MYSQL = new MySQLContainer(MySqlTestImage.NAME)
            .withUrlParam("connectTimeout", "3000").withUrlParam("socketTimeout", "30000");

    @Autowired private SmartLinkUseCase links;
    @Autowired private com.personal.batongo.application.link.port.out.LinkRetentionPort retention;
    @Autowired private SmartLinkRepository repository;
    @Autowired private LinkCreationReservationPort reservations;
    @Autowired private LinkCodeKeyGuardPort guardPort;
    @Autowired private PublicLinkOriginPort publicOrigin;
    @Autowired private TargetUrlPort targets;
    @Autowired private Clock clock;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearLinks() {
        jdbc.update("DELETE FROM link_creation_requests");
        jdbc.update("DELETE FROM smart_links");
    }

    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant EXPIRED = CREATED.plus(Duration.ofDays(1));

    @Test
    @DisplayName("보존 기간이 지난 종료 링크만 나누어 정리하고 생성 키를 재사용하지 않는다")
    void purgesInChunksAndPreservesIdempotency() {
        var expiredCommand = command(EXPIRED);
        var expired = create(expiredCommand);
        create(command(EXPIRED));
        var revokedCommand = command(null);
        var revoked = create(revokedCommand);
        jdbc.update("UPDATE smart_links SET revoked_at = ? WHERE id = UUID_TO_BIN(?)",
                java.time.LocalDateTime.ofInstant(EXPIRED, ZoneOffset.UTC), revoked.link().id().toString());
        var active = create(command(null));

        assertThat(purge(EXPIRED.minusNanos(1000), 100)).isZero();
        assertThat(purge(EXPIRED, 1)).isEqualTo(1);
        assertThat(purge(EXPIRED, 100)).isEqualTo(2);
        assertThat(purge(EXPIRED, 1)).isZero();
        assertThat(links.getLink(active.link().id()).id()).isEqualTo(active.link().id());
        assertThatThrownBy(() -> links.createLink(expiredCommand)).isInstanceOf(LinkPurgedException.class);
        assertThatThrownBy(() -> links.createLink(new CreateLinkCommand(expiredCommand.idempotencyKey(),
                TargetSystem.ROUND, "/room/bcde-fghj-kmnp", LinkPurpose.MEETING_ENTRY, null, EXPIRED)))
                .isInstanceOf(IdempotencyKeyConflictException.class);
        assertThatThrownBy(() -> links.resolveLink(expired.shortUrl().getPath().substring(3)))
                .isInstanceOf(LinkNotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM link_creation_requests WHERE purged_at IS NOT NULL AND public_origin IS NULL",
                Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM smart_links", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("묶음 삭제 중 실패하면 앞서 삭제한 링크와 모든 예약 변경을 되돌린다")
    void rollsBackEntireBatchWhenDeleteFails() {
        create(command(EXPIRED.minusSeconds(1)));
        var second = create(command(EXPIRED));
        jdbc.execute("""
                CREATE TABLE retention_delete_blocker (
                    link_id BINARY(16) PRIMARY KEY,
                    FOREIGN KEY (link_id) REFERENCES smart_links(id)
                )
                """);
        try {
            jdbc.update("INSERT INTO retention_delete_blocker VALUES (UUID_TO_BIN(?))",
                    second.link().id().toString());
            assertThatThrownBy(() -> purge(EXPIRED, 100)).isInstanceOf(DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM smart_links", Integer.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM link_creation_requests
                    WHERE purged_at IS NULL AND request_hash IS NULL AND public_origin IS NOT NULL
                    """, Integer.class)).isEqualTo(2);
        } finally {
            jdbc.execute("DROP TABLE retention_delete_blocker");
        }
        assertThat(purge(EXPIRED, 100)).isEqualTo(2);
    }

    @Test
    @DisplayName("기존 결과를 조회 중인 예약은 건너뛰고 조회 완료 뒤 다음 실행에서 정리한다")
    void skipsReplayHeldReservation() throws Exception {
        var command = command(EXPIRED);
        var created = create(command);
        var held = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var replay = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                var codes = new SecureLinkCodeAdapter(new LinkCodeProperties(LEGACY));
                reservations.find(codes.hashIdempotencyKey(command.idempotencyKey().value())).orElseThrow();
                held.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("재생 해제 대기 초과");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return links.createLink(command);
            }));
            try {
                assertThat(held.await(10, TimeUnit.SECONDS)).isTrue();
                assertThat(purge(EXPIRED, 100)).isZero();
            } finally {
                release.countDown();
            }
            assertThat(replay.get(10, TimeUnit.SECONDS).shortUrl()).isEqualTo(created.shortUrl());
        }
        assertThat(purge(EXPIRED, 100)).isEqualTo(1);
    }

    @Test
    @DisplayName("이전 키의 모든 링크를 정리한 뒤 키를 제거해도 재시도는 정리 완료로 응답한다")
    void allowsRetirementWithoutReissuingPurgedLink() {
        var command = command(EXPIRED);
        create(command);
        assertThat(purge(EXPIRED, 100)).isEqualTo(1);
        var codes = new SecureLinkCodeAdapter(new LinkCodeProperties(null, "k202609", Map.of("k202609", CURRENT)));
        var guard = new LinkCodeKeyGuard(codes, guardPort);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> guard.verifyOrBind());
        var service = new SmartLinkService(repository, reservations, codes, guard, publicOrigin, targets, clock);
        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> service.createLink(command)))
                .isInstanceOf(LinkPurgedException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM smart_links", Integer.class)).isZero();
    }

    private SmartLinkUseCase.CreatedLinkResult create(CreateLinkCommand command) {
        var codes = new SecureLinkCodeAdapter(new LinkCodeProperties(LEGACY));
        var service = new SmartLinkService(repository, reservations, codes, new LinkCodeKeyGuard(codes, guardPort),
                publicOrigin, targets, Clock.fixed(CREATED, ZoneOffset.UTC));
        return new TransactionTemplate(transactionManager).execute(status -> service.createLink(command));
    }

    private int purge(Instant cutoff, int batchSize) {
        return new TransactionTemplate(transactionManager).execute(status ->
                retention.purgeRetiredLinks(cutoff, cutoff.plus(Duration.ofDays(30)), batchSize));
    }

    private CreateLinkCommand command(Instant expiresAt) {
        return new CreateLinkCommand(CreationIdempotencyKey.parseRequest(UUID.randomUUID().toString()),
                TargetSystem.ROUND, "/room/abcd-efgh-jkmn", LinkPurpose.MEETING_ENTRY, null, expiresAt);
    }
}
