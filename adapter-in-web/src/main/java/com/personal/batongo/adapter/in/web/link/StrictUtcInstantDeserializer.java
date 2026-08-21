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
                    .appendLiteral('-')
                    .appendValue(ChronoField.MONTH_OF_YEAR, 2)
                    .appendLiteral('-')
                    .appendValue(ChronoField.DAY_OF_MONTH, 2)
                    .appendLiteral('T')
                    .appendValue(ChronoField.HOUR_OF_DAY, 2)
                    .appendLiteral(':')
                    .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
                    .appendLiteral(':')
                    .appendValue(ChronoField.SECOND_OF_MINUTE, 2)
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
            // 아래의 동일한 wire-format 오류로 변환한다.
        }
        return (Instant) context.handleWeirdStringValue(
                Instant.class,
                rawValue,
                "UTC Instant는 canonical ISO-8601 문자열이어야 합니다"
        );
    }
}
