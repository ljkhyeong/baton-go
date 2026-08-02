package com.personal.batongo.adapter.out.persistence.link;

import com.personal.batongo.domain.link.SmartLink;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SpringDataSmartLinkRepository extends JpaRepository<SmartLink, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select smartLink from SmartLink smartLink where smartLink.id = :id")
    Optional<SmartLink> findByIdForUpdate(@Param("id") UUID id);

}
