import { useCallback, useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Pencil, RotateCcw, TriangleAlert } from 'lucide-react';
import { Link } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { useProgressSaver } from '../../hooks/useProgressSaver';
import type { ProgressTarget } from '../../hooks/useProgressSaver';
import { formatTimestamp } from '../../lib/playback';
import { Button } from '../Button';
import { buttonClasses } from '../buttonStyles';
import { PlayerShell } from './PlayerShell';

/**
 * Pista de teclado del pie, solo con ratón o teclado (`pointer-fine`), como la
 * del tráiler. Escape no cierra mientras el foco está en los controles del vídeo
 * (ver `useEscapeToClose` en `PlayerShell`): la segunda frase dice cómo salir.
 */
const KEYBOARD_HINT = 'Pulsa Esc para salir. Si estás en los controles del vídeo, sal antes con Mayús+Tab.';

/**
 * Aviso del pie cuando no se pudo leer el progreso guardado. Dice las dos cosas
 * que pasan (empieza desde el principio y esta vez no se guarda) y por qué la
 * segunda: para no perder lo que ya tenía guardado.
 */
const PROGRESS_UNAVAILABLE_TEXT =
  'No se ha podido recuperar por dónde ibas: el vídeo empieza desde el principio y, para no borrar lo que tenías guardado, esta vez no se guardará por dónde vas.';

/**
 * Tiempo antes de enseñar «Cargando el vídeo…». Un archivo que llega enseguida
 * no debe hacer parpadear un aviso que nadie llega a leer.
 */
const LOADING_HINT_DELAY_MS = 400;

/** Qué ha pasado con el vídeo: cargando, listo o error (con el código de `MediaError`). */
type VideoPhase = { kind: 'loading' } | { kind: 'ready' } | { kind: 'error'; code: number | null };

/**
 * Explicación de un fallo del `<video>` según su `MediaError.code`, con
 * palabras de quien lo sufre (no «MEDIA_ERR_SRC_NOT_SUPPORTED»):
 * - 2 (red): se cortó la descarga;
 * - 3 (decodificación): el archivo está dañado o el navegador no lo entiende;
 * - 4 (origen no admitido): el navegador no ha podido abrir el archivo. Es lo
 *   que da también un archivo que no existe (404) o un servidor que no deja
 *   usarlo, así que el texto no culpa solo al formato.
 */
function mediaErrorMessage(code: number | null): string {
  switch (code) {
    case 2:
      return 'Se ha cortado la conexión mientras se cargaba el vídeo. Comprueba tu conexión e inténtalo de nuevo.';
    case 3:
      return 'El archivo de vídeo está dañado o tu navegador no puede reproducirlo.';
    case 4:
      return 'No se ha podido abrir el archivo de vídeo: puede que ya no esté disponible o que tu navegador no admita su formato.';
    default:
      return 'Ha ocurrido un problema al reproducir el vídeo.';
  }
}

/** Propiedades de {@link PlayerScreen}. */
export interface PlayerScreenProps {
  /** `<h1>` de la pantalla: la película o la serie. */
  title: string;
  /** Línea bajo el título: en un episodio, «T1:E2 · Mentiras». */
  subtitle?: string;
  /** URL del archivo de vídeo, ya validada con `getDirectVideoUrl`. */
  videoUrl: string;
  /** Portada que se ve mientras carga el vídeo (`imageUrl` del título). */
  posterUrl?: string;
  /** Segundo desde el que empezar (0 = el principio), de `resumePositionOf`. */
  resumeAt: number;
  /** Qué título se está viendo, para guardar su progreso. */
  target: ProgressTarget;
  /** Página del título: destino de «Volver» sin historial y de los enlaces de error y fin. */
  titlePath: string;
  /** Texto del enlace a la página del título: «Volver a Interstellar». */
  titleLinkLabel: string;
  /** Formulario del panel para este título (solo se enseña a un administrador, en los errores del vídeo). */
  editPath: string;
  /** Qué ofrecer al terminar el vídeo además de volver al título (p. ej. el siguiente episodio). */
  nextAction?: ReactNode;
  /** Texto del aviso de fin («Has terminado «Interstellar».»). */
  endedText: string;
  /**
   * `true` si no se pudo saber por dónde iba: empieza desde el principio, NO
   * guarda el progreso y el pie lo dice (ver «Progreso» en {@link PlayerScreen}).
   * Quien lo usa debe fijarlo al montar y no cambiarlo después (`WatchPage` lo
   * hace con `usePlayerStart`).
   */
  progressUnavailable?: boolean;
}

