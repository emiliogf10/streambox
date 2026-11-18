import { Component } from 'react';
import type { ReactNode } from 'react';
import { useLocation } from 'react-router-dom';
import { ChunkLoadError, reloadPage } from '../lib/lazyChunks';
import { ErrorState } from './ErrorState';
import { RestoreContentFocus } from './LazyScreenFocus';
import { GuardScreen } from './RouteGuards';

/** Propiedades de {@link LazyRouteBoundary}. */
interface LazyRouteBoundaryProps {
  children: ReactNode;
  /**
   * `true` en las pantallas públicas, que se pintan SIN `AppShell`: el aviso va
   * dentro de su propio `<main>` ({@link GuardScreen}), como las esperas de esas
   * pantallas. Dentro de `AppShell` (el panel) ya hay `<main>` y barra: el aviso
   * ocupa el sitio de la pantalla y la barra sigue ahí para irse a otra sección.
   */
  standalone?: boolean;
  /** Acción de «Recargar la página»; solo la cambian los tests (jsdom no recarga). */
  onReload?: () => void;
}

/** Propiedades del componente de clase: las de fuera más la ruta actual. */
interface BoundaryProps extends LazyRouteBoundaryProps {
  /** Ruta actual (`pathname`): al cambiar, el límite vuelve a intentar pintar a sus hijos. */
  resetKey: string;
}

/** Estado del límite: si ha capturado un error y en qué ruta. */
interface BoundaryState {
  /** `true` tras capturar un error (aparte de `error`, porque se puede lanzar `null`). */
  hasError: boolean;
  error: unknown;
  /** Ruta en la que está el límite; si cambia, se olvida el error. */
  resetKey: string;
}

/**
 * Límite de errores (*error boundary*) de React. Tiene que ser un componente de
 * CLASE: React solo ofrece capturar errores de pintado con
 * `getDerivedStateFromError`, que no tiene equivalente en hooks.
 */
class RouteErrorBoundary extends Component<BoundaryProps, BoundaryState> {
  state: BoundaryState = { hasError: false, error: null, resetKey: this.props.resetKey };

  static getDerivedStateFromError(error: unknown): Partial<BoundaryState> {
    return { hasError: true, error };
  }

  /**
   * Al cambiar de ruta (p. ej. de una sección del panel a otra) se olvida el
   * error y se vuelve a intentar: un error de pintado de UNA pantalla no debe
   * bloquear las demás. Si el trozo no se descargó, la nueva ruta vuelve a fallar
   * (`React.lazy` guarda el rechazo) y el aviso reaparece, que es lo verdadero.
   */
  static getDerivedStateFromProps(props: BoundaryProps, state: BoundaryState): Partial<BoundaryState> | null {
    if (props.resetKey === state.resetKey) return null;
    return { hasError: false, error: null, resetKey: props.resetKey };
  }

  // Sin `componentDidCatch`: NO se silencia nada. React ya escribe en la consola todo error que captura un límite
  // (con la pila de componentes; el `onCaughtError` por defecto de `createRoot`), en desarrollo y en producción.

  render() {
    if (!this.state.hasError) return this.props.children;

    const isChunkError = this.state.error instanceof ChunkLoadError;
    // Mismo título para las dos causas (lo que ve la persona es lo mismo: la pantalla no está) y una explicación
    // verdadera para cada una. Sirve igual para un USER y un ADMIN: no habla del rol ni de la cuenta.
    const message = isChunkError
      ? 'Puede que se haya cortado la conexión o que StreamBox se haya actualizado mientras tenías la página abierta. Recarga la página para intentarlo de nuevo.'
      : 'Ha ocurrido un error inesperado al mostrarla. Recarga la página para intentarlo de nuevo.';
    // `h1`: el aviso ES la pantalla (la que no se pudo pintar llevaba el suyo). `role="alert"` (de `ErrorState`) lo
    // anuncia al aparecer. El foco, como en cualquier cambio de página: al llegar navegando, `RouteChangeFocus` lo
    // lleva al `<main>`; si ese `<main>` era el de la espera y el aviso trae otro (`standalone`), `RestoreContentFocus`
    // lo pasa al nuevo para que no caiga en `<body>`.
    const alert = (
      <ErrorState
        headingLevel={1}
        title="No se pudo cargar esta pantalla"
        message={message}
        onRetry={this.props.onReload ?? reloadPage}
        retryLabel="Recargar la página"
      />
    );
    return (
      <>
        {this.props.standalone ? <GuardScreen>{alert}</GuardScreen> : alert}
        <RestoreContentFocus />
      </>
    );
  }
}

/**
 * Evita la página en blanco cuando una pantalla que se carga bajo demanda
 * (`React.lazy` en `App.tsx`) no llega a pintarse. Va alrededor de cada
 * `Suspense` diferido: el panel (`/admin/*`, dentro de `AppShell`) y las
 * pantallas públicas de cuenta (`standalone`).
 *
 * **Por qué.** Si falla la descarga del trozo (un despliegue con la pestaña
 * abierta: el trozo con el hash viejo ya no existe; o un corte de red), React
 * lanza el error al pintar y, sin un límite, desmonta toda la aplicación.
 *
 * **Qué enseña.** «No se pudo cargar esta pantalla» (con `ErrorState`, como el
 * resto de errores) y un botón «Recargar la página». No es «Reintentar»: un
 * `import()` fallido queda así en la caché de módulos del navegador y
 * `React.lazy` guarda la promesa rechazada, así que volver a pintar no sirve;
 * recargar trae además la versión nueva tras un despliegue. Si el error es de
 * otra causa (un fallo al pintar), el texto no habla de conexión.
 *
 * **Por qué no recarga sola** (lo que sugiere la documentación de Vite con el
 * evento `vite:preloadError`): una recarga automática se lleva por delante lo
 * que se estuviera escribiendo, puede entrar en bucle si el trozo de verdad no
 * existe y, sin red, deja la página de error del navegador en lugar de la
 * nuestra. Decide la persona, con un aviso que explica qué ha pasado.
 *
 * **Alcance.** Solo cubre lo que hay dentro: no es un «todo vale» para toda la
 * aplicación, y los errores siguen apareciendo en la consola.
 */
export function LazyRouteBoundary(props: LazyRouteBoundaryProps) {
  const { pathname } = useLocation();
  return <RouteErrorBoundary {...props} resetKey={pathname} />;
}
