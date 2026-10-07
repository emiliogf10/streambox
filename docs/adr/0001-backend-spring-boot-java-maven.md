# 0001. Backend con Java 21, Spring Boot 4 y Maven (con wrapper)

**Estado:** Aceptada

## Contexto
StreamBox necesita una API REST con autenticación, acceso a PostgreSQL, validación y documentación OpenAPI, y es un proyecto de portfolio: debe usar tecnología habitual en el mercado y que se pueda defender y explicar.

## Decisión
- **Java 21 (LTS)** con **Spring Boot 4.1** (WebMVC, Data JPA, Security, Validation, Actuator).
- **Maven** como herramienta de construcción, siempre a través del **wrapper** (`mvnw` / `mvnw.cmd`): cualquiera clona el repositorio y compila sin instalar Maven, con la misma versión que el CI.
- Arquitectura por capas `controller → service → repository → entity` con DTOs y mappers manuales (clases `final` con métodos estáticos) en lugar de MapStruct.
- Los controladores solo tratan DTOs; los servicios devuelven DTOs, nunca entidades, y mapean dentro de la transacción (las colecciones son `LAZY` y `open-in-view=false`).

## Alternativas descartadas
- **Gradle:** igual de válido; Maven tiene una convención más rígida y el `pom.xml` es más fácil de leer para quien empieza.
- **Spring WebFlux (reactivo):** no hay carga que lo justifique y JPA es bloqueante; añadiría complejidad sin beneficio.
- **MapStruct:** evita código repetitivo, pero añade una dependencia y un paso de generación; con pocos DTOs el mapeo manual es explícito y se prueba fácilmente.
- **Exponer entidades JPA:** acopla la API al esquema y provoca `LazyInitializationException` y fugas de campos (p. ej. contraseñas).

## Consecuencias
- (+) Convenciones claras y fáciles de revisar; los errores de carga diferida se detectan pronto porque `open-in-view` está desactivado.
- (−) Más clases (DTO, mapper) por cada recurso.
- (−) Spring Boot 4 es reciente: parte de la documentación en internet habla de la versión 3 y hay que contrastarla.