/**
 * Reproductor integrado: el `<video>` nativo del navegador con sus controles,
 * dentro de {@link PlayerShell} (cabecera con «Volver» y el título).
 *
 * **Por qué los controles nativos.** Ya son accesibles (teclado, lector de
 * pantalla, subtítulos cuando los haya), funcionan igual de bien con el dedo
 * que con el ratón, traen pantalla completa e imagen dentro de imagen y el
 * usuario ya los conoce. Unos propios serían cientos de líneas para hacer lo
 * mismo peor. Atributos:
 * - `controls`, `playsInline` (en iPhone, sin él el vídeo salta a pantalla
 *   completa al empezar) y `preload="auto"` (se va a reproducir ya).
 * - `poster`: la portada del título mientras llega el primer fotograma.
 * - Tamaño: 16:9 (`aspect-video`) desde el primer momento, sin saltos al cargar,
 *   y nunca más alto que la ventana (el ancho se limita por el alto disponible);
 *   `object-contain` encaja vídeos de otras proporciones sin recortarlos.
 *
 * **Empezar.** Al cargar los metadatos se salta a `resumeAt` (si cabe en el
 * vídeo) y se llama a `play()`. Se ha llegado aquí pulsando «Ver ahora» o
 * «Reanudar»: reproducir es lo que se ha pedido, así que no es una
 * reproducción automática en el sentido de WCAG 1.4.2 (y los controles dejan
 * pausar y bajar el volumen). Si no ha habido ningún gesto en la visita (se
 * abrió la dirección directamente, sin pulsar nada:
 * `navigator.userActivation.hasBeenActive` es `false`), ni se intenta; y si el
 * navegador no lo permite, `play()` falla en silencio. En los dos casos el
 * vídeo espera en pausa, con su botón de reproducir.
 *
 * **Reanudar.** Si se empieza donde se dejó (y el salto ha llegado: ver
 * `pendingResumeRef`), el pie lo dice («Sigues por 12:34») con **«Empezar
 * desde el principio»** al lado: reanudar es lo que quiere casi siempre quien
 * vuelve, así que no se pregunta antes (sería un clic más cada vez), pero
 * volver al principio está a un toque.
 *
 * **Estados.**
 * - Cargando: «Cargando el vídeo…» sobre el vídeo, solo si tarda más de
 *   {@link LOADING_HINT_DELAY_MS} (sin parpadeos).
 * - Error del vídeo (el archivo no existe, se corta la red, formato que el
 *   navegador no entiende): el vídeo se sustituye por un aviso que dice qué ha
 *   pasado ({@link mediaErrorMessage}), con «Reintentar» (vuelve a montar el
 *   `<video>` y sigue donde iba) y «Volver a…». A un administrador se le dice
 *   además dónde arreglarlo, con «Editar». Si el foco estaba en el vídeo (que
 *   desaparece), pasa al título del aviso.
 * - Fin: el pie dice que ha terminado (`role="status"`, se anuncia sin mover el
 *   foco) con «Volver a…» y, si se pasa, la siguiente acción (el siguiente episodio).
 *
 * **Progreso.** Lo guarda {@link useProgressSaver} con el elemento montado,
 * salvo si no se pudo leer el guardado (`progressUnavailable`): entonces no se
 * guarda nada en toda la sesión del reproductor (incidencia QA-3). Sin saber
 * qué hay en el servidor, cualquier guardado podría machacar un progreso bueno
 * (12 s encima de 1:20:00) y no hay forma de deshacerlo; no guardar solo pierde,
 * como mucho, lo que se vea esta vez, y el pie lo dice. Es la regla más simple
 * que nunca destruye datos: cuando no se sabe lo que hay, no se escribe encima.
 * (Esperar a que la lectura llegue más tarde no sirve de mucho: el vídeo ya ha
 * empezado desde el principio, y decidir qué posición «gana» sería adivinar.)
 */
