package com.personal.batongo.bootstrap.guard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.personal.batongo.BatonGoApplication;
import com.personal.batongo.MySqlTestImage;
import com.personal.batongo.application.link.CreationIdempotencyKey;
import com.personal.batongo.application.link.LinkCodeDerivationIdentity;
import com.personal.batongo.application.link.LinkCodeKeyRingIdentity;
import java.util.Map;
import com.personal.batongo.application.link.LinkCodeKeyGuard;
import com.personal.batongo.application.link.error.LinkCodeKeyBindingException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreateLinkCommand;
import com.personal.batongo.application.link.port.out.LinkCodeKeyGuardPort;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import com.personal.batongo.bootstrap.guard.ExistingDatabaseLinkCodeKeyBinder.BindingResult;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import java.sql.Connection;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
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
@SpringBootTest(classes = BatonGoApplication.class, properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://identity.example",
        "spring.security.oauth2.resourceserver.jwt.audiences=baton-go",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://identity.example/jwks",
        "baton-go.link-code.secret=test-link-code-secret-that-is-separate-and-long-enough",
        "baton-go.public-base-url=https://go.example",
        "baton-go.targets.baton-base-url=https://baton.example",
        "baton-go.targets.round-base-url=https://baton.example"
})
class LinkCodeKeyGuardIntegrationTest {

    private static final String CANONICAL_BATON_TARGET =
            "/teams/8e448211-66ae-44ab-9888-c4960648c22b"
                    + "/seasons/713d9cb7-2842-4f9f-b3cc-e31d98c6238a";

    @Container
    @ServiceConnection(name = "mysql")
    static final MySQLContainer MYSQL = new MySQLContainer(MySqlTestImage.NAME)
            .withUrlParam("connectTimeout", "3000")
            .withUrlParam("socketTimeout", "30000");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LinkCodePort linkCodePort;

    @Autowired
    private LinkCodeKeyGuard linkCodeKeyGuard;

    @Autowired
    private LinkCodeKeyGuardPort linkCodeKeyGuardPort;

    @Autowired
    @Qualifier("linkCodeKeyStartupValidator")
    private ApplicationRunner startupValidator;

    @Autowired
    private SmartLinkUseCase smartLinkUseCase;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DataSource dataSource;

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
    void bindsEmptyDatabaseDuringStartupValidation() throws Exception {
        unbind();

        startupValidator.run(new DefaultApplicationArguments(new String[0]));

        assertThat(storedIdentity()).isEqualTo(linkCodePort.derivationIdentity());
    }

    @Test
    @DisplayName("빈 데이터베이스라도 생성 경로는 미결합 HMAC 키를 자동 결합하지 않는다")
    void rejectsUnboundIdentityOnCreationPath() {
        unbind();

        assertThatThrownBy(() -> smartLinkUseCase.createLink(command(
                "40743730-ea7e-4d9d-a490-df10726c4926"
        )))
                .isInstanceOf(LinkCodeKeyBindingException.class);

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
                .hasMessageNotContaining(current.hmacFingerprint())
                .hasMessageNotContaining(different.hmacFingerprint());
        assertThatThrownBy(() -> smartLinkUseCase.createLink(command(
                "4ab8831d-78c6-47c2-b984-e2723e818245"
        )))
                .isInstanceOf(LinkCodeKeyBindingException.class);
        assertThat(linkCount()).isZero();
        assertThat(reservationCount()).isZero();
    }

    @Test
    @DisplayName("HMAC 보호 행이 유실되면 시작과 생성 모두 안전하게 실패한다")
    void rejectsMissingGuardRowAtStartupAndCreation() {
        jdbcTemplate.update("DELETE FROM link_code_key_guard WHERE guard_id = 1");

        assertThatThrownBy(() -> startupValidator.run(
                new DefaultApplicationArguments(new String[0])
        ))
                .isInstanceOf(LinkCodeKeyBindingException.class);
        assertThatThrownBy(() -> smartLinkUseCase.createLink(command(
                "adbb1c82-4ed5-461e-9cb8-431bb5e6fda2"
        )))
                .isInstanceOf(LinkCodeKeyBindingException.class);

        assertThat(linkCount()).isZero();
        assertThat(reservationCount()).isZero();
        assertThat(storedIdentity()).isNull();
    }

