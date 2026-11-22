import { useCallback, useEffect } from 'react';
import type { ReactNode } from 'react';
import { ArrowLeft } from 'lucide-react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useHasAppEntryBehind } from '../../context/InAppHistoryContext';
import { MAIN_CONTENT_ID } from '../SkipLink';

/**
 * Devuelve la función que cierra el reproductor: vuelve a la pantalla de antes.
 *
 * - Si detrás de esta entrada del historial hay otra pantalla de StreamBox
 *   (se llegó con un enlace de la propia aplicación), `navigate(-1)`: «Volver»
 *   hace lo mismo que el botón «Atrás» del navegador y deja al usuario donde
 *   estaba (la página del título, la portada con «Seguir viendo»...), con su scroll.
 * - Si no (se abrió directamente: un enlace pegado, una recarga), no hay nada
 *   «atrás» dentro de StreamBox y `navigate(-1)` sacaría al usuario de la
 *   aplicación. Entonces va a `fallbackPath` (la página del título),
 *   sustituyendo la entrada para que «Atrás» no vuelva a abrir el vídeo.
 *
 * Quién decide si hay pantalla detrás es `InAppHistoryProvider`, que cuenta las
 * navegaciones de toda la visita. No vale mirar si `location.key === 'default'`
 * (la clave de la primera entrada): «Ver el siguiente» navega con `replace`,
 * que da una clave nueva a una entrada que sigue siendo la primera, y «Volver»
 * sacaba de StreamBox (incidencia QA-1). Se pregunta al pulsar, con la clave de
 * esta entrada.
 *
 * @param fallbackPath adónde ir si no hay pantalla anterior
 */
function usePlayerClose(fallbackPath: string): () => void {
  const navigate = useNavigate();
  const { key } = useLocation();
  const hasAppEntryBehind = useHasAppEntryBehind();
  return useCallback(() => {
    if (hasAppEntryBehind(key)) void navigate(-1);
    else void navigate(fallbackPath, { replace: true });
  }, [hasAppEntryBehind, key, navigate, fallbackPath]);
}

/**
 * Escape cierra el reproductor, salvo en dos casos en los que la tecla ya es de otro:
 * - **Pantalla completa**: el navegador la usa para salir de ella (y ni
 *   siquiera la entrega a la página); salir del todo exige otra pulsación.
 * - **Foco en los controles del vídeo**: los del `<video>` nativo (el menú de
 *   velocidad, subtítulos...) también cierran con Escape, y esas pulsaciones
 *   llegan a la página con el `<video>` como destino. Cerrar el reproductor
 *   al cerrar un menú sería una sorpresa; desde ahí, Mayús+Tab devuelve el foco
 *   a «Volver» (lo dice la pista del pie, como en el tráiler).
 * Tampoco si alguien ya la ha atendido (`defaultPrevented`) o lleva modificadores.
 */
function useEscapeToClose(close: () => void): void {
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return;
      if (document.fullscreenElement) return;
      if (event.target instanceof HTMLMediaElement) return;
      close();
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [close]);
}

/** Propiedades de {@link PlayerShell}. */
interface PlayerShellProps {
  /** Adónde lleva «Volver» si no hay pantalla anterior (ver {@link usePlayerClose}). */
  fallbackPath: string;
  /**
   * Título y subtítulo de la cabecera. El `<h1>` lo pone quien lo usa (cambia
   * con cada estado); `null` si el `<h1>` va en el contenido (p. ej. «Película no encontrada»).
   */
  heading: ReactNode;
  children: ReactNode;
  /** Pie (opcional): avisos y acciones bajo el vídeo. */
  footer?: ReactNode;
}

/**
 * Marco de la pantalla de reproducción (`/ver/...`): fondo negro a toda la
 * ventana, una cabecera con «Volver» y el título, el contenido centrado en el
 * espacio que queda y, opcionalmente, un pie.
 *
 * **Por qué una página y no un diálogo.** El vídeo es la tarea entera mientras
 * dura, no una interrupción de otra: tiene dirección propia (se puede recargar
 * o enlazar), «Atrás» del navegador lo cierra como cualquier página y no hay
 * nada detrás que mantener inerte. Va fuera de `AppShell`, sin la barra de
 * navegación ni el buscador, como la pantalla de bienvenida: solo distraerían
 * del vídeo y le quitarían alto.
 *
 * El `<main id="contenido">` es el destino de «Saltar al contenido» y de
 * `RouteChangeFocus` (que lleva ahí el foco al llegar): el primer Tab alcanza
 * «Volver», el siguiente los controles del vídeo.
 *
 * **Escape** también cierra (ver {@link useEscapeToClose}), en todos los
 * estados de la pantalla: cargando, error o vídeo.
 *
 * «Volver» es un botón y no un enlace: no siempre sabe adónde va (vuelve a la
 * pantalla de antes, sea cual sea), y su texto es verdad en todos los casos.
 * Mide 44 px de alto (objetivo táctil) y lleva el icono y la palabra.
 *
 * `data-player-screen` pinta de negro también el fondo de `html` mientras esta
 * pantalla está montada (ver `index.css`): el hueco reservado para la barra de
 * scroll dejaría si no una franja gris junto al vídeo.
 */
export function PlayerShell({ fallbackPath, heading, children, footer }: PlayerShellProps) {
  const close = usePlayerClose(fallbackPath);
  useEscapeToClose(close);
  return (
    <main id={MAIN_CONTENT_ID} tabIndex={-1} data-player-screen className="flex min-h-dvh flex-col bg-black text-white outline-hidden">
      <header className="flex items-center gap-3 px-2 py-2 sm:gap-4 sm:px-4 sm:py-3">
        <button
          type="button"
          onClick={close}
          className="focus-ring inline-flex min-h-11 shrink-0 items-center gap-2 rounded-lg px-3 text-sm font-semibold text-white/85 transition-[color,background-color,scale] duration-150 ease-out-strong hover:bg-white/10 hover:text-white active:scale-97 motion-reduce:active:scale-100"
        >
          <ArrowLeft aria-hidden="true" className="size-5" />
          Volver
        </button>
        <div className="min-w-0">{heading}</div>
      </header>
      <div className="flex flex-1 flex-col items-center justify-center">{children}</div>
      {footer}
    </main>
  );
}
