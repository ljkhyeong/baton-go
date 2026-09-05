package com.personal.batongo.application.link.port.out;

import java.time.Instant;

public interface LinkRetentionPort {
    int purgeRetiredLinks(Instant cutoff, Instant purgedAt, int batchSize);
}