    @Test
    @DisplayName("기존 링크가 있는 데이터베이스의 미결합 sentinel은 자동 결합하지 않는다")
    void rejectsUnboundDatabaseWithExistingLink() {
        smartLinkUseCase.createLink(command(
                "0508cdd2-3b3d-4728-820a-36c135531574"
        ));
        unbind();

        assertThatThrownBy(() -> linkCodeKeyGuard.verifyOrBind())
                .isInstanceOf(LinkCodeKeyBindingException.class);

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
                .isInstanceOf(LinkCodeKeyBindingException.class);

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
        CountDownLatch firstBound = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<LinkCodeDerivationIdentity> firstFuture = executor.submit(
                    () -> bindAndHoldTransaction(first, firstBound, releaseFirst)
            );
            if (!firstBound.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("첫 HMAC identity 결합을 기다리지 못했습니다");
            }
            Future<LinkCodeDerivationIdentity> secondFuture = executor.submit(
                    () -> bindInTransaction(second, secondStarted)
            );
            if (!secondStarted.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("두 번째 HMAC identity 결합 시작을 기다리지 못했습니다");
            }

            releaseFirst.countDown();

            assertThat(firstFuture.get(20, TimeUnit.SECONDS)).isEqualTo(first);
            assertThatThrownBy(() -> secondFuture.get(20, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause()
                    .isInstanceOf(LinkCodeKeyBindingException.class);
            assertThat(storedIdentity()).isEqualTo(first);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("기존 데이터베이스는 보관한 canary가 현재 HMAC 키를 증명하면 한 번 결합된다")
    void bindsExistingDatabaseAfterCanaryVerification() throws Exception {
        String canaryIdempotencyKey = "60fa8eb4-e104-459d-aa45-e4a07831998e";
        smartLinkUseCase.createLink(command(canaryIdempotencyKey));
        unbind();

        BindingResult result = bindExistingDatabase(canaryIdempotencyKey);

        assertThat(result).isEqualTo(BindingResult.BOUND);
        assertThat(storedIdentity()).isEqualTo(linkCodePort.derivationIdentity());
        assertThat(linkCount()).isEqualTo(1L);
        assertThat(reservationCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("기존 MySQL 데이터의 uppercase version 7 canary는 과거 규칙으로 정규화해 결합한다")
    void bindsExistingDatabaseWithLegacyUppercaseVersionSevenCanary() throws Exception {
        String canonicalCanary = "019ae750-9234-7abc-8def-123456789abc";
        insertLegacyCreation(canonicalCanary);
        unbind();

        BindingResult result = bindExistingDatabase(
                canonicalCanary.toUpperCase(Locale.ROOT)
        );

        assertThat(result).isEqualTo(BindingResult.BOUND);
        assertThat(storedIdentity()).isEqualTo(linkCodePort.derivationIdentity());
        assertThat(linkCount()).isEqualTo(1L);
        assertThat(reservationCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("기존 데이터베이스는 HMAC 키를 증명하지 못하는 canary로 결합하지 않는다")
    void rejectsExistingDatabaseWhenCanaryDoesNotMatch() {
        String storedCanary = "527d7731-0e2a-49f9-a1cc-302673d4253f";
        String wrongCanary = "a95e14e3-92aa-490a-9afe-65c88dbc680e";
        smartLinkUseCase.createLink(command(storedCanary));
        unbind();

        assertThatThrownBy(() -> bindExistingDatabase(wrongCanary))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(storedCanary)
                .hasMessageNotContaining(wrongCanary)
                .hasMessageNotContaining(linkCodePort.derivationIdentity().hmacFingerprint());
        assertThat(storedIdentity()).isNull();
    }

    @Test
    @DisplayName("같은 identity에 이미 결합된 데이터베이스는 canary 검증 뒤 멱등하게 확인된다")
    void confirmsAlreadyBoundDatabaseAfterCanaryVerification() throws Exception {
        String canaryIdempotencyKey = "b46dd6bc-91f1-4f73-81e4-ceb63a438f66";
        smartLinkUseCase.createLink(command(canaryIdempotencyKey));

        BindingResult result = bindExistingDatabase(canaryIdempotencyKey);

        assertThat(result).isEqualTo(BindingResult.ALREADY_BOUND);
        assertThat(storedIdentity()).isEqualTo(linkCodePort.derivationIdentity());
    }

    private LinkCodeDerivationIdentity bindAndHoldTransaction(
            LinkCodeDerivationIdentity identity,
            CountDownLatch bound,
            CountDownLatch release
    ) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> {
                    linkCodeKeyGuardPort.verifyOrBind(new LinkCodeKeyRingIdentity("legacy", Map.of("legacy", identity)));
                    bound.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "첫 HMAC identity 결합 트랜잭션을 해제하지 못했습니다"
                            );
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "첫 HMAC identity 결합 트랜잭션 대기가 중단됐습니다",
                                exception
                        );
                    }
                }
        );
        return identity;
    }

    private LinkCodeDerivationIdentity bindInTransaction(
            LinkCodeDerivationIdentity identity,
            CountDownLatch started
    ) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> {
                    started.countDown();
                    linkCodeKeyGuardPort.verifyOrBind(new LinkCodeKeyRingIdentity("legacy", Map.of("legacy", identity)));
                }
        );
        return identity;
    }

