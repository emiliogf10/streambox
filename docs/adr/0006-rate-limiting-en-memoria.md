# 0006. Rate limiting y bloqueo de cuentas en memoria

**Estado:** Aceptada, con un límite conocido (una sola instancia)

## Contexto
Login y registro son blancos de fuerza bruta y de creación masiva de cuentas. Hace falta una defensa que no filtre qué emails existen.

## Decisión
Dos protecciones complementarias, ambas con un contador de **ventana deslizante** (`SlidingWindowCounter`):
- **Por IP** (`RateLimitingFilter`): login 10/min, registro 5/h → `429 RATE_LIMIT_EXCEEDED` con `Retry-After`.
- **Por cuenta** (`LoginAttemptService`): 5 fallos en 15 min → los fallos previos responden 401 con `remainingAttempts` y el que agota los intentos, `429 ACCOUNT_LOCKED`. Se cuentan también los emails inexistentes y la respuesta es idéntica, para no revelar qué cuentas existen.
- El filtro reconoce las rutas con los mismos `PathPatternRequestMatcher` que la autorización, **nunca** comparando `getRequestURI()` (llega sin decodificar y `/api/auth/%6cogin` evadía el límite: bug real, ya corregido con test).
- La IP viene de `getRemoteAddr()`; en producción `server.forward-headers-strategy=native` hace que Tomcat lea `X-Forwarded-For` de forma segura. Nunca se lee esa cabecera a mano.

## Alternativas descartadas
- **Redis / Bucket4j distribuido:** correcto para varias réplicas, pero añade infraestructura que hoy no se necesita.
- **Solo límite por IP:** no frena a un atacante que reparte intentos entre muchas IPs contra una cuenta.
- **Solo bloqueo por cuenta:** no frena un barrido de muchas cuentas desde una IP.

## Consecuencias
- (+) Sin dependencias nuevas; configurable en `streambox.security.rate-limit.*`; probado, incluidos los tiempos de respuesta.
- (−) Con varias réplicas cada una cuenta por separado y un reinicio pone los contadores a cero; para escalar habría que moverlo a un almacén compartido.
- (−) Alguien puede bloquear 15 minutos la cuenta de otra persona fallando su login a propósito. Se acepta porque el bloqueo es temporal.
