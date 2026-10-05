package com.personal.batongo.adapter.out.persistence.link;

import com.personal.batongo.application.link.LinkCodeDerivationIdentity;
import com.personal.batongo.application.link.LinkCodeKeyRingIdentity;
import com.personal.batongo.application.link.error.LinkCodeKeyBindingException;
import com.personal.batongo.application.link.port.out.LinkCodeKeyGuardPort;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class LinkCodeKeyGuardPersistenceAdapter implements LinkCodeKeyGuardPort {

    private final JdbcClient jdbcClient;

    public LinkCodeKeyGuardPersistenceAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void verifyOrBind(LinkCodeKeyRingIdentity ring) {
        Map<String, LinkCodeDerivationIdentity> stored = readKeys(" FOR UPDATE");
        // 빈 DB만 현재 키로 처음 등록하고, 등록된 DB는 같은 지문의 키를 하나 이상 설정해야 한다.
        if (stored.isEmpty() ? hasStoredLinkData() : !sharesRegisteredKey(ring, stored)) {
            throw new LinkCodeKeyBindingException();
        }
        var requiredKeyIds = jdbcClient.sql("SELECT DISTINCT key_id FROM link_creation_requests WHERE purged_at IS NULL")
                .query(String.class).list();
        if (!ring.keys().keySet().containsAll(requiredKeyIds)) {
            throw new LinkCodeKeyBindingException();
        }
        ring.keys().forEach((keyId, identity) -> {
            if (!stored.containsKey(keyId)) {
                jdbcClient.sql("""
                                INSERT INTO link_code_keys (key_id, derivation_version, key_fingerprint)
                                VALUES (?, ?, ?)
                                """)
                        .params(keyId, identity.version(), identity.hmacFingerprint())
                        .update();
            }
        });
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void verifyBound(LinkCodeKeyRingIdentity ring) {
        if (!readKeys(" FOR SHARE").entrySet().containsAll(ring.keys().entrySet())) {
            throw new LinkCodeKeyBindingException();
        }
    }

    private static boolean sharesRegisteredKey(
            LinkCodeKeyRingIdentity ring, Map<String, LinkCodeDerivationIdentity> stored
    ) {
        boolean matched = false;
        for (var entry : ring.keys().entrySet()) {
            LinkCodeDerivationIdentity existing = stored.get(entry.getKey());
            if (existing != null) {
                if (!existing.equals(entry.getValue())) {
                    return false;
                }
                matched = true;
            }
        }
        return matched;
    }

    private Map<String, LinkCodeDerivationIdentity> readKeys(String lockingClause) {
        return jdbcClient.sql("SELECT key_id, derivation_version, key_fingerprint FROM link_code_keys ORDER BY key_id"
                        + lockingClause)
                .query((row, rowNumber) -> Map.entry(
                        row.getString("key_id"),
                        new LinkCodeDerivationIdentity(row.getString("derivation_version"), row.getString("key_fingerprint"))
                ))
                .list().stream().collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private boolean hasStoredLinkData() {
        return jdbcClient.sql("""
                        SELECT EXISTS(SELECT 1 FROM smart_links)
                            OR EXISTS(SELECT 1 FROM link_creation_requests)
                        """)
                .query(Boolean.class)
                .single();
    }
}
