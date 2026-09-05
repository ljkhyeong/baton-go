package com.personal.batongo.adapter.in.web.link;

import com.personal.batongo.application.link.port.in.SmartLinkUseCase.LinkSearchResult;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LinkSearchResponse(
        List<LinkResponse> items,
        UUID nextAfterLinkId,
        boolean hasMore,
        Instant evaluatedAt
) {

    static LinkSearchResponse from(LinkSearchResult result) {
        return new LinkSearchResponse(
                result.items().stream().map(LinkResponse::from).toList(),
                result.nextAfterLinkId(),
                result.hasMore(),
                result.evaluatedAt()
        );
    }
}
