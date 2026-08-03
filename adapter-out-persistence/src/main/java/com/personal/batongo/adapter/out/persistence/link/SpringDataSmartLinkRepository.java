package com.personal.batongo.adapter.out.persistence.link;

import com.personal.batongo.domain.link.SmartLink;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataSmartLinkRepository extends JpaRepository<SmartLink, UUID> {
}
