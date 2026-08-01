package com.personal.batongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.application.link.CreationIdempotencyKey;
import com.personal.batongo.application.link.LinkCodeDerivationIdentity;
import com.personal.batongo.application.link.LinkCodeKeyGuard;
import com.personal.batongo.application.link.error.LinkCodeKeyBindingException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreateLinkCommand;
import com.personal.batongo.application.link.port.out.LinkCodeKeyGuardPort;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import com.personal.batongo.bootstrap.LinkCodeKeyStartupValidator;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
class LinkCodeKeyGuardIntegrationTest {

    private static final String SAFE_MESSAGE =
            "링크 코드 파생 키를 현재 데이터베이스에 안전하게 결합할 수 없습니다";

    @Container
    @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LinkCodePort linkCodePort;

    @Autowired
    private LinkCodeKeyGuard linkCodeKeyGuard;

    @Autowired
    private LinkCodeKeyGuardPort linkCodeKeyGuardPort;

    @Autowired
    private LinkCodeKeyStartupValidator startupValidator;

    @Autowired
    private SmartLinkUseCase smartLinkUseCase;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetDatabase() {
        clearLinkData();
        bind(linkCodePort.derivationIdentity());
    }

    @AfterEach
    void restoreDatabase() {
        clearLinkData();
        bind(linkCodePort.derivationIdentity());
    }

    @Test
    @DisplayName("빈 데이터베이스의 미결합 sentinel은 시작 검증에서 현재 HMAC 키에 결합된다")
    void bindsEmptyDatabaseDuringStartupValidation() {
        unbind();

        startupValidator.run(new DefaultApplicationArguments(new String[0]));

        assertThat(storedIdentity()).isEqualTo(linkCodePort.derivationIdentity());
    }

    @Test
    @DisplayName("빈 데이터베이스라도 생성 경로는 미결합 HMAC 키를 자동 결합하지 않는다")
    void rejectsUnboundIdentityOnCreationPath() {
        unbind();

        assertThatThrownBy(() -> smartLinkUseCase.createLink(command(
                "40743730-ea7e-4d9d-a490-df10726c4926",
                "/teams/unbound-create"
        )))
                .isInstanceOf(LinkCodeKeyBindingException.class)
                .hasMessage(SAFE_MESSAGE);

        assertThat(linkCount()).isZero();
        assertThat(reservationCount()).isZero();
        assertThat(storedIdentity()).isNull();
    }

    @Test
    @DisplayName("저장된 HMAC identity가 다르면 시작과 생성 모두 안전하게 실패한다")
    void rejectsMismatchedIdentityAtStartupAndCreation() {
        LinkCodeDerivationIdentity current = linkCodePort.derivationIdentity();
        LinkCodeDerivationIdentity different = new LinkCodeDerivationIdentity(
                current.version(),
                "f".repeat(64)
        );
        bind(different);

        assertThatThrownBy(() -> startupValidator.run(
                new DefaultApplicationArguments(new String[0])
        ))
                .isInstanceOf(LinkCodeKeyBindingException.class)
                .hasMessage(SAFE_MESSAGE)
                .hasMessageNotContaining(current.hmacFingerprint())
                .hasMessageNotContaining(different.hmacFingerprint());
        assertThatThrownBy(() -> smartLinkUseCase.createLink(command(
                "4ab8831d-78c6-47c2-b984-e2723e818245",
                "/teams/mismatched-key"
        )))
                .isInstanceOf(LinkCodeKeyBindingException.class)
                .hasMessage(SAFE_MESSAGE);
        assertThat(linkCount()).isZero();
        assertThat(reservationCount()).isZero();
    }

    @Test
    @DisplayName("기존 링크가 있는 데이터베이스의 미결합 sentinel은 자동 결합하지 않는다")
    void rejectsUnboundDatabaseWithExistingLink() {
        smartLinkUseCase.createLink(command(
                "0508cdd2-3b3d-4728-820a-36c135531574",
                "/teams/existing-link"
        ));
        unbind();

        assertThatThrownBy(() -> linkCodeKeyGuard.verifyOrBind())
                .isInstanceOf(LinkCodeKeyBindingException.class)
                .hasMessage(SAFE_MESSAGE);

        assertThat(linkCount()).isEqualTo(1L);
        assertThat(reservationCount()).isEqualTo(1L);
        assertThat(storedIdentity()).isNull();
    }

