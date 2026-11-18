import { lazy, Suspense, useState } from 'react';
import type { ReactNode } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClientProvider } from '@tanstack/react-query';
import { AppShell } from './components/AppShell';
import { LazyRouteBoundary } from './components/LazyRouteBoundary';
import { FallbackFocusHandoff, RestoreContentFocus } from './components/LazyScreenFocus';
import { LoadingState } from './components/LoadingState';
import { RouteChangeFocus } from './components/RouteChangeFocus';
import { GuardScreen, RedirectIfAuthenticated, RequireAdmin, RequireAuth } from './components/RouteGuards';
import { SkipLink } from './components/SkipLink';
import { AuthProvider } from './context/AuthContext';
import { ToastProvider } from './context/ToastContext';
import { WELCOME_PATH } from './lib/favoriteGenres';
import { importChunk } from './lib/lazyChunks';
import { createQueryClient } from './lib/queryClient';
import { ADMIN_MOVIES_PATH } from './pages/admin/adminPaths';
import { HomePage } from './pages/HomePage';
import { LoginPage } from './pages/LoginPage';
import { MovieDetailPage } from './pages/MovieDetailPage';
import { MoviesPage } from './pages/MoviesPage';
import { MyListPage } from './pages/MyListPage';
import { NotFoundPage } from './pages/NotFoundPage';
import { ProfilePage } from './pages/ProfilePage';
import { SeriesDetailPage } from './pages/SeriesDetailPage';
import { SeriesPage } from './pages/SeriesPage';
import { WelcomePage } from './pages/WelcomePage';

/*
 * Pantallas que se cargan BAJO DEMANDA (`React.lazy` + `import()`; revisión de
 * 2026-11, M3). Antes todo iba en un único paquete de 542 kB (161 kB con gzip),
 * también el panel de administración, que un USER nunca puede abrir.
 *
 * - **Panel** (`/admin/*`): un solo trozo con todas sus pantallas (ver
 *   `pages/admin/adminPages.ts`); `loadAdmin` comparte la misma descarga.
 * - **Pantallas de cuenta sin sesión** (registro, recuperar y restablecer la
 *   contraseña, confirmar el correo): se visitan pocas veces y casi nunca con la
 *   sesión abierta, así que cada una va en su trozo.
 * - Se quedan en el paquete principal el login (la primera pantalla de quien no
 *   tiene sesión) y todo lo de la zona con sesión que se usa a diario.
 *
 * `lazy` pide un `default`: las páginas exportan con nombre (como el resto del
 * proyecto), así que cada carga lo adapta con `.then`.
 *
 * Cada `import()` pasa por `importChunk`, que marca un fallo de descarga como
 * `ChunkLoadError`; cada `Suspense` diferido va dentro de un
 * `LazyRouteBoundary`, que lo convierte en «No se pudo cargar esta pantalla» con
 * «Recargar la página» en vez de dejar la aplicación en blanco (ver los dos).
 */
const loadAdmin = () => importChunk(() => import('./pages/admin/adminPages'));
const AdminLayout = lazy(() => loadAdmin().then((m) => ({ default: m.AdminLayout })));
const AdminMoviesPage = lazy(() => loadAdmin().then((m) => ({ default: m.AdminMoviesPage })));
const MovieFormPage = lazy(() => loadAdmin().then((m) => ({ default: m.MovieFormPage })));
const AdminSeriesPage = lazy(() => loadAdmin().then((m) => ({ default: m.AdminSeriesPage })));
const SeriesFormPage = lazy(() => loadAdmin().then((m) => ({ default: m.SeriesFormPage })));
const AdminGenresPage = lazy(() => loadAdmin().then((m) => ({ default: m.AdminGenresPage })));
const RegisterPage = lazy(() =>
  importChunk(() => import('./pages/RegisterPage')).then((m) => ({ default: m.RegisterPage })),
);
const ForgotPasswordPage = lazy(() =>
  importChunk(() => import('./pages/ForgotPasswordPage')).then((m) => ({ default: m.ForgotPasswordPage })),
);
const ResetPasswordPage = lazy(() =>
  importChunk(() => import('./pages/ResetPasswordPage')).then((m) => ({ default: m.ResetPasswordPage })),
);
const VerifyEmailPage = lazy(() =>
  importChunk(() => import('./pages/VerifyEmailPage')).then((m) => ({ default: m.VerifyEmailPage })),
);

