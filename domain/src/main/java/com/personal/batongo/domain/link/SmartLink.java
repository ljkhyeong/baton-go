package com.personal.batongo.domain.link;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "smart_links")
public class SmartLink {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "binary(16)")
    private UUID id;

    @Column(name = "code_hash", nullable = false, length = 64, unique = true)
    private String codeHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_system", nullable = false, length = 32)
    private TargetSystem targetSystem;

    @Column(name = "target_path", nullable = false, length = 1024)
    private String targetPath;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 32)
    private LinkPurpose purpose;

    @Column(name = "not_before")
    private Instant notBefore;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected SmartLink() {
    }

    public SmartLink(
            UUID id,
            String codeHash,
            TrustedTarget trustedTarget,
            Instant notBefore,
            Instant expiresAt,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "링크 식별자는 필수입니다");
        this.codeHash = Objects.requireNonNull(codeHash, "링크 코드 해시는 필수입니다");
        Objects.requireNonNull(trustedTarget, "신뢰 대상은 필수입니다");
        this.targetSystem = trustedTarget.targetSystem();
        this.purpose = trustedTarget.purpose();
        this.targetPath = trustedTarget.targetPath();
        this.notBefore = notBefore;
        this.expiresAt = expiresAt;
        this.createdAt = Objects.requireNonNull(createdAt, "생성 시각은 필수입니다");
        if (expiresAt != null && !expiresAt.isAfter(createdAt)) {
            throw new LinkValidationException("만료 시각은 생성 시각보다 뒤여야 합니다");
        }
        if (notBefore != null && expiresAt != null && !expiresAt.isAfter(notBefore)) {
            throw new LinkValidationException("만료 시각은 활성 시작 시각보다 뒤여야 합니다");
        }
    }

}
