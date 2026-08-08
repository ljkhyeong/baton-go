package com.personal.batongo.bootstrap.guard;

import com.personal.batongo.application.link.LinkCodeDerivationIdentity;
import com.personal.batongo.application.link.port.out.IssuedLinkCode;
import com.personal.batongo.application.link.port.out.LinkCodePort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** 기존 데이터베이스의 HMAC guard를 검증 후 한 번만 결합하는 JDBC 도구입니다. */
public final class ExistingDatabaseLinkCodeKeyBinder {

    private static final int SINGLETON_GUARD_ID = 1;
    private static final String SAFE_MESSAGE =
            "기존 데이터베이스의 링크 코드 키 결합 검증에 실패했습니다";

    public BindingResult bind(
            Connection connection,
            LinkCodePort linkCodePort,
            String rawCanaryIdempotencyKey
    ) {
        LegacyCanaryIdempotencyKey canaryIdempotencyKey;
        try {
            canaryIdempotencyKey = LegacyCanaryIdempotencyKey.parse(
                    rawCanaryIdempotencyKey
            );
        } catch (RuntimeException exception) {
            throw unsafeState();
        }
        return bind(connection, linkCodePort, canaryIdempotencyKey);
    }

    BindingResult bind(
            Connection connection,
            LinkCodePort linkCodePort,
            LegacyCanaryIdempotencyKey canaryIdempotencyKey
    ) {
        try {
            requireTransactionalConnection(connection);
            LinkCodeDerivationIdentity identity = linkCodePort.derivationIdentity();
            GuardState guardState = lockGuard(connection);
            verifyCanary(connection, linkCodePort, canaryIdempotencyKey);

            if (guardState.isBound()) {
                requireMatchingIdentity(guardState, identity);
                return BindingResult.ALREADY_BOUND;
            }
            if (!guardState.isUnbound()) {
                throw unsafeState();
            }

            bindGuard(connection, identity);
            return BindingResult.BOUND;
        } catch (GuardBindingToolException exception) {
            throw exception;
        } catch (SQLException | RuntimeException exception) {
            throw unsafeState();
        }
    }

    private void requireTransactionalConnection(Connection connection) throws SQLException {
        if (connection == null || connection.getAutoCommit()) {
            throw unsafeState();
        }
    }

    private GuardState lockGuard(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                        SELECT derivation_version, key_fingerprint
                        FROM link_code_key_guard
                        WHERE guard_id = ?
                        FOR UPDATE
                        """
        )) {
            statement.setInt(1, SINGLETON_GUARD_ID);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    throw unsafeState();
                }
                GuardState state = new GuardState(
                        resultSet.getString("derivation_version"),
                        resultSet.getString("key_fingerprint")
                );
                if (resultSet.next()) {
                    throw unsafeState();
                }
                return state;
            }
        }
    }

    private void verifyCanary(
            Connection connection,
            LinkCodePort linkCodePort,
            LegacyCanaryIdempotencyKey canaryIdempotencyKey
    ) throws SQLException {
        String idempotencyKey = canaryIdempotencyKey.value();
        String idempotencyKeyHash = linkCodePort.hashIdempotencyKey(idempotencyKey);
        IssuedLinkCode issuedLinkCode = linkCodePort.issue(idempotencyKey);

        try (PreparedStatement statement = connection.prepareStatement(
                """
                        SELECT smart_links.code_hash
                        FROM link_creation_requests
                        JOIN smart_links
                          ON smart_links.id = link_creation_requests.link_id
                        WHERE link_creation_requests.idempotency_key_hash = ?
                        FOR SHARE
                        """
        )) {
            statement.setString(1, idempotencyKeyHash);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()
                        || !constantTimeEquals(
                        issuedLinkCode.codeHash(),
                        resultSet.getString("code_hash")
                )
                        || resultSet.next()) {
                    throw unsafeState();
                }
            }
        }
    }

    private void requireMatchingIdentity(
            GuardState guardState,
            LinkCodeDerivationIdentity identity
    ) {
        if (!constantTimeEquals(guardState.version(), identity.version())
                || !constantTimeEquals(guardState.fingerprint(), identity.hmacFingerprint())) {
            throw unsafeState();
        }
    }

    private void bindGuard(
            Connection connection,
            LinkCodeDerivationIdentity identity
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                        UPDATE link_code_key_guard
                        SET derivation_version = ?, key_fingerprint = ?
                        WHERE guard_id = ?
                          AND derivation_version IS NULL
                          AND key_fingerprint IS NULL
                        """
        )) {
            statement.setString(1, identity.version());
            statement.setString(2, identity.hmacFingerprint());
            statement.setInt(3, SINGLETON_GUARD_ID);
            if (statement.executeUpdate() != 1) {
                throw unsafeState();
            }
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII)
        );
    }

    private GuardBindingToolException unsafeState() {
        return new GuardBindingToolException(SAFE_MESSAGE);
    }

    public enum BindingResult {
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

    public static final class GuardBindingToolException extends RuntimeException {

        private GuardBindingToolException(String message) {
            super(message);
        }

    }
}
