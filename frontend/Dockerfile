# ===================================================================
# Imagen del frontend de StreamBox (React 19 + Vite, servido por nginx)
#
# Construcción multietapa:
#   1. "build":   instala las dependencias con npm y compila con Vite.
#   2. "runtime": solo nginx y los archivos estáticos de dist/. Node,
#                 node_modules (~centenares de MB) y el código fuente se
#                 quedan en la etapa 1 y NO llegan a la imagen final.
#
# Uso habitual: desde la raíz del repositorio, `docker compose build frontend`.
# A mano: `docker build -t streambox-frontend ./frontend`.
#
# El contexto lo limita frontend/.dockerignore (lista blanca: nunca entran
# node_modules, dist ni archivos .env).
# ===================================================================


# ---------- Etapa 1: compilación ----------------------------------------
# Node 24 (LTS), la misma versión exacta que se usa en desarrollo. Vite 8 y
# Vitest 5 exigen Node ^22.12 o ^24 (campo "engines" de sus package.json).
# Versión fijada hasta el parche de Node y la de Alpine para que la imagen
# se construya igual hoy que dentro de seis meses.
FROM node:24.21.0-alpine3.24 AS build

WORKDIR /app

# Caché de capas: primero SOLO package.json y package-lock.json, y la
# instalación en una capa propia. Al cambiar código (lo habitual) esta capa
# se reutiliza y no se vuelve a descargar node_modules; solo se repite si
# cambian las dependencias.
#   - npm ci (y no npm install): instala exactamente lo del lockfile y falla
#     si no cuadra con package.json. Construcciones reproducibles.
#   - Se instalan también las devDependencies: Vite, TypeScript y Tailwind
#     son de desarrollo, pero hacen falta para compilar. No llegan a la
#     imagen final de todas formas.
#   - --ignore-scripts: no ejecuta los scripts de instalación (postinstall)
#     de las dependencias. Es la vía típica de los ataques a la cadena de
#     suministro de npm, y este proyecto no los necesita (los binarios
#     nativos de Rolldown, Tailwind y oxlint llegan como paquetes opcionales
#     ya compilados para Linux/musl).
#   - --no-audit --no-fund: sin avisos ni consultas extra en cada build.
COPY package.json package-lock.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund

# Ahora sí, el código, y el mismo comando de compilación que en local y en
# la integración continua (tsc -b && vite build): si TypeScript encuentra
# un error, la imagen no se construye.
COPY . .
RUN npm run build


# ---------- Etapa 2: ejecución ------------------------------------------
# nginx "unprivileged" (mantenida por el equipo de nginx) en lugar de la
# oficial "nginx":
#   - La oficial arranca el proceso maestro como root (para abrir el puerto
#     80) y necesita capacidades del kernel (CHOWN, SETUID, SETGID,
#     NET_BIND_SERVICE). Con "cap_drop: ALL" en compose no arrancaría.
#   - Esta se ejecuta entera con el usuario nginx (UID 101), escucha en el
#     8080 y tiene el pid y los temporales en /tmp. Funciona con el sistema
#     de archivos de solo lectura y sin ninguna capacidad.
# Misma versión (1.30, rama estable) y misma Alpine (3.24) que la imagen del
# backend. Trae busybox con wget para el HEALTHCHECK.
FROM nginxinc/nginx-unprivileged:1.30.5-alpine3.24 AS runtime

LABEL org.opencontainers.image.title="streambox-frontend" \
      org.opencontainers.image.description="SPA de StreamBox (React 19 + Vite) servida por nginx, con proxy de /api al backend"

# Se quitan la página de bienvenida y el 50x.html de nginx: en la imagen
# solo debe quedar lo que es de StreamBox. Requiere root solo para esto.
USER root
RUN rm -rf /usr/share/nginx/html/*

# Configuración propia (sustituye al default.conf de la imagen) y la SPA
# compilada, incluidas las portadas de public/covers (Vite copia public/ tal
# cual a dist/). Los archivos quedan como propiedad de root: el usuario de
# nginx puede leerlos pero no modificarlos.
COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist/ /usr/share/nginx/html/

# De vuelta al usuario sin privilegios de la imagen base (UID numérico para
# que orquestadores como Kubernetes puedan verificar "runAsNonRoot").
USER 101

# Documenta el puerto interno. Lo publica docker compose (STREAMBOX_PORT).
EXPOSE 8080

# "healthy" cuando nginx responde. /healthz lo contesta nginx sin tocar el
# disco ni el backend (ver nginx/default.conf). nginx arranca en menos de un
# segundo: start-period corto y comprobaciones frecuentes mientras dura.
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --start-interval=1s --retries=3 \
    CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1

# ENTRYPOINT y CMD los hereda de la imagen base (nginx -g "daemon off;" en
# primer plano, PID 1, recibe SIGTERM/SIGQUIT de docker stop).
