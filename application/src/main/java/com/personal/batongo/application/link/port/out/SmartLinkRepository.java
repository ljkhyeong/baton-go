package com.personal.batongo.application.link.port.out;

import com.personal.batongo.domain.link.SmartLink;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SmartLinkRepository {

    SmartLink save(SmartLink smartLink);

    Optional<SmartLink> findById(UUID id);

    Optional<SmartLink> findByIdForUpdate(UUID id);

    Optional<StoredLinkResolution> findResolutionByCodeHash(String codeHash);

    record StoredLinkResolution(
            UUID id,
            String targetSystem,
            String targetPath,
            String purpose,
            Instant notBefore,
            Instant expiresAt,
            Instant revokedAt
    ) {
    }
}
