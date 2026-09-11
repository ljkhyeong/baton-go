package com.personal.batongo.adapter.in.web.link;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

public final class StrictUtcInstantDeserializer extends StdDeserializer<Instant> {

    private static final DateTimeFormatter UTC_INSTANT_FORMATTER =
            new DateTimeFormatterBuilder()
                    .parseCaseSensitive()
                    .parseStrict()
                    .appendValue(ChronoField.YEAR, 4)
                    .appendPattern("-MM-dd'T'HH:mm:ss")
                    .optionalStart()
                    .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
                    .optionalEnd()
                    .appendLiteral('Z')
                    .toFormatter()
                    .withResolverStyle(ResolverStyle.STRICT)
                    .withZone(ZoneOffset.UTC);

    public StrictUtcInstantDeserializer() {
        super(Instant.class);
    }

    @Override
    public Instant deserialize(JsonParser parser, DeserializationContext context)
            throws JacksonException {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return (Instant) context.handleUnexpectedToken(Instant.class, parser);
        }

        String rawValue = parser.getString();
        try {
            return Instant.from(UTC_INSTANT_FORMATTER.parse(rawValue));
        } catch (DateTimeParseException ignored) {
            // 모든 날짜·시각 형식 오류를 같은 API 오류로 반환한다.
        }
        return (Instant) context.handleWeirdStringValue(
                Instant.class,
                rawValue,
                "시각은 UTC Z 접미사가 있는 ISO-8601 문자열이어야 합니다"
        );
    }
}
