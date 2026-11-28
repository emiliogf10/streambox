import { useCallback, useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Pencil, RotateCcw, TriangleAlert } from 'lucide-react';
import { Link } from 'react-router-dom';
import { useAuth } from '../../context/AuthContext';
import { useFirstLoadableImage } from '../../hooks/useFirstLoadableImage';
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
 * Aviso del pie cuando los subtítulos no se han podido usar (no cargó la pista,
 * o el vídeo no admitía CORS y se ha vuelto a cargar sin ella). Dice lo que ha
 * pasado y lo que importa a quien mira: el vídeo sigue. No culpa a nadie: desde
 * el navegador no se sabe si fue CORS, un 404 o la red.
 */
const SUBTITLES_UNAVAILABLE_TEXT = 'No se han podido cargar los subtítulos: el vídeo sigue sin ellos.';

/** Lo que se añade al aviso de subtítulos para un administrador: dónde mirar. */
const SUBTITLES_UNAVAILABLE_ADMIN_TEXT =
  ' Comprueba su URL en el panel y que los servidores del vídeo y de los subtítulos permitan CORS.';

/**
 * Tiempo antes de enseñar «Cargando el vídeo…». Un archivo que llega enseguida
 * no debe hacer parpadear un aviso que nadie llega a leer.
 */
const LOADING_HINT_DELAY_MS = 400;

/** Qué ha pasado con el vídeo: cargando, listo o error (con el código de `MediaError`). */
type VideoPhase = { kind: 'loading' } | { kind: 'ready' } | { kind: 'error'; code: number | null };

/**
 * Cómo se monta el `<video>` en cada intento: `attempt` es su `key` (cambiarla lo
 * vuelve a montar desde cero) y `crossOrigin` dice si va con CORS. Van juntos
 * porque CORS se decide al montar y no se cambia en un elemento vivo: el
 * navegador lo lee al empezar a descargar, y cambiarlo a mitad no recargaría nada.
 */
interface VideoMount {
  attempt: number;
  crossOrigin: boolean;
}

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

/** Lista vacía compartida: sin candidatas, sin `poster` (y una referencia estable entre renderizados). */
const NO_POSTER: readonly string[] = [];

/** Propiedades de {@link PlayerScreen}. */
export interface PlayerScreenProps {
  /** `<h1>` de la pantalla: la película o la serie. */
  title: string;
  /** Línea bajo el título: en un episodio, «T1:E2 · Mentiras». */
  subtitle?: string;
  /** URL del archivo de vídeo, ya validada con `getDirectVideoUrl`. */
  videoUrl: string;
  /**
   * Imágenes candidatas para lo que se ve mientras carga el vídeo, en orden de
   * preferencia: horizontales primero y la portada del título al final (ver
   * `moviePlayerPosterCandidates` y `episodePlayerPosterCandidates` en
   * `lib/titleImages.ts`). Se usa la primera que cargue (ver «`poster`» en
   * {@link PlayerScreen}).
   */
  posterCandidates?: readonly string[];
  /** Segundo desde el que empezar (0 = el principio), de `resumePositionOf`. */
  resumeAt: number;
  /** Qué título se está viendo, para guardar su progreso. */
  target: ProgressTarget;
  /**
   * Subtítulos en español (WebVTT), ya validados con `getSubtitlesUrl`, o `null`
   * si no hay. Ver «Subtítulos» en {@link PlayerScreen}.
   */
  subtitlesUrl?: string | null;
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
 * - `poster`: lo que se ve mientras llega el primer fotograma. El hueco es
 *   16:9, así que se prefiere una imagen horizontal (la de fondo del título o
 *   la del episodio) y solo si no hay, la portada vertical (`object-contain`
 *   la encaja con bandas a los lados). Como el atributo no avisa si la imagen
 *   falla, {@link useFirstLoadableImage} usa la primera candidata desde el
 *   principio y salta a la siguiente si no carga: una imagen de fondo rota no
 *   deja el vídeo en negro.
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
 * **Subtítulos.** Si el título los tiene, el `<video>` lleva una pista
 * `<track kind="subtitles" srcLang="es" label="Español">` sin `default`:
 * desactivada salvo que el usuario la active con el botón de subtítulos de los
 * controles nativos (o que su sistema pida mostrarlos siempre). Reglas, todas
 * para que **los subtítulos nunca rompan ni retrasen el vídeo**:
 * - Una pista de otro origen solo se carga si el `<video>` lleva
 *   `crossOrigin="anonymous"`, y entonces el navegador pide **también el vídeo**
 *   con CORS: un servidor de vídeo que no lo admite haría fallar un vídeo que
 *   sin subtítulos se vería bien. Por eso `crossOrigin` solo se pone cuando hay
 *   pista (sin subtítulos, el vídeo se carga como siempre).
 * - Si el vídeo falla con `crossOrigin` puesto **antes de sus metadatos**
 *   (`loadedmetadata`), se vuelve a montar UNA vez sin `crossOrigin` y sin
 *   pista, sin pasar por la pantalla de error. `MediaError` no distingue un
 *   rechazo de CORS de otros fallos (y no debe: el navegador lo oculta a
 *   propósito), pero un rechazo de CORS impide leer la respuesta del vídeo, así
 *   que llega siempre antes de los metadatos. Si vuelve a fallar, es un error
 *   del vídeo de los de siempre. Empieza en el mismo sitio: si aún no había
 *   empezado, en `resumeAt` (con su «Sigues por…»); si ya iba por un punto,
 *   desde ahí (`lastPositionRef`). El guardado se engancha al elemento nuevo
 *   como al reintentar.
 * - Un error **después** de los metadatos (p. ej. la red se corta a mitad) ya
 *   no puede ser de CORS: el vídeo se estaba viendo con él. Es el error de
 *   siempre, con «Reintentar», sin culpar a los subtítulos, y al reintentar se
 *   vuelve a montar **con** pista, porque no han fallado (revisión QA, BAJA-3).
 * - Si lo que falla es solo la pista (evento `error` del `<track>`, que no
 *   burbujea: se escucha en el propio elemento), se quita y el vídeo sigue.
 * - En los dos casos el pie lo dice con {@link SUBTITLES_UNAVAILABLE_TEXT}
 *   (`role="status"`: se anuncia sin mover el foco ni tapar el vídeo) y no se
 *   vuelven a intentar en esta visita, tampoco con «Reintentar»: si el vídeo
 *   falla después por otra causa, no se arriesga a fallar dos veces.
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
  subtitlesUrl = null,
  posterCandidates = NO_POSTER,
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
  const posterUrl = useFirstLoadableImage(posterCandidates);
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
  const [mount, setMount] = useState<VideoMount>(() => ({ attempt: 0, crossOrigin: subtitlesUrl !== null }));
  /** Los subtítulos han fallado (la pista o el vídeo con CORS): ya no se usan en esta visita y el pie lo dice. */
  const [subtitlesFailed, setSubtitlesFailed] = useState(false);
  /** Pista que lleva el `<video>` de este intento: solo con CORS puesto (sin él no cargaría) y si no ha fallado. */
  const trackUrl = mount.crossOrigin && !subtitlesFailed ? subtitlesUrl : null;
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
  /**
   * Si el `<video>` montado ahora ha llegado a `loadedmetadata`. Separa un fallo
   * que puede ser de CORS (antes: el navegador no ha podido leer el vídeo) de
   * uno del vídeo que ya se estaba viendo (después). Es una `ref` porque solo
   * la lee el manejador del error, y vuelve a `false` en cada montaje nuevo.
   */
  const metadataLoadedRef = useRef(false);
  const errorHeadingRef = useRef<HTMLHeadingElement>(null);

