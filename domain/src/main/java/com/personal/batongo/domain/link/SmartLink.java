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
import java.util.regex.Pattern;

@Entity
@Table(name = "smart_links")
public class SmartLink {

    private static final Pattern SHA_256_HEX = Pattern.compile("^[0-9a-f]{64}$");

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
    private Long version;

    protected SmartLink() {
    }

    private SmartLink(
            UUID id,
            String codeHash,
            TrustedTarget trustedTarget,
            Instant notBefore,
            Instant expiresAt,
            Instant createdAt
    ) {
        this.id = Objects.requireNonNull(id, "링크 식별자는 필수입니다");
        this.codeHash = requireCodeHash(codeHash);
        Objects.requireNonNull(trustedTarget, "신뢰 대상은 필수입니다");
        this.targetSystem = trustedTarget.targetSystem();
        this.purpose = trustedTarget.purpose();
        this.targetPath = trustedTarget.targetPath();
        this.notBefore = notBefore;
        this.expiresAt = expiresAt;
        this.createdAt = Objects.requireNonNull(createdAt, "생성 시각은 필수입니다");
        validateTimeRange();
    }

    public static SmartLink create(
            UUID id,
            String codeHash,
            TrustedTarget trustedTarget,
            Instant notBefore,
            Instant expiresAt,
            Instant createdAt
    ) {
        return new SmartLink(
                id,
                codeHash,
                trustedTarget,
                notBefore,
                expiresAt,
                createdAt
        );
    }

    public void requireResolvableAt(Instant now) {
        LinkAvailabilityPolicy.requireResolvableAt(
                revokedAt,
                notBefore,
                expiresAt,
                now
        );
    }

    public void revoke(Instant now) {
        Objects.requireNonNull(now, "폐기 시각은 필수입니다");
        if (now.isBefore(createdAt)) {
            throw new LinkValidationException("폐기 시각은 생성 시각보다 빠를 수 없습니다");
        }
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    private void validateTimeRange() {
        if (expiresAt != null && !expiresAt.isAfter(createdAt)) {
            throw new LinkValidationException("만료 시각은 생성 시각보다 뒤여야 합니다");
        }
        if (notBefore != null && expiresAt != null && !expiresAt.isAfter(notBefore)) {
            throw new LinkValidationException("만료 시각은 활성 시작 시각보다 뒤여야 합니다");
        }
    }

    private static String requireCodeHash(String value) {
        if (value == null || !SHA_256_HEX.matcher(value).matches()) {
            throw new LinkValidationException("링크 코드 해시는 SHA-256 lowercase hex여야 합니다");
        }
        return value;
    }

    public UUID getId() {
        return id;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public TargetSystem getTargetSystem() {
        return targetSystem;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public LinkPurpose getPurpose() {
        return purpose;
    }

    public Instant getNotBefore() {
        return notBefore;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
