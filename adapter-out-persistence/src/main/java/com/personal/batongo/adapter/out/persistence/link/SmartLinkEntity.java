package com.personal.batongo.adapter.out.persistence.link;

import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.SmartLink;
import com.personal.batongo.domain.link.TargetSystem;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** 새 링크를 저장하고 애플리케이션 시작 시 링크 테이블 스키마를 검증하는 JPA 엔티티입니다. */
@Entity
@Table(name = "smart_links")
class SmartLinkEntity {

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

    protected SmartLinkEntity() {
    }

    SmartLinkEntity(SmartLink link) {
        this.id = link.id();
        this.codeHash = link.codeHash();
        this.targetSystem = link.trustedTarget().targetSystem();
        this.targetPath = link.trustedTarget().targetPath();
        this.purpose = link.trustedTarget().purpose();
        this.notBefore = link.notBefore();
        this.expiresAt = link.expiresAt();
        this.createdAt = link.createdAt();
    }
}