  useProgressSaver(phase.kind === 'error' ? null : video, progressUnavailable ? null : target);

  useEffect(() => {
    if (phase.kind !== 'loading') return;
    const timer = setTimeout(() => setShowLoadingHint(true), LOADING_HINT_DELAY_MS);
    return () => {
      clearTimeout(timer);
      setShowLoadingHint(false);
    };
  }, [phase.kind, mount.attempt]);

  // El foco estaba en el vídeo (o en ningún sitio) y el vídeo ha desaparecido: al aviso, no a <body>.
  useEffect(() => {
    if (phase.kind !== 'error') return;
    const active = document.activeElement;
    if (active === null || active === document.body) errorHeadingRef.current?.focus();
  }, [phase.kind]);

  const handleLoadedMetadata = useCallback(() => {
    const video = videoRef.current;
    if (!video) return;
    metadataLoadedRef.current = true;
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
    metadataLoadedRef.current = false;
    setPhase({ kind: 'loading' });
    setEnded(false);
    setMount((current) => ({
      attempt: current.attempt + 1,
      crossOrigin: subtitlesUrl !== null && !subtitlesFailed,
    }));
  };

  /**
   * Error del `<video>`. Con `crossOrigin` puesto y **antes de los metadatos**
   * (el único momento en que CORS puede impedir el vídeo), se vuelve a montar
   * sin él ni pista y sin pasar por la pantalla de error (ver «Subtítulos»); en
   * cualquier otro caso es el error de siempre, sin tocar los subtítulos.
   */
  const handleVideoError = (code: number | null) => {
    if (mount.crossOrigin && !metadataLoadedRef.current) {
      setSubtitlesFailed(true);
      setEnded(false);
      setPhase({ kind: 'loading' });
      setMount((current) => ({ attempt: current.attempt + 1, crossOrigin: false }));
      return;
    }
    setPhase({ kind: 'error', code });
  };

  /**
   * Escucha el `error` de la pista (no burbujea, así que no basta con escuchar
   * en el `<video>`). Es una `ref` con limpieza (React 19): se engancha al
   * montarse la pista y se suelta al quitarla.
   */
  const attachTrack = useCallback((track: HTMLTrackElement | null) => {
    if (!track) return undefined;
    const onError = () => setSubtitlesFailed(true);
    track.addEventListener('error', onError);
    return () => track.removeEventListener('error', onError);
  }, []);

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
          <>
            {progressUnavailable && <p className="text-sm text-white/75">{PROGRESS_UNAVAILABLE_TEXT}</p>}
            {/* Con el vídeo en error no se dice: «el vídeo sigue» sería mentira y ya hay un aviso mayor. */}
            {subtitlesFailed && phase.kind !== 'error' && (
              <p className="text-sm text-white/75">
                {SUBTITLES_UNAVAILABLE_TEXT}
                {isAdmin && SUBTITLES_UNAVAILABLE_ADMIN_TEXT}
              </p>
            )}
          </>
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
              key={mount.attempt}
              ref={attachVideo}
              src={videoUrl}
              crossOrigin={mount.crossOrigin ? 'anonymous' : undefined}
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
              onError={(event) => handleVideoError(event.currentTarget.error?.code ?? null)}
              className="size-full bg-black object-contain"
            >
              {trackUrl && (
                <track ref={attachTrack} kind="subtitles" srcLang="es" label="Español" src={trackUrl} />
              )}
            </video>
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
