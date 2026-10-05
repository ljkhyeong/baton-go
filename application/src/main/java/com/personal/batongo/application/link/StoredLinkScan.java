package com.personal.batongo.application.link;

import com.personal.batongo.application.link.port.out.SmartLinkRepository;
import com.personal.batongo.application.link.port.out.SmartLinkRepository.StoredLinkSnapshot;
import java.util.List;
import java.util.UUID;

/** 링크 ID 순서로 한 번에 검사할 저장 행과 다음 커서를 정한다. */
record StoredLinkScan(List<StoredLinkSnapshot> rows, boolean hasMore) {

    static final int MAX_LIMIT = 500;

    static boolean isValidLimit(int limit) {
        return limit >= 1 && limit <= MAX_LIMIT;
    }

    /** 다음 행 존재 확인용 한 건을 더해 최대 limit + 1건만 읽는다. */
    static StoredLinkScan read(SmartLinkRepository repository, UUID afterLinkId, int limit) {
        List<StoredLinkSnapshot> scanned = repository.scanStoredAfter(afterLinkId, limit + 1);
        boolean hasMore = scanned.size() > limit;
        return new StoredLinkScan(hasMore ? scanned.subList(0, limit) : scanned, hasMore);
    }

    /** 반환할 항목이 없어도 마지막으로 검사한 행 다음부터 조회한다. */
    UUID nextAfterLinkId() {
        return hasMore ? rows.getLast().id() : null;
    }
}
