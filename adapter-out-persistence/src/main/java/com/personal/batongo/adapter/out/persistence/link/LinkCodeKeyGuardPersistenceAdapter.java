package com.personal.batongo.adapter.out.persistence.link;

import com.personal.batongo.application.link.LinkCodeDerivationIdentity;
import com.personal.batongo.application.link.error.LinkCodeKeyBindingException;
import com.personal.batongo.application.link.port.out.LinkCodeKeyGuardPort;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LinkCodeKeyGuardPersistenceAdapter implements LinkCodeKeyGuardPort {

    private static final int SINGLETON_GUARD_ID = 1;

    private final JdbcTemplate jdbcTemplate;

    public LinkCodeKeyGuardPersistenceAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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

        int updated = jdbcTemplate.update(
                """
                        UPDATE link_code_key_guard
                        SET derivation_version = ?, key_fingerprint = ?
                        WHERE guard_id = ?
                          AND derivation_version IS NULL
                          AND key_fingerprint IS NULL
                        """,
                identity.version(),
                identity.hmacFingerprint(),
                SINGLETON_GUARD_ID
        );
        if (updated != 1) {
            throw new LinkCodeKeyBindingException();
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public void verifyBound(LinkCodeDerivationIdentity identity) {
        GuardRow guard = readGuard(" FOR SHARE");
        if (!guard.isBound()) {
            throw new LinkCodeKeyBindingException();
        }
        requireMatchingIdentity(guard, identity);
    }

    private GuardRow readGuard(String lockingClause) {
        List<GuardRow> rows = jdbcTemplate.query(
                """
                        SELECT derivation_version, key_fingerprint
                        FROM link_code_key_guard
                        WHERE guard_id = ?
                        """ + lockingClause,
                (resultSet, rowNumber) -> new GuardRow(
                        resultSet.getString("derivation_version"),
                        resultSet.getString("key_fingerprint")
                ),
                SINGLETON_GUARD_ID
        );
        if (rows.size() != 1) {
            throw new LinkCodeKeyBindingException();
        }
        return rows.getFirst();
    }

    private boolean hasStoredLinkData() {
        Boolean hasData = jdbcTemplate.queryForObject(
                """
                        SELECT EXISTS(SELECT 1 FROM smart_links LIMIT 1)
                            OR EXISTS(SELECT 1 FROM link_creation_requests LIMIT 1)
                        """,
                Boolean.class
        );
        return Boolean.TRUE.equals(hasData);
    }

    private void requireMatchingIdentity(
            GuardRow guard,
            LinkCodeDerivationIdentity currentIdentity
    ) {
        try {
            LinkCodeDerivationIdentity storedIdentity = new LinkCodeDerivationIdentity(
                    guard.derivationVersion(),
                    guard.keyFingerprint()
            );
            if (!storedIdentity.matches(currentIdentity)) {
                throw new LinkCodeKeyBindingException();
            }
        } catch (IllegalArgumentException exception) {
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
    }
}
