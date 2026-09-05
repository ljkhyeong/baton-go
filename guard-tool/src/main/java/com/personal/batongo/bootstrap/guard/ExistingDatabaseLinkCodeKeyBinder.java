package com.personal.batongo.bootstrap.guard;

import com.personal.batongo.application.link.CreationIdempotencyKey;
import com.personal.batongo.application.link.LinkCodeDerivationIdentity;
import com.personal.batongo.application.link.port.out.IssuedLinkCode;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 기존 데이터베이스의 HMAC guard를 검증 후 한 번만 결합하는 JDBC 도구입니다. */
final class ExistingDatabaseLinkCodeKeyBinder {

    private static final int SINGLETON_GUARD_ID = 1;
    private static final String SAFE_MESSAGE =
            "기존 데이터베이스의 링크 코드 키 결합 검증에 실패했습니다";

    BindingResult bind(
            DataSource dataSource,
            LinkCodePort linkCodePort,
            CreationIdempotencyKey canaryIdempotencyKey
    ) {
        try {
            JdbcClient jdbcClient = JdbcClient.create(dataSource);
            return new TransactionTemplate(new JdbcTransactionManager(dataSource)).execute(status -> {
                LinkCodeDerivationIdentity identity = linkCodePort.derivationIdentity();
                GuardState guardState = lockGuard(jdbcClient);
                verifyCanary(jdbcClient, linkCodePort, canaryIdempotencyKey);

                if (guardState.isBound()) {
                    requireMatchingIdentity(guardState, identity);
                    return BindingResult.ALREADY_BOUND;
                }
                if (!guardState.isUnbound()) {
                    throw unsafeState();
                }

                bindGuard(jdbcClient, identity);
                return BindingResult.BOUND;
            });
        } catch (RuntimeException exception) {
            throw unsafeState();
        }
    }

    private GuardState lockGuard(JdbcClient jdbcClient) {
        return jdbcClient.sql("""
                        SELECT derivation_version AS version,
                               key_fingerprint AS fingerprint
                        FROM link_code_key_guard
                        WHERE guard_id = ?
                        FOR UPDATE
                        """)
                .param(SINGLETON_GUARD_ID)
                .query(GuardState.class)
                .single();
    }

    private void verifyCanary(
            JdbcClient jdbcClient,
            LinkCodePort linkCodePort,
            CreationIdempotencyKey canaryIdempotencyKey
    ) {
        String idempotencyKey = canaryIdempotencyKey.value();
        String idempotencyKeyHash = linkCodePort.hashIdempotencyKey(idempotencyKey);
        IssuedLinkCode issuedLinkCode = linkCodePort.issue(idempotencyKey);

        String storedCodeHash = jdbcClient.sql("""
                        SELECT smart_links.code_hash
                        FROM link_creation_requests
                        JOIN smart_links
                          ON smart_links.id = link_creation_requests.link_id
                        WHERE link_creation_requests.idempotency_key_hash = ?
                        FOR SHARE
                        """)
                .param(idempotencyKeyHash)
                .query(String.class)
                .single();
        if (!MessageDigest.isEqual(
                issuedLinkCode.codeHash().getBytes(StandardCharsets.US_ASCII),
                storedCodeHash.getBytes(StandardCharsets.US_ASCII)
        )) {
            throw unsafeState();
        }
    }

    private void requireMatchingIdentity(
            GuardState guardState,
            LinkCodeDerivationIdentity identity
    ) {
        if (!identity.version().equals(guardState.version())
                || !identity.hmacFingerprint().equals(guardState.fingerprint())) {
            throw unsafeState();
        }
    }

    private void bindGuard(
            JdbcClient jdbcClient,
            LinkCodeDerivationIdentity identity
    ) {
        jdbcClient.sql("""
                        UPDATE link_code_key_guard
                        SET derivation_version = ?, key_fingerprint = ?
                        WHERE guard_id = ?
                        """)
                .params(
                        identity.version(),
                        identity.hmacFingerprint(),
                        SINGLETON_GUARD_ID
                )
                .update();
    }

    private IllegalStateException unsafeState() {
        return new IllegalStateException(SAFE_MESSAGE);
    }

    enum BindingResult {
        BOUND,
        ALREADY_BOUND
    }

    private record GuardState(String version, String fingerprint) {

        private boolean isUnbound() {
            return version == null && fingerprint == null;
        }

        private boolean isBound() {
            return version != null && fingerprint != null;
        }

        @Override
        public String toString() {
            return "GuardState[redacted]";
        }
    }

}
