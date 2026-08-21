package com.personal.batongo.adapter.out.persistence.link;

import com.personal.batongo.domain.link.SmartLink;
import java.util.UUID;
import org.springframework.data.repository.CrudRepository;

interface SpringDataSmartLinkRepository extends CrudRepository<SmartLink, UUID> {
}
