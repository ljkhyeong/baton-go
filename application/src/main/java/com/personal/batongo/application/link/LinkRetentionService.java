package com.personal.batongo.application.link;

import com.personal.batongo.application.link.port.out.LinkRetentionPort;
import com.personal.batongo.application.link.port.out.LinkRetentionPort.PurgedLink;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
        // 정리 뒤 같은 생성 요청은 LINK_PURGED, 다른 요청은 키 재사용 충돌로 구분하도록 요청 해시를 남긴다.
        List<PurgedLink> purged = retention.lockRetiredLinks(now.minus(period), batchSize).stream()
                .map(link -> new PurgedLink(link.id(), LinkCreationFingerprint.of(
                        link.targetSystem(), link.purpose(), link.targetPath(),
                        link.notBefore(), link.expiresAt())))
                .toList();
        retention.purge(purged, now);
        return purged.size();
    }
}