    private BindingResult bindExistingDatabase(String canaryIdempotencyKey) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                BindingResult result = new ExistingDatabaseLinkCodeKeyBinder().bind(
                        connection,
                        linkCodePort,
                        CreationIdempotencyKey.parseRequest(canaryIdempotencyKey)
                );
                connection.commit();
                return result;
            } catch (RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private void insertLegacyCreation(String canonicalCanary) {
        String linkId = "2df34d5c-2d80-4ae3-a82c-16ae447b3a61";
        jdbcTemplate.update(
                """
                        INSERT INTO smart_links (
                            id,
                            code_hash,
                            target_system,
                            target_path,
                            purpose,
                            not_before,
                            expires_at,
                            revoked_at,
                            created_at,
                            version
                        ) VALUES (
                            UUID_TO_BIN(?), ?, 'BATON', ?, 'NAVIGATION',
                            NULL, NULL, NULL, UTC_TIMESTAMP(6), 0
                        )
                        """,
                linkId,
                linkCodePort.issue(canonicalCanary).codeHash(),
                CANONICAL_BATON_TARGET
        );
        jdbcTemplate.update(
                """
                        INSERT INTO link_creation_requests (
                            idempotency_key_hash,
                            link_id,
                            created_at
                        ) VALUES (?, UUID_TO_BIN(?), UTC_TIMESTAMP(6))
                        """,
                linkCodePort.hashIdempotencyKey(canonicalCanary),
                linkId
        );
    }

    private CreateLinkCommand command(String idempotencyKey) {
        return new CreateLinkCommand(
                CreationIdempotencyKey.parseRequest(idempotencyKey),
                TargetSystem.BATON,
                CANONICAL_BATON_TARGET,
                LinkPurpose.NAVIGATION,
                null,
                null
        );
    }

    private void clearLinkData() {
        jdbcTemplate.update("DELETE FROM link_creation_requests");
        jdbcTemplate.update("DELETE FROM smart_links");
        jdbcTemplate.update("DELETE FROM link_code_keys");
    }

    private void bind(LinkCodeDerivationIdentity identity) {
        jdbcTemplate.update("DELETE FROM link_code_keys");
        jdbcTemplate.update(
                "INSERT INTO link_code_keys (key_id, derivation_version, key_fingerprint) VALUES ('legacy', ?, ?)",
                identity.version(), identity.hmacFingerprint()
        );
        jdbcTemplate.update(
                """
                        INSERT INTO link_code_key_guard (
                            guard_id,
                            derivation_version,
                            key_fingerprint
                        ) VALUES (1, ?, ?) AS new
                        ON DUPLICATE KEY UPDATE
                            derivation_version = new.derivation_version,
                            key_fingerprint = new.key_fingerprint
                        """,
                identity.version(),
                identity.hmacFingerprint()
        );
    }

    private void unbind() {
        jdbcTemplate.update("DELETE FROM link_code_keys");
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
