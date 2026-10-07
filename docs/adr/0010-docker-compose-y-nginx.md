# 0010. Despliegue con Docker Compose y un único puerto publicado (nginx)

**Estado:** Aceptada

## Contexto
Se quiere que cualquiera levante la aplicación completa con un comando y sin instalar Java, Node ni PostgreSQL, sin exponer más superficie de la necesaria ni tocar la base de desarrollo del autor (Supabase).

## Decisión
- `docker compose up -d --build` levanta tres contenedores: `db` (PostgreSQL 16), `backend` (Spring Boot, perfil `prod`) y `frontend` (nginx, que sirve la SPA y reenvía `/api` al backend).
- **Un solo puerto publicado**, el de nginx (`127.0.0.1:8088` por defecto, configurable con `STREAMBOX_PORT`). La base y el backend no se publican.
- nginx añade CSP y cabeceras de seguridad; los contenedores corren sin root y con sistema de archivos de solo lectura.
- Los secretos (`POSTGRES_PASSWORD`, `JWT_SECRET`) vienen de `.env` (ignorado por git; la plantilla `.env.example` los deja **vacíos** para que Compose se niegue a arrancar si faltan).
- `application-local.properties` (credenciales de Supabase) nunca entra en una imagen (`.dockerignore` y `pom.xml` lo impiden).
- El CI (GitHub Actions) ejecuta backend, frontend, E2E y una prueba de humo del stack Docker; Dependabot propone actualizaciones semanales.

## Alternativas descartadas
- **Publicar backend y frontend en puertos distintos:** obligaría a configurar CORS y ampliaría la superficie expuesta.
- **Kubernetes:** desproporcionado para este proyecto.
- **Valores de ejemplo en `.env.example`:** arrancaría con un secreto conocido por quien lea el repositorio.

## Consecuencias
- (+) Un comando, un origen, mínima superficie expuesta; reproducible en el CI.
- (−) Los rate limits en memoria ([0006](0006-rate-limiting-en-memoria.md)) limitan el despliegue a una réplica del backend.
- (−) Una variable `JWT_SECRET` definida en la terminal gana al `.env`; está documentado en el README.
