import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { ErrorState } from './ErrorState';
import { LoadingState } from './LoadingState';

/**
 * Protege una ruta: sin sesión redirige a `/login`.
 *
 * Es también el mecanismo del cierre de sesión por 401: `AuthProvider` solo
 * limpia la sesión y este componente, al ver `isAuthenticated = false`, hace la
 * redirección una única vez.
 *
 * Mientras el arranque averigua si hay sesión (`GET /users/me`) espera con un
 * indicador: redirigir ya echaría al login a quien recarga con la sesión abierta.
 * Si ese chequeo falla por red o 5xx no se sabe si hay sesión, así que se ofrece
 * reintentar en lugar de mandar al login.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { isAuthenticated, isCheckingSession, sessionCheckFailed, refreshUser } = useAuth();
  if (isCheckingSession) return <LoadingState label="Comprobando tu sesión..." />;
  if (sessionCheckFailed) {
    return (
      <ErrorState
        title="No se pudo comprobar tu sesión"
        message="No se ha podido conectar con el servidor. Inténtalo de nuevo."
        onRetry={refreshUser}
      />
    );
  }
  return isAuthenticated ? <>{children}</> : <Navigate to="/login" replace />;
}

/**
 * Protege el panel de administración (`/admin/*` en `App`). Va DENTRO de
 * {@link RequireAuth}, que ya se ocupa de la falta de sesión.
 *
 * Según `userStatus` de `AuthContext`:
 * - `loading` (o `idle`): espera con un indicador. Redirigir ya echaría a un
 *   administrador de verdad solo porque `/users/me` aún no ha contestado (p. ej.
 *   al recargar la página estando en `/admin`).
 * - `error`: no se sabe el rol, así que NO se enseña el contenido (falla
 *   cerrado), pero tampoco se expulsa: un fallo de red no debe sacar al
 *   administrador de su pantalla. Se ofrece "Reintentar".
 * - `ready` y no es `ADMIN`: redirige a la portada.
 *
 * Es solo comodidad de la interfaz: ocultar la pantalla no protege nada; los
 * endpoints de administración ya responden 403 a quien no es `ADMIN`.
 */
export function RequireAdmin({ children }: { children: ReactNode }) {
  const { isAdmin, userStatus, refreshUser } = useAuth();
  if (userStatus === 'error') {
    return (
      <ErrorState
        title="No se pudo comprobar tu cuenta"
        message="No se ha podido confirmar si tienes permisos de administración. Inténtalo de nuevo."
        onRetry={refreshUser}
      />
    );
  }
  if (userStatus !== 'ready') return <LoadingState label="Comprobando permisos..." />;
  return isAdmin ? <>{children}</> : <Navigate to="/" replace />;
}

/**
 * Envuelve las pantallas públicas (`/login`, `/registro`): si ya hay sesión,
 * no tiene sentido mostrarlas y se redirige a la portada. Si el chequeo inicial
 * falló (sin saber si hay sesión) se muestran igualmente: el login sigue siendo
 * posible y el servidor decidirá.
 */
export function RedirectIfAuthenticated({ children }: { children: ReactNode }) {
  const { isAuthenticated, isCheckingSession } = useAuth();
  // Sin esperar al chequeo inicial, quien ya tiene sesión vería un parpadeo del formulario.
  if (isCheckingSession) return <LoadingState label="Comprobando tu sesión..." />;
  return isAuthenticated ? <Navigate to="/" replace /> : <>{children}</>;
}
