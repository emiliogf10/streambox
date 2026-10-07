# 0002. Frontend como SPA independiente con React, Vite, TypeScript y Tailwind

**Estado:** Aceptada

## Contexto
La interfaz imita una plataforma OTT (banner, carruseles, modales, sesión, panel de administración). El backend ya es una API REST, así que el cliente puede ser una aplicación separada.

## Decisión
- Una **SPA** en `frontend/` con **React 19**, **TypeScript**, **Vite** (servidor de desarrollo y empaquetado), **React Router** y **Tailwind CSS v4** con tokens `@theme` en `index.css`.
- Backend y frontend son proyectos independientes, cada uno con su propio gestor de dependencias y sus tests.
- **En desarrollo**, Vite reenvía `/api` a `localhost:8080` (proxy): el navegador solo habla con un origen, así que **no hace falta CORS**. **En Docker**, nginx hace lo mismo (ver [0010](0010-docker-compose-y-nginx.md)).
- Todas las llamadas a la API pasan por un único cliente (`lib/api.ts`, `apiFetch`) que trata de forma uniforme 401, 403, 429, 204 y red caída.
- Estado global mínimo con Context (sesión, avisos, favoritos); sin librería de estado externa.

## Alternativas descartadas
- **Next.js / renderizado en servidor:** no hay SEO que justifique un segundo servidor; la app vive tras el login.
- **Plantillas en el propio Spring (Thymeleaf):** mezclaría la interfaz con el backend y limitaría la experiencia interactiva.
- **Redux u otra librería de estado:** el estado compartido es pequeño; Context basta.
- **CORS abierto entre dos orígenes:** más superficie de ataque y más configuración que un proxy.

## Consecuencias
- (+) Se puede cambiar o probar cada parte por separado (Vitest para lógica y componentes, Playwright para flujos completos).
- (+) Un solo origen: sin CORS en desarrollo ni en producción.
- (−) Dos cadenas de herramientas (Maven y npm) y dos conjuntos de dependencias que mantener (Dependabot cubre ambos).
- (−) El contrato de la API hay que mantenerlo a mano en los tipos de `lib/`; no se genera a partir de OpenAPI.
