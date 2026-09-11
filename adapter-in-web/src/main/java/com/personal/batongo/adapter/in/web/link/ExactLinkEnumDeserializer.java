package com.personal.batongo.adapter.in.web.link;

import com.personal.batongo.domain.link.LinkPurpose;
import com.personal.batongo.domain.link.TargetSystem;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;

/** 숫자나 공백을 보정하지 않고 v1 열거형 이름을 그대로 읽습니다. */
public abstract class ExactLinkEnumDeserializer<E extends Enum<E>>
        extends StdDeserializer<E> {

    private final Class<E> enumType;

    protected ExactLinkEnumDeserializer(Class<E> enumType) {
        super(enumType);
        this.enumType = enumType;
    }

    @Override
    public E deserialize(JsonParser parser, DeserializationContext context)
            throws JacksonException {
        if (!parser.hasToken(JsonToken.VALUE_STRING)) {
            return enumType.cast(context.handleUnexpectedToken(enumType, parser));
        }
        String rawValue = parser.getString();
        try {
            return Enum.valueOf(enumType, rawValue);
        } catch (IllegalArgumentException exception) {
            return enumType.cast(context.handleWeirdStringValue(
                    enumType,
                    rawValue,
                    "열거형 이름은 API에 정의된 값과 정확히 일치해야 합니다"
            ));
        }
    }

    public static final class TargetSystemDeserializer
            extends ExactLinkEnumDeserializer<TargetSystem> {

        public TargetSystemDeserializer() {
            super(TargetSystem.class);
        }
    }

    public static final class LinkPurposeDeserializer
            extends ExactLinkEnumDeserializer<LinkPurpose> {

        public LinkPurposeDeserializer() {
            super(LinkPurpose.class);
        }
    }
}
