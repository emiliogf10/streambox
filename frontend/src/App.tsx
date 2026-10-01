import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AppShell } from './components/AppShell';
import { RedirectIfAuthenticated, RequireAuth } from './components/RouteGuards';
import { SkipLink } from './components/SkipLink';
import { AuthProvider } from './context/AuthContext';
import { ToastProvider } from './context/ToastContext';
import { HomePage } from './pages/HomePage';
import { LoginPage } from './pages/LoginPage';
import { MyListPage } from './pages/MyListPage';
import { RegisterPage } from './pages/RegisterPage';

/**
 * Raíz de la aplicación: proveedores globales y rutas.
 *
 * Orden de los proveedores: `ToastProvider` por fuera porque `AuthProvider`
 * lo necesita (avisa de "sesión caducada"). `BrowserRouter` envuelve a todo
 * para que cualquier componente pueda usar el enrutador.
 *
 * Rutas:
 * - `/login` y `/registro`: públicas; si ya hay sesión redirigen a `/`.
 * - `/` y `/favorites`: privadas, dentro de `AppShell` (barra + contenido).
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
              <Route path="/favorites" element={<MyListPage />} />
              {/* Alias /my-list → /favorites por compatibilidad con enunciado */}
              <Route path="/my-list" element={<Navigate to="/favorites" replace />} />
            </Route>
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        </AuthProvider>
      </ToastProvider>
    </BrowserRouter>
  );
}

export default App;
