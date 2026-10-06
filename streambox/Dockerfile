# ===================================================================
# Imagen del backend de StreamBox (Spring Boot 4.1, Java 21)
#
# Construcción multietapa:
#   1. "build":   compila con Maven + JDK 21 y genera el JAR.
#   2. "runtime": solo el JRE 21 y la aplicación. Maven, el JDK, el código
#                 fuente y la caché de dependencias se quedan en la etapa 1
#                 y NO llegan a la imagen final (más pequeña y con menos
#                 superficie de ataque).
#
# Uso habitual: desde la raíz del repositorio, `docker compose build backend`.
# A mano: `docker build -t streambox-backend ./streambox`.
#
# El contexto (lo que se envía a Docker) lo limita streambox/.dockerignore:
# solo pom.xml y src/main, y NUNCA application-local.properties.
# ===================================================================


# ---------- Etapa 1: compilación ----------------------------------------
# Imagen oficial de Maven con Temurin 21. Versión fijada (3.9.16, la misma
# que declara .mvn/wrapper/maven-wrapper.properties) para que la imagen se
# construya igual hoy que dentro de seis meses. Se usa Maven directamente en
# lugar de ./mvnw porque la imagen ya lo trae: el wrapper solo serviría para
# descargar otra vez la misma versión.
FROM maven:3.9.16-eclipse-temurin-21 AS build

WORKDIR /build

# Caché de capas: Docker reutiliza una capa mientras no cambien los archivos
# que copia. Se copia primero SOLO el pom.xml y se descargan las dependencias
# en una capa propia; al cambiar código (lo habitual) esta capa se reutiliza
# y no se vuelve a bajar medio Internet. Solo se repite si cambia el pom.xml.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Ahora sí, el código. Se empaqueta SIN tests (-Dmaven.test.skip=true, que
# además de no ejecutarlos tampoco los compila: src/test ni siquiera está en
# el contexto). Los tests se ejecutan en la integración continua, que es
# donde tiene sentido que un fallo pare el proceso; repetirlos aquí haría
# cada construcción varios minutos más lenta y los de PostgreSQL necesitan
# Docker dentro de Docker.
#
# Después se separa el JAR "gordo" en dos partes (modo "tools" que recomienda
# Spring Boot para imágenes): extracted/lib/ con las dependencias, que casi
# nunca cambian, y un extracted/app.jar pequeño con nuestras clases. En la
# etapa 2 van en capas distintas, así que una versión nueva del código solo
# cambia una capa de pocos cientos de KB y no las decenas de MB de librerías.
COPY src ./src
RUN mvn -B -q package -Dmaven.test.skip=true \
    && cp target/*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --destination extracted


# ---------- Etapa 2: ejecución ------------------------------------------
# JRE 21 de Eclipse Temurin sobre Alpine:
#   - JRE y no JDK: para ejecutar no hace falta el compilador (~100 MB menos).
#   - Alpine: base mínima (~8 MB) y trae busybox con wget, que usa el
#     HEALTHCHECK. Las alternativas sin shell (distroless) son aún más
#     pequeñas, pero no tienen wget ni shell para depurar ni para el
#     HEALTHCHECK, y el proyecto no necesita ese extra.
#   - Temurin publica la variante Alpine (musl) con soporte oficial.
# Versión fijada hasta el parche de Java y la versión de Alpine.
FROM eclipse-temurin:21.0.12.1_1-jre-alpine-3.24 AS runtime

LABEL org.opencontainers.image.title="streambox-backend" \
      org.opencontainers.image.description="API REST de StreamBox (Spring Boot 4.1, Java 21)"

# Usuario sin privilegios con UID/GID numéricos fijos. Si alguien consiguiera
# ejecutar código a través de la aplicación, no sería root dentro del
# contenedor. Se usa el número en USER (no el nombre) para que orquestadores
# como Kubernetes puedan verificar "runAsNonRoot" sin leer /etc/passwd.
# -S: usuario de sistema, -D: sin contraseña, -H: sin directorio personal.
RUN addgroup -S -g 10001 streambox \
    && adduser -S -D -H -u 10001 -G streambox streambox

WORKDIR /app

# Primero las dependencias (capa que casi nunca cambia) y después el código.
# Los archivos quedan como propiedad de root y solo de lectura para el
# usuario de la aplicación: no puede modificar su propio código.
COPY --from=build /build/extracted/lib/ lib/
COPY --from=build /build/extracted/app.jar app.jar

# Perfil prod por defecto: credenciales por variables de entorno, sin
# Swagger, sin SQL en los logs y logs en JSON (application-prod.properties).
# Se puede sobrescribir con -e SPRING_PROFILES_ACTIVE=... si hiciera falta.
#
# Opciones de la JVM para contenedores, en JAVA_TOOL_OPTIONS porque la JVM
# las lee sola (funciona con el ENTRYPOINT en forma exec, sin shell) y se
# pueden cambiar desde docker compose sin reconstruir la imagen:
#   - MaxRAMPercentage=75: el heap usa hasta el 75 % de la memoria LÍMITE
#     del contenedor (no la de la máquina). El 25 % restante queda para
#     metaspace, hilos, buffers... Sin esto la JVM usa solo el 25 %.
#   - ExitOnOutOfMemoryError: si se queda sin memoria, el proceso termina y
#     Docker lo reinicia (restart: unless-stopped), en vez de seguir vivo
#     en un estado inestable.
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

USER 10001:10001

# Documenta el puerto interno. No lo publica en la máquina: eso lo decide
# docker compose (y en este proyecto solo se llega al backend por nginx).
EXPOSE 8080

# Docker marca el contenedor como "healthy" cuando la aplicación está LISTA
# para recibir tráfico (readiness: arrancada, con Flyway aplicado y la base
# de datos accesible), no solo cuando el proceso existe. docker compose lo
# usa en depends_on (condition: service_healthy) para no arrancar el
# frontend antes de tiempo. Endpoint público y sin detalles (SecurityConfig).
#   - start-period: margen para el arranque (Spring + Flyway) en el que los
#     fallos no cuentan; start-interval: durante ese margen se comprueba más
#     a menudo, así se detecta antes que ya está listo.
#   - wget de busybox devuelve error con 4xx/5xx (readiness DOWN = 503).
#   - 127.0.0.1 y no "localhost" para no depender de cómo resuelve IPv6.
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --start-interval=5s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1

# Forma exec (JSON): java es el proceso 1 y recibe directamente la señal
# SIGTERM de `docker stop`, de modo que el apagado ordenado
# (server.shutdown=graceful) termina las peticiones en curso. Con la forma
# shell ("java -jar ...") la señal le llegaría a /bin/sh y no a Java.
ENTRYPOINT ["java", "-jar", "app.jar"]
