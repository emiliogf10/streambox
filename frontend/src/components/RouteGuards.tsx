import { useState } from 'react';
import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { WELCOME_PATH, locationPath, needsGenresOnboarding } from '../lib/favoriteGenres';
import type { WelcomeState } from '../lib/favoriteGenres';
import { ErrorState } from './ErrorState';
import { LoadingState } from './LoadingState';
import { MAIN_CONTENT_ID } from './SkipLink';

/**
 * `<main>` de las pantallas que pintan las guardas ANTES de que exista la app
 * («Comprobando tu sesión...», «No se pudo comprobar tu sesión»): sin barra ni
 * `AppShell`, no habría ningún landmark principal y el enlace «Saltar al
 * contenido» (que busca `#contenido`) no tendría destino. Mismo `id` y
 * `tabIndex` que el `<main>` de `AppShell` y `AuthLayout`.
 */
function GuardScreen({ children }: { children: ReactNode }) {
  return (
    <main id={MAIN_CONTENT_ID} tabIndex={-1} className="outline-hidden">
      {children}
    </main>
  );
}

/**
 * Protege una ruta: sin sesión redirige a `/login`.
 *
 * Es también el mecanismo del cierre de sesión por 401: `AuthProvider` solo
 * limpia la sesión y este componente, al ver `isAuthenticated = false`, hace la
 * redirección una única vez.
 *
 * Mientras el arranque averigua si hay sesión (`GET /users/me` y, si da 401, el
 * intento de renovarla con el refresh token) espera con un indicador: redirigir
 * ya echaría al login a quien recarga con la sesión abierta. Si ese chequeo falla
 * por red, 5xx o 429 no se sabe si hay sesión, así que se ofrece reintentar en
 * lugar de mandar al login. El texto no dice «no hay conexión» porque no siempre
 * es el motivo (un 500 o un 429 sí tienen conexión).
 *
 * Esas dos pantallas van dentro de un `<main>` propio ({@link GuardScreen}) y la
 * de error lleva su título como `<h1>`: son la página entera en ese momento.
 *
 * **Pregunta de bienvenida.** Con sesión, si el usuario es un `USER` con
 * `genresOnboardingPending` (`GET /api/users/me`), cualquier ruta protegida le
 * lleva a `/bienvenida` guardando a dónde iba (`state.from`, con búsqueda y
 * fragmento), y allí vuelve al terminar. Va aquí porque es el único sitio por el
 * que pasan las dos situaciones que piden la pregunta, tras el login (que
 * redirige a `/`) y al cargar la app con sesión, y porque así nunca alcanza a
 * las páginas públicas (`/verificar-email`, `/restablecer-contrasena`...), que
 * no van dentro de esta guarda. Es **una sola vez**: al contestar o saltarla, el
 * servidor la cierra y la sesión deja de tenerla pendiente
 * (`useSaveFavoriteGenres`); si el servidor falla, «Continuar sin guardar» la
 * cierra solo en esta visita. Un ADMIN nunca la ve ({@link needsGenresOnboarding}).
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { isAuthenticated, isCheckingSession, sessionCheckFailed, refreshUser, user } = useAuth();
  const location = useLocation();
  if (isCheckingSession) {
    return (
      <GuardScreen>
        <LoadingState label="Comprobando tu sesión..." />
      </GuardScreen>
    );
  }
  if (sessionCheckFailed) {
    // El título es el `<h1>` de la pantalla y va DENTRO de la alerta: se anuncia una sola vez, junto al mensaje.
    return (
      <GuardScreen>
        <ErrorState
          headingLevel={1}
          title="No se pudo comprobar tu sesión"
          message="No hemos podido confirmar si tu sesión sigue abierta. Inténtalo de nuevo en unos instantes."
          onRetry={refreshUser}
        />
      </GuardScreen>
    );
  }
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  if (needsGenresOnboarding(user) && location.pathname !== WELCOME_PATH) {
    const state: WelcomeState = { from: locationPath(location) };
    return <Navigate to={WELCOME_PATH} replace state={state} />;
  }
  return <>{children}</>;
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
 *
 * **El indicador «Comprobando tu sesión...» solo tapa el formulario la PRIMERA
 * vez.** Mientras no se sabe si hay sesión se espera para que quien ya la tiene
 * no vea un parpadeo del formulario. Pero la sesión se puede volver a comprobar
 * con la pantalla ya pintada: cuando otra pestaña anuncia un cambio de sesión
 * (`onSessionChangedElsewhere` en `AuthContext`, p. ej. al cerrar allí la
 * sesión) el estado vuelve a «sin comprobar». Si en ese momento se cambiara el
 * formulario por el indicador, se DESMONTARÍA y se perdería lo que la persona
 * estaba escribiendo, casi siempre para volver a «sin sesión», es decir, para
 * nada. Por eso, una vez mostrado, el formulario se queda montado durante las
 * comprobaciones posteriores; si alguna confirma una sesión, se redirige a la
 * portada como siempre.
 *
 * El recuerdo («ya se mostró») se guarda con el patrón de React de actualizar
 * el estado durante el render a partir de la información del render anterior
 * (no con un `ref`, que no debe leerse ni escribirse al renderizar): React
 * repite el render en el acto, antes de pintar nada.
 */
export function RedirectIfAuthenticated({ children }: { children: ReactNode }) {
  const { isAuthenticated, isCheckingSession } = useAuth();
  const [contentShown, setContentShown] = useState(false);
  const showsContent = !isCheckingSession && !isAuthenticated;
  if (showsContent && !contentShown) setContentShown(true);
  // Sin esperar al chequeo inicial, quien ya tiene sesión vería un parpadeo del formulario.
  if (isCheckingSession && !contentShown) {
    return (
      <GuardScreen>
        <LoadingState label="Comprobando tu sesión..." />
      </GuardScreen>
    );
  }
  return isAuthenticated ? <Navigate to="/" replace /> : <>{children}</>;
}
