import { useLayoutEffect, useRef } from 'react';
import { Play, X } from 'lucide-react';
import { Link } from 'react-router-dom';
import { continueWatchingName, useContinueWatching, useRemoveFromContinueWatching } from '../hooks/useWatchProgress';
import { episodePlayerPath, formatRemaining, isResumablePosition, moviePlayerPath } from '../lib/playback';
import { episodeCode } from '../lib/series';
import type { ContinueWatchingItem } from '../lib/types';
import { MoviePoster } from './MoviePoster';
import { PosterRow } from './PosterRow';
import { RowError } from './RowError';
import { MAIN_CONTENT_ID } from './SkipLink';
import { WatchProgressBar } from './WatchProgressBar';

/** Título de la fila. */
const ROW_TITLE = 'Seguir viendo';

/** Clave única de una tarjeta: una película y un episodio pueden tener el mismo id. */
function itemKey(item: ContinueWatchingItem): string {
  return `${item.type}:${item.id}`;
}

/** Reproductor del elemento: directamente al vídeo, que reanuda donde se dejó. */
function playerPathOf(item: ContinueWatchingItem): string {
  return item.type === 'MOVIE' ? moviePlayerPath(item.id) : episodePlayerPath(item.seriesId ?? 0, item.id);
}

/** Por dónde va el usuario en el título de la tarjeta, o `null` si aún no lo ha empezado (el siguiente episodio). */
function ownProgressOf(item: ContinueWatchingItem): { positionSeconds: number; durationSeconds: number } | null {
  const { positionSeconds, durationSeconds } = item;
  return positionSeconds === null || durationSeconds === null ? null : { positionSeconds, durationSeconds };
}

/**
 * Línea de datos bajo el título: «Quedan 34 min» en una película, «T1:E2 ·
 * Quedan 34 min» en un episodio y «T1:E3 · Siguiente episodio» en el siguiente
 * a uno terminado que aún no se ha empezado (si ya se había empezado, lo que
 * queda, como cualquier otro: es lo que el usuario necesita saber).
 */
function metaOf(item: ContinueWatchingItem): string {
  const progress = ownProgressOf(item);
  const detail = progress ? formatRemaining(progress.positionSeconds, progress.durationSeconds) : 'Siguiente episodio';
  if (item.type === 'EPISODE' && item.seasonNumber !== null && item.episodeNumber !== null) {
    return `${episodeCode(item.seasonNumber, item.episodeNumber)} · ${detail}`;
  }
  return detail;
}

/**
 * Verbo con el que empieza el nombre del enlace: «Reanudar» solo si el
 * reproductor va a empezar donde se dejó (la misma regla que «Reanudar» en la
 * página de cada título, {@link isResumablePosition}); si no —el siguiente
 * episodio sin empezar, o algo que apenas se había empezado—, «Ver».
 */
function verbOf(item: ContinueWatchingItem): 'Reanudar' | 'Ver' {
  const progress = ownProgressOf(item);
  return progress && isResumablePosition(progress.positionSeconds) ? 'Reanudar' : 'Ver';
}

/** Clases del botón «Quitar»: visible siempre en pantallas táctiles; con ratón, al pasar por la tarjeta o con el foco. */
const removeButtonClass =
  'focus-ring absolute top-2 right-2 inline-flex size-11 items-center justify-center rounded-full bg-black/75 text-white ' +
  'ring-1 ring-white/25 transition-[opacity,background-color,scale] duration-150 ease-out-strong hover:bg-black ' +
  'active:scale-95 motion-reduce:active:scale-100 pointer-fine:size-9 pointer-fine:opacity-0 ' +
  'pointer-fine:group-hover:opacity-100 pointer-fine:group-focus-within:opacity-100';

/** Propiedades de {@link ContinueWatchingCard}. */
interface CardProps {
  item: ContinueWatchingItem;
  onRemove: (item: ContinueWatchingItem) => void;
}

/**
 * Tarjeta de «Seguir viendo»: la portada con la barra de lo visto justo debajo,
 * el título (la película o la SERIE: es lo que se reconoce) y «T1:E2 · Quedan
 * 34 min».
 *
 * - **Pulsar lleva al reproductor**, no a la página del título: quien pulsa
 *   aquí quiere seguir viendo. Al pasar el ratón aparece un ▶ sobre la
 *   portada que lo anuncia; el nombre del enlace empieza por «Reanudar»
 *   («Reanudar Dark T1:E2 · Quedan 34 min»).
 * - **Siguiente episodio** (`upNext`: el usuario terminó el último que vio).
 *   Si aún no lo ha empezado, dice «T1:E3 · Siguiente episodio», el enlace se
 *   llama «Ver Dark T1:E3 · Siguiente episodio» (no «Reanudar»: empezará
 *   desde el principio) y la barra va VACÍA: es verdad (no ha visto nada de
 *   ese episodio) y todas las tarjetas de la fila conservan la misma altura y
 *   el texto a la misma línea. Si ya lo había empezado, es una tarjeta normal.
 *   La página de la serie propone el mismo episodio (`seriesWatchTarget`).
 * - **«Quitar de Seguir viendo»** (✕ en la esquina): un botón HERMANO del
 *   enlace, no dentro de él (un control dentro de un enlace no se puede
 *   pulsar por separado ni con teclado ni con un lector de pantalla). En
 *   pantallas táctiles se ve siempre (no hay «pasar el ratón»); con ratón
 *   aparece al pasar por la tarjeta y con el foco del teclado, para no llenar
 *   la fila de cruces. Mide 44 px en táctil y 36 px con ratón.
 * - La barra va dentro del enlace y oculta a los lectores de pantalla: lo que
 *   dice ya está escrito en el texto («Quedan 34 min»), y un `progressbar`
 *   dentro de un enlace se colaría en su nombre.
 *
 * Mismo tamaño, póster y efectos que `PosterCard`, para que la fila se lea
 * igual que las demás de la portada.
 */
