package com.personal.batongo.application.link.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface LinkRetentionPort {

    /** 종료 시각이 기준 이하인 정리 전 링크를 잠그고, 다른 트랜잭션이 잠근 예약은 건너뛴다. */
    List<UUID> lockRetiredLinks(Instant cutoff, int batchSize);

    /** 링크 본문을 삭제하고 생성 예약에는 정리 시각을 남기고 공개 출처를 비운다. */
    void purge(List<UUID> linkIds, Instant purgedAt);
}
