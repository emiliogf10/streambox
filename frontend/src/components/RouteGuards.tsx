import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';

/**
 * Protege una ruta: sin sesión redirige a `/login`.
 *
 * Es también el mecanismo del cierre de sesión por 401: `apiFetch` solo limpia
 * el token y este componente, al ver `isAuthenticated = false`, hace la
 * redirección una única vez.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { isAuthenticated } = useAuth();
  return isAuthenticated ? <>{children}</> : <Navigate to="/login" replace />;
}

/**
 * Envuelve las pantallas públicas (`/login`, `/registro`): si ya hay sesión,
 * no tiene sentido mostrarlas y se redirige a la portada.
 */
export function RedirectIfAuthenticated({ children }: { children: ReactNode }) {
  const { isAuthenticated } = useAuth();
  return isAuthenticated ? <Navigate to="/" replace /> : <>{children}</>;
}