export function PlayerScreen({
  title,
  subtitle,
  videoUrl,
  posterUrl,
  resumeAt,
  target,
  titlePath,
  titleLinkLabel,
  editPath,
  nextAction,
  endedText,
  progressUnavailable = false,
}: PlayerScreenProps) {
  const { isAdmin } = useAuth();
  // El elemento como estado (para engancharle el guardado en cuanto existe) y como `ref` (para moverlo: saltar a
  // un segundo o reproducir son cambios del elemento, no del estado de React).
  const [video, setVideo] = useState<HTMLVideoElement | null>(null);
  const videoRef = useRef<HTMLVideoElement | null>(null);
  const attachVideo = useCallback((element: HTMLVideoElement | null) => {
    videoRef.current = element;
    setVideo(element);
  }, []);
  const [phase, setPhase] = useState<VideoPhase>({ kind: 'loading' });
  const [showLoadingHint, setShowLoadingHint] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [resumedFrom, setResumedFrom] = useState<number | null>(null);
  const [ended, setEnded] = useState(false);
  /** Último segundo conocido: «Reintentar» tras un error sigue desde ahí y no desde `resumeAt`. */
  const lastPositionRef = useRef(resumeAt);
  /** Si ya se ha decidido desde dónde empezar (solo una vez; al reintentar, desde `lastPositionRef`). */
  const startedRef = useRef(false);
  /**
   * Segundo al que se ha pedido saltar al reanudar, hasta que el salto termina
   * (`seeked`). «Sigues por 12:34» solo se dice si el vídeo ha llegado de verdad:
   * un servidor que no admite peticiones parciales (`Range`) no deja saltar, y el
   * navegador se queda en el segundo 0 sin dar ningún error.
   */
  const pendingResumeRef = useRef<number | null>(null);
  const errorHeadingRef = useRef<HTMLHeadingElement>(null);

  useProgressSaver(phase.kind === 'error' ? null : video, progressUnavailable ? null : target);

  useEffect(() => {
    if (phase.kind !== 'loading') return;
    const timer = setTimeout(() => setShowLoadingHint(true), LOADING_HINT_DELAY_MS);
    return () => {
      clearTimeout(timer);
      setShowLoadingHint(false);
    };
  }, [phase.kind, attempt]);

  // El foco estaba en el vídeo (o en ningún sitio) y el vídeo ha desaparecido: al aviso, no a <body>.
  useEffect(() => {
    if (phase.kind !== 'error') return;
    const active = document.activeElement;
    if (active === null || active === document.body) errorHeadingRef.current?.focus();
  }, [phase.kind]);

  const handleLoadedMetadata = useCallback(() => {
    const video = videoRef.current;
    if (!video) return;
    const from = startedRef.current ? lastPositionRef.current : resumeAt;
    // Solo si cabe: si el archivo ha cambiado y es más corto, se empieza por el principio.
    if (from > 0 && from < video.duration - 1) {
      if (!startedRef.current) pendingResumeRef.current = from;
      video.currentTime = from;
    }
    startedRef.current = true;
    // Sin ningún gesto del usuario en esta visita (la dirección se abrió directamente), no se reproduce solo: con
    // sonido sería una sorpresa (WCAG 1.4.2) y algunos navegadores lo permitirían igualmente por su historial de
    // uso del sitio. El vídeo espera en pausa con su botón. Sin `userActivation` (navegadores antiguos), decide el
    // navegador.
    if (navigator.userActivation?.hasBeenActive === false) return;
    try {
      // Se ha pedido ver el vídeo (ver «Empezar»). Si el navegador no lo deja, espera en pausa.
      void video.play()?.catch(() => {});
    } catch {
      // Navegadores (o jsdom) sin `play()`: el vídeo queda en pausa con sus controles.
    }
  }, [resumeAt]);

  const handleSeeked = useCallback(() => {
    const from = pendingResumeRef.current;
    const video = videoRef.current;
    if (from === null || !video) return;
    pendingResumeRef.current = null;
    if (Math.abs(video.currentTime - from) < 1) setResumedFrom(from);
  }, []);

  const startOver = () => {
    const video = videoRef.current;
    if (!video) return;
    video.currentTime = 0;
    setResumedFrom(null);
    void video.play()?.catch(() => {});
  };

  const retry = () => {
    setPhase({ kind: 'loading' });
    setEnded(false);
    setAttempt((value) => value + 1);
  };

  const heading = (
    <>
      <h1 className="truncate text-base font-bold tracking-tight sm:text-lg">{title}</h1>
      {subtitle && <p className="truncate text-sm text-white/75">{subtitle}</p>}
    </>
  );

  const backToTitle = (
    <Link to={titlePath} className={buttonClasses('outline')}>
      {titleLinkLabel}
    </Link>
  );

  const footer = (
    <footer className="flex min-h-16 flex-wrap items-center gap-x-6 gap-y-3 px-4 py-3 sm:px-6">
      <div role="status" className="flex min-w-0 flex-wrap items-center gap-x-4 gap-y-2">
        {ended ? (
          <>
            <p className="text-sm text-white/85">{endedText}</p>
            {nextAction}
            {backToTitle}
          </>
        ) : (
          progressUnavailable && (
            <p className="text-sm text-white/75">{PROGRESS_UNAVAILABLE_TEXT}</p>
          )
        )}
      </div>
      {!ended && resumedFrom !== null && (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
          <p className="text-sm text-white/75 tabular-nums">Sigues por {formatTimestamp(resumedFrom)}.</p>
          <button
            type="button"
            onClick={startOver}
            className="focus-ring inline-flex min-h-11 items-center gap-2 rounded-lg px-3 text-sm font-semibold text-white/85 transition-[color,background-color,scale] duration-150 ease-out-strong hover:bg-white/10 hover:text-white active:scale-97 motion-reduce:active:scale-100"
          >
            <RotateCcw aria-hidden="true" className="size-4" />
            Empezar desde el principio
          </button>
        </div>
      )}
      <p className="ml-auto hidden text-xs text-muted pointer-fine:block">{KEYBOARD_HINT}</p>
    </footer>
  );

  return (
    <PlayerShell fallbackPath={titlePath} heading={heading} footer={footer}>
      <div className="relative mx-auto aspect-video w-full max-w-[calc((100dvh-9rem)*16/9)] bg-black">
        {phase.kind === 'error' ? (
          <div role="alert" className="flex size-full flex-col items-center justify-center gap-3 px-6 text-center">
            <TriangleAlert aria-hidden="true" className="size-8 text-danger" />
            <h2 ref={errorHeadingRef} tabIndex={-1} className="text-lg font-semibold outline-hidden">
              No se pudo reproducir el vídeo
            </h2>
            <p className="max-w-prose text-sm leading-relaxed text-white/75">
              {mediaErrorMessage(phase.code)}
              {isAdmin && ' Comprueba la dirección del vídeo desde «Editar».'}
            </p>
            <div className="mt-2 flex flex-wrap items-center justify-center gap-3">
              <Button variant="light" onClick={retry}>
                Reintentar
              </Button>
              {backToTitle}
              {isAdmin && (
                <Link to={editPath} aria-label={`Editar ${title}`} className={buttonClasses('ghost')}>
                  <Pencil aria-hidden="true" className="size-4" />
                  Editar
                </Link>
              )}
            </div>
          </div>
        ) : (
          <>
            <video
              key={attempt}
              ref={attachVideo}
              src={videoUrl}
              poster={posterUrl}
              controls
              playsInline
              preload="auto"
              aria-label={subtitle ? `${title}: ${subtitle}` : title}
              onLoadedMetadata={handleLoadedMetadata}
              onSeeked={handleSeeked}
              onCanPlay={() => setPhase((current) => (current.kind === 'loading' ? { kind: 'ready' } : current))}
              onTimeUpdate={(event) => {
                lastPositionRef.current = event.currentTarget.currentTime;
              }}
              onPlay={() => setEnded(false)}
              onEnded={() => setEnded(true)}
              onError={(event) => setPhase({ kind: 'error', code: event.currentTarget.error?.code ?? null })}
              className="size-full bg-black object-contain"
            />
            {phase.kind === 'loading' && showLoadingHint && (
              <div
                role="status"
                className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center gap-3"
              >
                <div
                  aria-hidden="true"
                  className="size-10 animate-spin rounded-full border-4 border-white/15 border-t-accent motion-reduce:animate-none"
                />
                <p className="rounded-md bg-black/60 px-2 py-1 text-sm text-white/85">Cargando el vídeo…</p>
              </div>
            )}
          </>
        )}
      </div>
    </PlayerShell>
  );
}
