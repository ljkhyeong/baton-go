package com.personal.batongo.adapter.out.persistence.link;

import com.personal.batongo.application.link.LinkCodeDerivationIdentity;
import com.personal.batongo.application.link.error.LinkCodeKeyBindingException;
import com.personal.batongo.application.link.port.out.LinkCodeKeyGuardPort;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LinkCodeKeyGuardPersistenceAdapter implements LinkCodeKeyGuardPort {

    private static final int SINGLETON_GUARD_ID = 1;

    private final JdbcClient jdbcClient;

    public LinkCodeKeyGuardPersistenceAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void verifyOrBind(LinkCodeDerivationIdentity identity) {
        GuardRow guard = readGuard(" FOR UPDATE");
        if (guard.isBound()) {
            requireMatchingIdentity(guard, identity);
            return;
        }
        if (!guard.isUnbound() || hasStoredLinkData()) {
            throw new LinkCodeKeyBindingException();
        }

        int updated = jdbcClient.sql("""
                        UPDATE link_code_key_guard
                        SET derivation_version = ?, key_fingerprint = ?
                        WHERE guard_id = ?
                          AND derivation_version IS NULL
                          AND key_fingerprint IS NULL
                        """)
                .params(
                        identity.version(),
                        identity.hmacFingerprint(),
                        SINGLETON_GUARD_ID
                )
                .update();
        if (updated != 1) {
            throw new LinkCodeKeyBindingException();
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void verifyBound(LinkCodeDerivationIdentity identity) {
        GuardRow guard = readGuard(" FOR SHARE");
        if (!guard.isBound()) {
            throw new LinkCodeKeyBindingException();
        }
        requireMatchingIdentity(guard, identity);
    }

    private GuardRow readGuard(String lockingClause) {
        try {
            return jdbcClient.sql("""
                            SELECT derivation_version, key_fingerprint
                            FROM link_code_key_guard
                            WHERE guard_id = ?
                            """ + lockingClause)
                    .param(SINGLETON_GUARD_ID)
                    .query(GuardRow.class)
                    .single();
        } catch (IncorrectResultSizeDataAccessException exception) {
            throw new LinkCodeKeyBindingException();
        }
    }

    private boolean hasStoredLinkData() {
        return jdbcClient.sql("""
                        SELECT EXISTS(SELECT 1 FROM smart_links)
                            OR EXISTS(SELECT 1 FROM link_creation_requests)
                        """)
                .query(Boolean.class)
                .single();
    }

    private void requireMatchingIdentity(
            GuardRow guard,
            LinkCodeDerivationIdentity currentIdentity
    ) {
        if (!currentIdentity.version().equals(guard.derivationVersion())
                || !currentIdentity.hmacFingerprint().equals(guard.keyFingerprint())) {
            throw new LinkCodeKeyBindingException();
        }
    }

    private record GuardRow(
            String derivationVersion,
            String keyFingerprint
    ) {

        private boolean isBound() {
            return derivationVersion != null && keyFingerprint != null;
        }

        private boolean isUnbound() {
            return derivationVersion == null && keyFingerprint == null;
        }

        @Override
        public String toString() {
            return "GuardRow[redacted]";
        }
    }
}
