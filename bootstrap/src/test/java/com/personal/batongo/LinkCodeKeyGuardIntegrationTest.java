package com.personal.batongo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.jdbc.JdbcTestUtils.countRowsInTable;
import static org.springframework.test.jdbc.JdbcTestUtils.deleteFromTables;

import com.personal.batongo.application.link.CreationIdempotencyKey;
import com.personal.batongo.application.link.LinkCodeDerivationIdentity;
import com.personal.batongo.application.link.LinkCodeKeyRingIdentity;
import java.util.Map;
import com.personal.batongo.application.link.error.LinkCodeKeyBindingException;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase;
import com.personal.batongo.application.link.port.in.SmartLinkUseCase.CreateLinkCommand;
import com.personal.batongo.application.link.port.out.LinkCodeKeyGuardPort;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
        "baton-go.link-code.keys.default=test-link-code-secret-that-is-separate-and-long-enough",
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
    static final MySQLContainer MYSQL = MySqlTestImage.container();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private LinkCodePort linkCodePort;

    @Autowired
    private LinkCodeKeyGuardPort linkCodeKeyGuardPort;

    @Autowired
    @Qualifier("linkCodeKeyStartupValidator")
    private ApplicationRunner startupValidator;

    @Autowired
    private SmartLinkUseCase smartLinkUseCase;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void resetDatabase() {
        deleteFromTables(jdbcTemplate, "link_creation_requests", "smart_links");
        bind(linkCodePort.keyRingIdentity().keys().get("default"));
    }

    @Test
    @DisplayName("빈 데이터베이스는 시작 검증에서 현재 HMAC 키 정보를 등록한다")
    void bindsEmptyDatabaseDuringStartupValidation() throws Exception {
        unbind();

        startupValidator.run(new DefaultApplicationArguments(new String[0]));

        assertThat(storedIdentity()).isEqualTo(linkCodePort.keyRingIdentity().keys().get("default"));
    }

    @Test
    @DisplayName("빈 데이터베이스라도 생성 요청 중에는 HMAC 키 정보를 자동 등록하지 않는다")
    void rejectsUnboundIdentityOnCreationPath() {
        unbind();

        assertThatThrownBy(() -> smartLinkUseCase.createLink(command(
                "40743730-ea7e-4d9d-a490-df10726c4926"
        )))
                .isInstanceOf(LinkCodeKeyBindingException.class);

        assertThat(countRowsInTable(jdbcTemplate, "smart_links")).isZero();
        assertThat(countRowsInTable(jdbcTemplate, "link_creation_requests")).isZero();
        assertThat(storedIdentity()).isNull();
    }

    @Test
    @DisplayName("저장된 HMAC 키 정보가 다르면 시작과 생성을 거부한다")
    void rejectsMismatchedIdentityAtStartupAndCreation() {
        LinkCodeDerivationIdentity current = linkCodePort.keyRingIdentity().keys().get("default");
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
        assertThat(countRowsInTable(jdbcTemplate, "smart_links")).isZero();
        assertThat(countRowsInTable(jdbcTemplate, "link_creation_requests")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            """
            INSERT INTO smart_links (id, code_hash, target_system, target_path, purpose, created_at)
            VALUES (UUID_TO_BIN('0508cdd2-3b3d-4728-820a-36c135531574'), REPEAT('d', 64),
                    'ROUND', '/room/abcd-efgh-jkmn', 'MEETING_ENTRY', UTC_TIMESTAMP(6))
            """,
            """
            INSERT INTO link_creation_requests (
                idempotency_key_hash, link_id, public_origin, key_id, request_hash, created_at
            ) VALUES (REPEAT('d', 64), UUID_TO_BIN('9752e1df-8f49-480c-87b4-e871b28ee0c4'),
                    'https://go.example', 'default', REPEAT('e', 64), UTC_TIMESTAMP(6))
            """
    })
    @DisplayName("링크나 생성 예약 중 하나만 있어도 키 정보가 없으면 키를 자동 등록하지 않는다")
    void rejectsUnboundDatabaseWithStoredLinkData(String insertStoredData) {
        jdbcTemplate.update(insertStoredData);
        unbind();

        assertThatThrownBy(() -> startupValidator.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(LinkCodeKeyBindingException.class);

        assertThat(countRowsInTable(jdbcTemplate, "smart_links")
                + countRowsInTable(jdbcTemplate, "link_creation_requests")).isEqualTo(1);
        assertThat(storedIdentity()).isNull();
    }

    @Test
    @DisplayName("서로 다른 HMAC 키 정보를 동시에 처음 등록하면 하나만 성공한다")
    void serializesConcurrentInitialBinding() throws Exception {
        unbind();
        LinkCodeDerivationIdentity first = linkCodePort.keyRingIdentity().keys().get("default");
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
                throw new IllegalStateException("첫 HMAC 키 정보 등록을 기다리지 못했습니다");
            }
            Future<LinkCodeDerivationIdentity> secondFuture = executor.submit(
                    () -> bindInTransaction(second, secondStarted)
            );
            if (!secondStarted.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("두 번째 HMAC 키 정보 등록 시작을 기다리지 못했습니다");
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

    private LinkCodeDerivationIdentity bindAndHoldTransaction(
            LinkCodeDerivationIdentity identity,
            CountDownLatch bound,
            CountDownLatch release
    ) {
        new TransactionTemplate(transactionManager).executeWithoutResult(
                status -> {
                    linkCodeKeyGuardPort.verifyOrBind(new LinkCodeKeyRingIdentity("default", Map.of("default", identity)));
                    bound.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException(
                                    "첫 HMAC 키 정보 등록 트랜잭션을 해제하지 못했습니다"
                            );
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                "첫 HMAC 키 정보 등록 트랜잭션 대기가 중단됐습니다",
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
                    linkCodeKeyGuardPort.verifyOrBind(new LinkCodeKeyRingIdentity("default", Map.of("default", identity)));
                }
        );
        return identity;
    }

    private CreateLinkCommand command(String idempotencyKey) {
        return new CreateLinkCommand(
                new CreationIdempotencyKey(idempotencyKey),
                TargetSystem.BATON,
                CANONICAL_BATON_TARGET,
                LinkPurpose.NAVIGATION,
                null,
                null
        );
    }

    private void bind(LinkCodeDerivationIdentity identity) {
        jdbcTemplate.update("DELETE FROM link_code_keys");
        jdbcTemplate.update(
                "INSERT INTO link_code_keys (key_id, derivation_version, key_fingerprint) VALUES ('default', ?, ?)",
                identity.version(), identity.hmacFingerprint()
        );
    }

    private void unbind() {
        jdbcTemplate.update("DELETE FROM link_code_keys");
    }

    private LinkCodeDerivationIdentity storedIdentity() {
        return jdbcTemplate.query(
                "SELECT derivation_version, key_fingerprint FROM link_code_keys WHERE key_id = 'default'",
                resultSet -> resultSet.next()
                        ? new LinkCodeDerivationIdentity(
                                resultSet.getString("derivation_version"),
                                resultSet.getString("key_fingerprint")
                        )
                        : null
        );
    }
}
