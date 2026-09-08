package com.personal.batongo.adapter.in.web.link;

import com.personal.batongo.application.link.port.in.SmartLinkUseCase.LinkBatchResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LinkBatchResponse(List<LinkResponse> items, List<UUID> notFoundIds, Instant evaluatedAt) {

    static LinkBatchResponse from(LinkBatchResult result) {
        return new LinkBatchResponse(
                result.items().stream().map(LinkResponse::from).toList(),
                result.notFoundIds(),
                result.evaluatedAt()
        );
    }
}
