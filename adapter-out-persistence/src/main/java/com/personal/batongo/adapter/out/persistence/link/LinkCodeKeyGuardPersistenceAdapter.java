package com.personal.batongo.adapter.out.persistence.link;

import com.personal.batongo.application.link.LinkCodeDerivationIdentity;
import com.personal.batongo.application.link.LinkCodeKeyRingIdentity;
import com.personal.batongo.application.link.error.LinkCodeKeyBindingException;
import com.personal.batongo.application.link.port.out.LinkCodeKeyGuardPort;
import java.util.Map;
import java.util.stream.Collectors;
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
    public void verifyOrBind(LinkCodeKeyRingIdentity ring) {
        GuardRow guard = readGuard(" FOR UPDATE");
        Map<String, LinkCodeDerivationIdentity> stored = readKeys();
        if (guard.isUnbound()) {
            if (!stored.isEmpty() || hasStoredLinkData()) {
                throw new LinkCodeKeyBindingException();
            }
            LinkCodeDerivationIdentity active = ring.keys().get(ring.activeKeyId());
            jdbcClient.sql("""
                        UPDATE link_code_key_guard
                        SET derivation_version = ?, key_fingerprint = ?
                        WHERE guard_id = ?
                        """)
                    .params(active.version(), active.hmacFingerprint(), SINGLETON_GUARD_ID)
                    .update();
        } else if (stored.isEmpty()) {
            // V6 적용 뒤 guard-tool로 처음 등록한 DB의 기존 키를 확인한다.
            requireMatchingIdentity(guard, ring.keys().get("legacy"));
        } else {
            requireAnchoredGuard(guard, stored);
            boolean matched = false;
            for (var entry : ring.keys().entrySet()) {
                LinkCodeDerivationIdentity existing = stored.get(entry.getKey());
                if (existing != null) {
                    if (!existing.equals(entry.getValue())) {
                        throw new LinkCodeKeyBindingException();
                    }
                    matched = true;
                }
            }
            if (!matched) {
                throw new LinkCodeKeyBindingException();
            }
        }
        var requiredKeyIds = jdbcClient.sql("SELECT DISTINCT key_id FROM link_creation_requests WHERE purged_at IS NULL")
                .query(String.class).list();
        if (!ring.keys().keySet().containsAll(requiredKeyIds)) {
            throw new LinkCodeKeyBindingException();
        }
        for (var entry : ring.keys().entrySet()) {
            if (!stored.containsKey(entry.getKey())) {
                jdbcClient.sql("""
                                INSERT INTO link_code_keys (key_id, derivation_version, key_fingerprint)
                                VALUES (?, ?, ?)
                                """)
                        .params(entry.getKey(), entry.getValue().version(), entry.getValue().hmacFingerprint())
                        .update();
            }
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void verifyBound(LinkCodeKeyRingIdentity ring) {
        GuardRow guard = readGuard(" FOR SHARE");
        Map<String, LinkCodeDerivationIdentity> stored = readKeys();
        requireAnchoredGuard(guard, stored);
        if (!stored.entrySet().containsAll(ring.keys().entrySet())) {
            throw new LinkCodeKeyBindingException();
        }
    }

    private Map<String, LinkCodeDerivationIdentity> readKeys() {
        return jdbcClient.sql("SELECT key_id, derivation_version, key_fingerprint FROM link_code_keys ORDER BY key_id FOR SHARE")
                .query((row, rowNumber) -> Map.entry(
                        row.getString("key_id"),
                        new LinkCodeDerivationIdentity(row.getString("derivation_version"), row.getString("key_fingerprint"))
                ))
                .list().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private void requireAnchoredGuard(GuardRow guard, Map<String, LinkCodeDerivationIdentity> stored) {
        if (!guard.isBound() || stored.values().stream().noneMatch(identity ->
                identity.version().equals(guard.derivationVersion())
                        && identity.hmacFingerprint().equals(guard.keyFingerprint()))) {
            throw new LinkCodeKeyBindingException();
        }
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
        if (currentIdentity == null || !currentIdentity.version().equals(guard.derivationVersion())
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
