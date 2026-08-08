package com.personal.batongo.adapter.in.web.link;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

public final class StrictUtcInstantDeserializer extends StdDeserializer<Instant> {

    private static final Pattern UTC_INSTANT_PATTERN = Pattern.compile(
            "^(?:\\d{4}|-\\d{4}|[+-][1-9]\\d{4,9})-\\d{2}-\\d{2}T"
                    + "(?:[01]\\d|2[0-3]):[0-5]\\d:[0-5]\\d"
                    + "(?:\\.\\d{1,9})?Z$"
    );

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
        if (!UTC_INSTANT_PATTERN.matcher(rawValue).matches()) {
            return invalidValue(rawValue, context);
        }
        try {
            return Instant.parse(rawValue);
        } catch (DateTimeParseException ignored) {
            // 아래의 동일한 wire-format 오류로 변환한다.
        }
        return invalidValue(rawValue, context);
    }

    private Instant invalidValue(String rawValue, DeserializationContext context)
            throws JacksonException {
        return (Instant) context.handleWeirdStringValue(
                Instant.class,
                rawValue,
                "UTC Instant는 canonical ISO-8601 문자열이어야 합니다"
        );
    }
}
