package com.personal.batongo.adapter.in.web;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;

@Configuration(proxyBeanMethods = false)
class StrictHttpJsonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer strictHttpJsonCustomizer() {
        return builder -> builder.withCoercionConfig(Long.class, coercion -> {
            coercion.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
            coercion.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        });
    }
}