function ContinueWatchingCard({ item, onRemove }: CardProps) {
  const name = continueWatchingName(item);
  const progress = ownProgressOf(item);
  return (
    <div className="group relative w-36 shrink-0 sm:w-44">
      <Link
        to={playerPathOf(item)}
        className="focus-ring block rounded-xl text-left transition-[scale] duration-150 ease-out-strong active:scale-98 motion-reduce:active:scale-100"
      >
        <div className="relative overflow-hidden rounded-xl">
          <MoviePoster
            title={name}
            src={item.imageUrl}
            className="aspect-2/3 w-full"
            imgClassName="transition-[scale] duration-200 ease-out-strong group-hover:scale-105 motion-reduce:group-hover:scale-100"
          />
          <div
            aria-hidden="true"
            className="absolute inset-0 rounded-xl ring-1 ring-white/10 transition-[box-shadow] duration-200 ring-inset group-hover:ring-white/30"
          />
          <div
            aria-hidden="true"
            className="absolute inset-0 hidden items-center justify-center opacity-0 transition-opacity duration-150 pointer-fine:flex pointer-fine:group-hover:opacity-100"
          >
            <span className="inline-flex size-12 items-center justify-center rounded-full bg-black/70 ring-1 ring-white/30">
              <Play className="size-5 translate-x-px fill-current" />
            </span>
          </div>
        </div>
        <WatchProgressBar
          positionSeconds={progress?.positionSeconds ?? 0}
          durationSeconds={progress?.durationSeconds ?? 0}
          withText={false}
          className="mt-2 mb-2"
        />
        <div className="px-0.5">
          <p className="truncate text-sm font-medium text-white/90 transition-colors group-hover:text-white">
            {/* El espacio va fuera del <span> oculto: dentro se perdería al calcular el nombre («ReanudarDark»). */}
            <span className="sr-only">{verbOf(item)}</span> {name}
          </p>
          <p className="text-xs text-muted tabular-nums">{metaOf(item)}</p>
        </div>
      </Link>
      <button
        type="button"
        onClick={() => onRemove(item)}
        aria-label={`Quitar ${name} de Seguir viendo`}
        className={removeButtonClass}
      >
        <X aria-hidden="true" className="size-4" />
      </button>
    </div>
  );
}

/**
 * Fila **«Seguir viendo»** de la portada: lo que el usuario tiene a medias, lo
 * más reciente primero (`GET /api/users/me/continue-watching`). El servidor ya
 * quita las películas terminadas (≥ 95 %) y lo que ya no tiene vídeo, y da una
 * sola tarjeta por serie: su último episodio, o el siguiente si lo terminó
 * (`upNext`; sin siguiente, la serie no sale).
 *
 * - **Solo si hay algo.** Sin elementos, o mientras cargan, no pinta nada: no
 *   tiene estado vacío propio («No tienes nada a medias» no ayuda a nadie) ni
 *   esqueleto (para quien no tiene nada a medias, un hueco que aparece y
 *   desaparece). Para que no empuje las demás filas al llegar, la portada la pide
 *   en paralelo con el catálogo (`usePrefetchContinueWatching`).
 * - **Error**: el aviso de fila de siempre ({@link RowError}) con «Reintentar».
 * - **Quitar** ({@link useRemoveFromContinueWatching}): la tarjeta desaparece
 *   al pulsar y un aviso lo confirma (o la devuelve si falla). El foco, que
 *   estaba en la ✕ que desaparece, pasa a la tarjeta que ocupa su sitio (o a la
 *   anterior, si era la última); si la fila se queda vacía y desaparece, al
 *   contenido principal, nunca a `<body>`.
 *
 * Igual para USER y ADMIN: es el progreso de cada uno.
 */
export function ContinueWatchingRow() {
  const { items, status, errorMessage, reload } = useContinueWatching();
  const remove = useRemoveFromContinueWatching();
  const rowRef = useRef<HTMLDivElement>(null);
  /** Posición de la tarjeta quitada, para mover el foco cuando ya no esté. */
  const focusAfterRemoval = useRef<number | null>(null);

  useLayoutEffect(() => {
    const index = focusAfterRemoval.current;
    if (index === null) return;
    focusAfterRemoval.current = null;
    const links = rowRef.current?.querySelectorAll<HTMLAnchorElement>('li a[href]') ?? [];
    const target = links[Math.min(index, links.length - 1)];
    if (target) target.focus();
    else document.getElementById(MAIN_CONTENT_ID)?.focus({ preventScroll: true });
  }, [items]);

  if (status === 'error') {
    return (
      <RowError
        title={ROW_TITLE}
        summary="No se pudo cargar lo que tienes a medias."
        message={errorMessage}
        onRetry={reload}
        retryContext="la carga de Seguir viendo"
      />
    );
  }
  if (items.length === 0) return null;

  const handleRemove = (item: ContinueWatchingItem) => {
    focusAfterRemoval.current = items.findIndex((other) => itemKey(other) === itemKey(item));
    void remove(item);
  };

  return (
    <div ref={rowRef}>
      <PosterRow
        title={ROW_TITLE}
        items={items}
        itemKey={itemKey}
        renderItem={(item) => <ContinueWatchingCard item={item} onRemove={handleRemove} />}
      />
    </div>
  );
}
