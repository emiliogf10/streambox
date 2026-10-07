import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AppShell } from './components/AppShell';
import { RedirectIfAuthenticated, RequireAdmin, RequireAuth } from './components/RouteGuards';
import { SkipLink } from './components/SkipLink';
import { AuthProvider } from './context/AuthContext';
import { ToastProvider } from './context/ToastContext';
import { AdminGenresPage } from './pages/admin/AdminGenresPage';
import { AdminLayout } from './pages/admin/AdminLayout';
import { AdminMoviesPage } from './pages/admin/AdminMoviesPage';
import { ADMIN_MOVIES_PATH } from './pages/admin/adminPaths';
import { AdminSeriesPage } from './pages/admin/AdminSeriesPage';
import { MovieFormPage } from './pages/admin/MovieFormPage';
import { SeriesFormPage } from './pages/admin/SeriesFormPage';
import { HomePage } from './pages/HomePage';
import { LoginPage } from './pages/LoginPage';
import { MoviesPage } from './pages/MoviesPage';
import { MyListPage } from './pages/MyListPage';
import { ProfilePage } from './pages/ProfilePage';
import { RegisterPage } from './pages/RegisterPage';
import { SeriesDetailPage } from './pages/SeriesDetailPage';
import { SeriesPage } from './pages/SeriesPage';

/**
 * Mapa de rutas de la aplicación.
 *
 * - `/login` y `/registro`: públicas; si ya hay sesión redirigen a `/`.
 * - `/`, `/peliculas` (con `?genero=&anio=&orden=`), `/series`, `/series/:id`
 *   (con `?temporada=N`), `/favorites` y `/perfil` (datos de la cuenta y resumen de
 *   la lista): privadas, dentro de `AppShell` (barra + contenido). A `/perfil` se
 *   llega desde «Mi perfil» en el menú de usuario de la barra.
 * - `/admin/*`: panel de administración, también dentro de `AppShell` y además
 *   tras `RequireAdmin` (espera a conocer el rol; si no es `ADMIN`, a `/`).
 *   `/admin` lleva a `/admin/peliculas`; el resto de rutas del panel son el
 *   listado, el alta (`peliculas/nueva`) y la edición (`peliculas/:id/editar`)
 *   de películas, lo mismo para series (`series`, `series/nueva`,
 *   `series/:id/editar`) y los géneros (`generos`). Una ruta desconocida del
 *   panel vuelve al listado de películas.
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
            <RegisterPage />
          </RedirectIfAuthenticated>
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
              <AdminLayout />
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
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

/**
 * Raíz de la aplicación: proveedores globales y rutas ({@link AppRoutes}).
 *
 * Orden de los proveedores: `ToastProvider` por fuera porque `AuthProvider`
 * lo necesita (avisa de "sesión caducada"). `BrowserRouter` envuelve a todo
 * para que cualquier componente pueda usar el enrutador.
 *
 * `SkipLink` va antes de las rutas para ser lo primero que recibe el foco con el teclado.
 */
function App() {
  return (
    <BrowserRouter>
      <ToastProvider>
        <AuthProvider>
          {/* Primer elemento enfocable de toda la aplicación (ver SkipLink). */}
          <SkipLink />
          <AppRoutes />
        </AuthProvider>
      </ToastProvider>
    </BrowserRouter>
  );
}

export default App;
