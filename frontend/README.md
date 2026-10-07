# StreamBox: frontend

SPA de la plataforma de streaming StreamBox: catálogo de películas, búsqueda,
registro e inicio de sesión y la lista personal de favoritos. Consume la API
REST del backend (carpeta `../streambox`).

Stack: React 19, Vite, TypeScript, Tailwind CSS v4 (se configura en
`src/index.css` con `@theme`, no hay `tailwind.config.js`), `react-router-dom` 7
y `lucide-react`. Tests con Vitest + Testing Library y Playwright.

## Requisitos

- Node.js 20.19 o superior (o 22.12+) y npm.
- Para `npm run dev`, el backend en marcha en `http://localhost:8080`: el proxy
  de Vite envía `/api` a ese puerto.

## Comandos

Todos se ejecutan desde esta carpeta (`frontend/`):

| Comando | Qué hace |
| :--- | :--- |
| `npm install` | Instala las dependencias |
| `npm run dev` | Servidor de desarrollo en http://localhost:5173 |
| `npm run build` | Comprueba los tipos (`tsc -b`) y genera `dist/` |
| `npm run preview` | Sirve `dist/` para probar la compilación |
| `npm run lint` | Análisis estático con oxlint (todo el proyecto) |
| `npx oxlint src e2e playwright.config.ts` | Lint explícito del código y de los E2E |
| `npm run test` | Tests unitarios y de componentes (Vitest, una pasada) |
| `npm run test:watch` | Vitest en modo vigilancia |
| `npm run test:e2e` | Tests E2E (Playwright, Chromium) |

`npm run test:e2e` levanta **su propio backend** (puerto 8099, H2 en memoria) y
su propio Vite (puerto 5199), así que no toca tu base de datos ni los puertos
8080 y 5173. Necesita Java 21 y el wrapper de Maven del backend.

## Estructura

```
frontend/
├── src/
│   ├── pages/        Pantallas (Home, Películas, Series, MyList, Profile, Login, Register y admin/)
│   ├── components/   Componentes reutilizables (Navbar, Modal, MoviePoster, estados cargando/vacío/error...)
│   ├── context/      Estado global: sesión (AuthContext), avisos (ToastContext) y favoritos (FavoritesContext)
│   ├── hooks/        Hooks propios (useCatalog, useModalDialog, useCountdown...)
│   ├── lib/          Cliente HTTP central (api.ts), tipos de la API, validación y utilidades
│   ├── test/         Utilidades compartidas por los tests (render con proveedores, fetch simulado, setup)
│   ├── index.css     Tailwind v4 y tokens de diseño (@theme)
│   └── main.tsx      Punto de entrada
├── e2e/              Tests de Playwright y su soporte (arranque, datos de ejemplo, fixtures)
├── public/           Archivos estáticos (favicon y portadas de ejemplo en covers/)
└── playwright.config.ts
```

Los tests unitarios viven junto al código que prueban (`*.test.ts(x)`).

## Más información

Descripción general, endpoints de la API, configuración del backend y plan de
trabajo: [README de la raíz](../README.md) y [`docs/PLAN_DE_ACCION.md`](../docs/PLAN_DE_ACCION.md).
