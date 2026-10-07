package com.personal.batongo.application.link;

import com.personal.batongo.application.link.port.out.LinkRetentionPort;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LinkRetentionService {
    private final LinkRetentionPort retention;
    private final Clock clock;

    public LinkRetentionService(LinkRetentionPort retention, Clock clock) {
        this.retention = retention;
        this.clock = clock;
    }

    @Transactional
    public int purge(Duration period, int batchSize) {
        Instant now = clock.instant();
        List<UUID> linkIds = retention.lockRetiredLinks(now.minus(period), batchSize);
        retention.purge(linkIds, now);
        return linkIds.size();
    }
}