/**
 * Espera de una pantalla pública que se carga bajo demanda: el estado
 * «Cargando...» de siempre dentro de un `<main>` propio (sin `AppShell` no hay
 * otro), como las pantallas de las guardas. Al navegar desde otra página casi
 * nunca se ve: React Router cambia de ruta dentro de una transición y deja la
 * página anterior a la vista hasta que la nueva está lista.
 *
 * Si la pantalla no llega (falla la descarga de su trozo), `LazyRouteBoundary`
 * pinta el aviso en su lugar, también dentro de un `<main>` propio.
 *
 * El foco: al llegar navegando, `RouteChangeFocus` lo pone en el `<main>` de la
 * espera, que desaparece al llegar la pantalla (trae el suyo).
 * `FallbackFocusHandoff` y `RestoreContentFocus` lo pasan al `<main>` nuevo
 * para que no caiga en `<body>` (ver `LazyScreenFocus`).
 */
function PublicPage({ children }: { children: ReactNode }) {
  return (
    <LazyRouteBoundary standalone>
      <Suspense
        fallback={
          <FallbackFocusHandoff>
            <GuardScreen>
              <LoadingState />
            </GuardScreen>
          </FallbackFocusHandoff>
        }
      >
        {children}
        <RestoreContentFocus />
      </Suspense>
    </LazyRouteBoundary>
  );
}

/**
 * Mapa de rutas de la aplicación.
 *
 * - `/login` y `/registro`: públicas; si ya hay sesión redirigen a `/`.
 * - `/verificar-email` (`#token=…`): pública y SIN guarda, para que el enlace
 *   del correo de confirmación funcione igual con o sin sesión abierta (quien
 *   ya ha entrado con otra cuenta en este navegador no debe acabar en `/`).
 * - `/recuperar-contrasena` («He olvidado mi contraseña»: pedir el enlace) y
 *   `/restablecer-contrasena` (`#token=…`, elegir la contraseña nueva): públicas
 *   y SIN guarda por lo mismo; quien tiene la sesión abierta pero no recuerda la
 *   contraseña también puede usarlas.
 * - `/`, `/peliculas` (con `?genero=&anio=&orden=`), `/peliculas/:id` (la página
 *   de una película), `/series`, `/series/:id` (con `?temporada=N`), `/favorites` y
 *   `/perfil` (datos de la cuenta y resumen de la lista): privadas, dentro de `AppShell` (barra + contenido). A `/perfil` se
 *   llega desde «Mi perfil» en el menú de usuario de la barra.
 * - `/bienvenida`: la pregunta de los géneros favoritos de una cuenta nueva
 *   (`WelcomePage`). Privada (tras `RequireAuth`, que es quien lleva ahí a un
 *   `USER` con la pregunta pendiente, una vez), pero FUERA de `AppShell`: sin
 *   barra, como el login, porque es un paso y sus salidas son sus dos botones.
 * - `/admin/*`: panel de administración, también dentro de `AppShell` y además
 *   tras `RequireAdmin` (espera a conocer el rol; si no es `ADMIN`, a `/`).
 *   `/admin` lleva a `/admin/peliculas`; el resto de rutas del panel son el
 *   listado, el alta (`peliculas/nueva`) y la edición (`peliculas/:id/editar`)
 *   de películas, lo mismo para series (`series`, `series/nueva`,
 *   `series/:id/editar`) y los géneros (`generos`). Una ruta desconocida del
 *   panel vuelve al listado de películas.
 * - Cualquier otra ruta: `NotFoundPage` («Página no encontrada» con «Ir al
 *   inicio»), dentro de `AppShell` y tras `RequireAuth` (sin sesión, al login).
 *   Antes redirigía a `/` sin explicar nada.
 *
 * Está separado de {@link App} (que añade el enrutador del navegador y los
 * proveedores) para que los tests puedan montar estas MISMAS rutas con un
 * enrutador en memoria y comprobar redirecciones y guardas reales.
 */