    @Test
    @DisplayName("기존 생성 예약만 있어도 미결합 sentinel은 자동 결합하지 않는다")
    void rejectsUnboundDatabaseWithExistingReservation() {
        jdbcTemplate.update(
                """
                        INSERT INTO link_creation_requests (
                            idempotency_key_hash,
                            link_id,
                            created_at
                        ) VALUES (?, UUID_TO_BIN(?), UTC_TIMESTAMP(6))
                        """,
                "d".repeat(64),
                "9752e1df-8f49-480c-87b4-e871b28ee0c4"
        );
        unbind();

        assertThatThrownBy(() -> linkCodeKeyGuard.verifyOrBind())
                .isInstanceOf(LinkCodeKeyBindingException.class)
                .hasMessage(SAFE_MESSAGE);

        assertThat(linkCount()).isZero();
        assertThat(reservationCount()).isEqualTo(1L);
        assertThat(storedIdentity()).isNull();
    }

    @Test
    @DisplayName("서로 다른 HMAC identity가 동시에 최초 결합하면 한 identity만 성공한다")
    void serializesConcurrentInitialBinding() throws Exception {
        unbind();
        LinkCodeDerivationIdentity first = linkCodePort.derivationIdentity();
        LinkCodeDerivationIdentity second = new LinkCodeDerivationIdentity(
                first.version(),
                "e".repeat(64)
        );
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Future<LinkCodeDerivationIdentity>> futures = List.of(
                    executor.submit(() -> bindInTransactionAfter(start, first)),
                    executor.submit(() -> bindInTransactionAfter(start, second))
            );
            start.countDown();

            int successes = 0;
            int failures = 0;
            for (Future<LinkCodeDerivationIdentity> future : futures) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException exception) {
                    assertThat(exception.getCause())
                            .isInstanceOf(LinkCodeKeyBindingException.class)
                            .hasMessage(SAFE_MESSAGE);
                    failures++;
                }
            }

            assertThat(successes).isEqualTo(1);
            assertThat(failures).isEqualTo(1);
            assertThat(storedIdentity()).isIn(first, second);
        } finally {
            executor.shutdownNow();
        }
    }

    private LinkCodeDerivationIdentity bindInTransactionAfter(
            CountDownLatch start,
            LinkCodeDerivationIdentity identity
    ) throws InterruptedException {
        start.await();
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> linkCodeKeyGuardPort.verifyOrBind(identity)
        );
        return identity;
    }

    private CreateLinkCommand command(String idempotencyKey, String targetPath) {
        return new CreateLinkCommand(
                new CreationIdempotencyKey(idempotencyKey),
                TargetSystem.BATON,
                targetPath,
                LinkPurpose.NAVIGATION,
                null,
                null
        );
    }

    private void clearLinkData() {
        jdbcTemplate.update("DELETE FROM link_creation_requests");
        jdbcTemplate.update("DELETE FROM smart_links");
    }

    private void bind(LinkCodeDerivationIdentity identity) {
        jdbcTemplate.update(
                """
                        UPDATE link_code_key_guard
                        SET derivation_version = ?, key_fingerprint = ?
                        WHERE guard_id = 1
                        """,
                identity.version(),
                identity.hmacFingerprint()
        );
    }

    private void unbind() {
        jdbcTemplate.update(
                """
                        UPDATE link_code_key_guard
                        SET derivation_version = NULL, key_fingerprint = NULL
                        WHERE guard_id = 1
                        """
        );
    }

    private LinkCodeDerivationIdentity storedIdentity() {
        return jdbcTemplate.query(
                """
                        SELECT derivation_version, key_fingerprint
                        FROM link_code_key_guard
                        WHERE guard_id = 1
                        """,
                resultSet -> {
                    if (!resultSet.next()) {
                        return null;
                    }
                    String version = resultSet.getString("derivation_version");
                    String fingerprint = resultSet.getString("key_fingerprint");
                    return version == null || fingerprint == null
                            ? null
                            : new LinkCodeDerivationIdentity(version, fingerprint);
                }
        );
    }

    private long linkCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM smart_links",
                Long.class
        );
    }

    private long reservationCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM link_creation_requests",
                Long.class
        );
    }
}
