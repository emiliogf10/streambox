# StreamBox Frontend

Aplicacin web tipo OTT (estilo Netflix) construida con **React 19**, **Vite**, **TypeScript** y **Tailwind CSS v4**.

## Caractersticas Principales

- **Diseo OTT Inmersivo**: Interfaz oscura con degradados, hero banner y carruseles horizontales.
- **Navegacin SPA**: Router integrado para navegar entre el Login, la Home y "Mi Lista".
- **Autenticacin JWT**: Sistema de login conectado al backend que gestiona y almacena tokens seguros.
- **Integracin con API Real**: Consume el backend de Spring Boot mediante un proxy configurado en Vite, solucionando problemas de CORS.
- **Diseo Responsivo y Accesible**: Controles de teclado en modales y diseño adaptable a múltiples resoluciones.

## Requisitos Previos

- **Node.js** (v18+)
- Backend de StreamBox corriendo localmente en el puerto `8080`.

## Scripts de Ejecucin

1. **Instalar dependencias:**
   ```bash
   npm install
   ```

2. **Servidor de desarrollo:**
   ```bash
   npm run dev
   ```
   La aplicacin estarǭ disponible en `http://localhost:5173`. Todas las llamadas a `/api/*` serǭn redirigidas automǭticamente al backend en `localhost:8080`.

3. **Compilacin de produccin:**
   ```bash
   npm run build
   ```

4. **Verificacin de TypeScript:**
   ```bash
   npm run tsc
   ```

## Estructura del Proyecto

- `src/AppShell.tsx`: Layout base (Navbar + padding de contenido).
- `src/HomePage.tsx`: Landing principal con catǭlogo y filas de pelculas.
- `src/LoginPage.tsx`: Interfaz de autenticacin protegida.
- `src/MyListPage.tsx`: Gestin y visualizacin de pelculas favoritas.
- `src/MovieDetailsModal.tsx`: Modal oscuro superpuesto para detalles de pelculas.