export function AppRoutes() {
  return (
    <Routes>
      <Route
        path="/login"
        element={
          <RedirectIfAuthenticated>
            <LoginPage />
          </RedirectIfAuthenticated>
        }
      />
      <Route
        path="/registro"
        element={
          <RedirectIfAuthenticated>
            <PublicPage>
              <RegisterPage />
            </PublicPage>
          </RedirectIfAuthenticated>
        }
      />
      <Route
        path="/verificar-email"
        element={
          <PublicPage>
            <VerifyEmailPage />
          </PublicPage>
        }
      />
      <Route
        path="/recuperar-contrasena"
        element={
          <PublicPage>
            <ForgotPasswordPage />
          </PublicPage>
        }
      />
      <Route
        path="/restablecer-contrasena"
        element={
          <PublicPage>
            <ResetPasswordPage />
          </PublicPage>
        }
      />
      <Route
        path={WELCOME_PATH}
        element={
          <RequireAuth>
            <WelcomePage />
          </RequireAuth>
        }
      />
      <Route
        element={
          <RequireAuth>
            <AppShell />
          </RequireAuth>
        }
      >
        <Route path="/" element={<HomePage />} />
        <Route path="/peliculas" element={<MoviesPage />} />
        <Route path="/peliculas/:id" element={<MovieDetailPage />} />
        <Route path="/series" element={<SeriesPage />} />
        <Route path="/series/:id" element={<SeriesDetailPage />} />
        <Route path="/favorites" element={<MyListPage />} />
        <Route path="/perfil" element={<ProfilePage />} />
        {/* Alias /my-list → /favorites por compatibilidad con enunciado */}
        <Route path="/my-list" element={<Navigate to="/favorites" replace />} />
        <Route
          path="/admin"
          element={
            <RequireAdmin>
              {/* Tras confirmar el rol: el código del panel solo se descarga para un ADMIN. Si la descarga falla, el
                  aviso ocupa el sitio del panel y la barra sigue a la vista (ver LazyRouteBoundary). */}
              <LazyRouteBoundary>
                <Suspense fallback={<LoadingState label="Cargando el panel..." />}>
                  <AdminLayout />
                </Suspense>
              </LazyRouteBoundary>
            </RequireAdmin>
          }
        >
          {/* Rutas absolutas: un `to` relativo en la ruta comodín se resolvería respecto a la URL completa
              (/admin/xyz/peliculas), que vuelve a caer en el comodín. */}
          <Route index element={<Navigate to={ADMIN_MOVIES_PATH} replace />} />
          <Route path="peliculas" element={<AdminMoviesPage />} />
          <Route path="peliculas/nueva" element={<MovieFormPage />} />
          <Route path="peliculas/:id/editar" element={<MovieFormPage />} />
          <Route path="series" element={<AdminSeriesPage />} />
          <Route path="series/nueva" element={<SeriesFormPage />} />
          <Route path="series/:id/editar" element={<SeriesFormPage />} />
          <Route path="generos" element={<AdminGenresPage />} />
          <Route path="*" element={<Navigate to={ADMIN_MOVIES_PATH} replace />} />
        </Route>
        {/* Cualquier otra dirección: «Página no encontrada», con la barra (antes llevaba al inicio sin explicar nada). */}
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  );
}

/**
 * Raíz de la aplicación: proveedores globales y rutas ({@link AppRoutes}).
 *
 * Orden de los proveedores: `QueryClientProvider` (la caché de datos del
 * servidor, ver `lib/queryClient.ts`) y `ToastProvider` por fuera porque
 * `AuthProvider` necesita los dos (vacía la caché al cambiar de sesión y avisa
 * de "sesión caducada"). `BrowserRouter` envuelve a todo para que cualquier
 * componente pueda usar el enrutador.
 *
 * El `QueryClient` se crea UNA vez por montaje de `App` (`useState` con
 * inicializador) y no como variable de módulo: así cada montaje, también en
 * los tests, empieza con la caché vacía.
 *
 * `SkipLink` va antes de las rutas para ser lo primero que recibe el foco con el teclado.
 * `RouteChangeFocus`, después: al cambiar de página sube el scroll y lleva el
 * foco al contenido, una vez pintada (y enfocada, si lo hace) la página nueva.
 */
function App() {
  const [queryClient] = useState(createQueryClient);
  return (
    <BrowserRouter>
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <AuthProvider>
            {/* Primer elemento enfocable de toda la aplicación (ver SkipLink). */}
            <SkipLink />
            <AppRoutes />
            <RouteChangeFocus />
          </AuthProvider>
        </ToastProvider>
      </QueryClientProvider>
    </BrowserRouter>
  );
}

export default App;
