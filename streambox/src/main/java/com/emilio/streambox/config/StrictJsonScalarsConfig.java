package com.emilio.streambox.config;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.type.LogicalType;

/**
 * Hace estricto el JSON de entrada: cada campo acepta solo su tipo de valor
 * JSON. Un campo de texto solo admite una cadena y uno numérico o booleano, solo
 * un número o un booleano; cualquier otra cosa es un 400
 * {@code MALFORMED_REQUEST} (el mismo que un JSON mal formado).
 *
 * <p>
 * <b>El problema.</b> Jackson convierte por defecto los escalares de un tipo a
 * otro sin avisar:
 * </p>
 * <ul>
 * <li><b>Número o booleano → texto</b>: {@code "password": 123456789012.5}
 * creaba la cuenta con la contraseña {@code "123456789012.5"}, y
 * {@code "title": true}, una película titulada {@code "true"}. El cliente
 * mandó otra cosa de la que se guarda, y la validación ({@code @Size},
 * {@code PasswordPolicy}...) se aplica sobre un texto que nadie escribió.</li>
 * <li><b>Texto → número o booleano</b>: {@code "duration": "90"} se leía como
 * {@code 90} y {@code "duration": ""} como {@code null} (que acababa en un
 * «es obligatorio» engañoso: el campo sí venía).</li>
 * </ul>
 * <p>
 * Ninguna de esas conversiones la necesita un cliente correcto: el frontend
 * envía cada campo con su tipo ({@code frontend/src/lib/types.ts}: los textos
 * como {@code string} y {@code duration}, los años y los ids como
 * {@code number}). Aceptarlas solo sirve para que un error del cliente o una
 * petición manipulada pase como válida con un valor distinto del enviado.
 * </p>
 *
 * <p>
 * <b>Qué se configura y por qué hacen falta dos ajustes.</b>
 * </p>
 * <ul>
 * <li>{@link MapperFeature#ALLOW_COERCION_OF_SCALARS} desactivado: cubre los
 * destinos numéricos y booleanos (texto → número/booleano, número →
 * booleano). Por sí solo <b>no</b> cubre el texto: Jackson no considera
 * {@code String} un escalar «convertible» a efectos de esta característica,
 * así que {@code "password": 123} seguía entrando.</li>
 * <li>Coerción del tipo lógico {@link LogicalType#Textual} ({@code String},
 * {@code char}...) desde {@code Integer}, {@code Float} y {@code Boolean} a
 * {@link CoercionAction#Fail}: es lo que rechaza número/booleano → texto.</li>
 * </ul>
 * <p>
 * El tercer caso, decimal → entero ({@code "duration": 100.5} truncado a
 * {@code 100}), ya lo cierra {@code spring.jackson.deserialization.accept-float-as-int=false}
 * en {@code application.properties} (incidencia QA-1). Se deja allí porque
 * Spring Boot lo admite como propiedad; lo de esta clase no tiene propiedad
 * equivalente ({@code spring.jackson.*} no configura coerciones), por eso es
 * un {@link JsonMapperBuilderCustomizer}.
 * </p>
 *
 * <p>
 * <b>Alcance.</b> Afecta al {@code JsonMapper} de Jackson 3 que Spring Boot
 * autoconfigura y que Spring MVC usa para leer todos los {@code @RequestBody}.
 * Solo cambia la <em>lectura</em>: las respuestas se escriben igual. No afecta
 * a los parámetros de consulta ({@code ?page=1}, los convierte Spring, no
 * Jackson), ni al {@code ObjectMapper} de Jackson 2 de
 * {@code SecurityErrorResponseWriter} (solo escribe) ni al de jjwt. Los campos
 * de tipo {@code Object} de {@code UpdateProfileRequest} siguen aceptando
 * cualquier valor: no hay conversión que hacer.
 * </p>
 *
 * <p>
 * El personalizador se ejecuta después del estándar de Spring Boot (que
 * aplica las propiedades {@code spring.jackson.*}), porque no declara orden y
 * el de Boot tiene orden 0; aun así no tocan lo mismo.
 * </p>
 */
@Configuration
public class StrictJsonScalarsConfig {

    /**
     * Desactiva las conversiones automáticas entre escalares del JSON de
     * entrada (ver la documentación de la clase).
     *
     * @return personalizador del {@code JsonMapper} de Spring Boot
     */
    @Bean
    JsonMapperBuilderCustomizer strictJsonScalarsCustomizer() {
        return builder -> builder
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .withCoercionConfig(LogicalType.Textual, config -> config
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail));
    }
}
