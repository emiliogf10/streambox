import { useId } from 'react';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Check, History } from 'lucide-react';
import { useWatchHistory, WATCH_HISTORY_LIMIT } from '../../hooks/useWatchProgress';
import { formatRemaining } from '../../lib/playback';
import { pluralize } from '../../lib/series';
import type { WatchHistoryItem } from '../../lib/types';
import { watchHistoryEpisodeLine, watchHistoryName, watchHistoryPath } from '../../lib/watchHistory';
import { buttonClasses } from '../buttonStyles';
import { ErrorState } from '../ErrorState';
import { ExploreLink } from '../ExploreLink';
import { MoviePoster } from '../MoviePoster';
import { COMPACT_POSTER_GRID_CLASS } from '../posterGridStyles';
import { WatchProgressBar } from '../WatchProgressBar';

/** Clave única de una entrada: una película y un episodio pueden tener el mismo id. */
function itemKey(item: WatchHistoryItem): string {
  return `${item.type}:${item.id}`;
}

/** Clases del enlace de la tarjeta: las mismas que `PosterCard` (foco, hundirse al pulsar). */
const cardClass =
  'focus-ring group block w-full rounded-xl text-left transition-[scale] duration-150 ease-out-strong active:scale-98 motion-reduce:active:scale-100';

/**
 * Tarjeta de «Vistos recientemente»: portada, lo visto y el título, enlazada a
 * la **página del título** (la película, o la serie en la temporada del
 * episodio), no al reproductor: es historial, y para seguir viendo ya está la
 * fila «Seguir viendo» del inicio.
 *
 * - **A medias:** la barra de lo visto bajo la portada y «Quedan 34 min».
 * - **Terminado** (≥ 95 %): sin barra (su hueco se conserva, para que los títulos
 *   de la fila queden a la misma altura) y la marca «Visto» con un ✓; no se
 *   distingue solo por el color: lo dice el texto.
 * - **Visto y vuelto a empezar** (`watched` sin `finished`): la marca «Visto»
 *   (lo terminó alguna vez y eso no se pierde) y, como uno a medias, la barra
 *   y «Quedan 34 min» de la vez actual. Son las mismas dos piezas que la lista
 *   de episodios («Visto · Quedan 12 min»), en dos líneas porque la tarjeta es estrecha.
 *
 * «Visto» sale de `watched` (o de `finished`, por si acaso: lo terminado por
 * posición siempre está visto); la barra y lo que queda, de la posición.
 * - **Episodio:** el nombre es el de la SERIE (lo que se reconoce y lo que abre
 *   el enlace) y debajo «T1:E2 · Mentiras».
 *
 * La barra va dentro del enlace y oculta a los lectores de pantalla
 * (`withText={false}`): lo que dice ya está escrito («Quedan 34 min») y un
 * `progressbar` dentro de un enlace se colaría en su nombre. El nombre del enlace
 * sale del texto: «Dark T1:E2 · Mentiras Quedan 34 min», «Dune Visto» o «Dune
 * Visto Quedan 34 min».
 *
 * Mismo póster, borde y efectos que `PosterCard`, para que se lea igual que «De
 * tu lista» y «Te gusta» en el mismo perfil.
 */
function WatchHistoryCard({ item }: { item: WatchHistoryItem }) {
  const name = watchHistoryName(item);
  const episodeLine = watchHistoryEpisodeLine(item);
  const seen = item.watched || item.finished;
  return (
    <Link to={watchHistoryPath(item)} className={cardClass}>
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
      </div>
      {item.finished ? (
        <div aria-hidden="true" className="my-2 h-1" />
      ) : (
        <WatchProgressBar
          positionSeconds={item.positionSeconds}
          durationSeconds={item.durationSeconds}
          withText={false}
          className="my-2"
        />
      )}
      <div className="px-0.5">
        <p className="truncate text-sm font-medium text-white/90 transition-colors group-hover:text-white">{name}</p>
        {episodeLine && <p className="truncate text-xs text-muted tabular-nums">{episodeLine}</p>}
        {seen && (
          <p className="inline-flex items-center gap-1 text-xs font-medium text-white/90">
            <Check aria-hidden="true" className="size-3.5 text-accent" strokeWidth={3} />
            Visto
          </p>
        )}
        {!item.finished && (
          <p className="text-xs text-muted tabular-nums">{formatRemaining(item.positionSeconds, item.durationSeconds)}</p>
        )}
      </div>
    </Link>
  );
}

/** Tarjetas de la silueta de carga: una fila completa desde `md` (6) y dos en el móvil (3 + 3). */
const SKELETON_CARD_COUNT = 6;

/**
 * Silueta de la rejilla mientras llega el historial, con la MISMA rejilla y
 * medidas que las tarjetas reales. Formas decorativas; se anuncia el texto del
 * `role="status"`. El pulso se quita con `prefers-reduced-motion`.
 */
function HistorySkeleton() {
  const block = 'rounded-lg bg-white/6';
  return (
    <div role="status">
      <span className="sr-only">Cargando lo que has visto...</span>
      <ul aria-hidden="true" className={`${COMPACT_POSTER_GRID_CLASS} animate-pulse motion-reduce:animate-none`}>
        {Array.from({ length: SKELETON_CARD_COUNT }, (_, index) => (
          <li key={index}>
            <div className={`${block} aspect-2/3 w-full rounded-xl`} />
            <div className="my-2 h-1 rounded-full bg-white/6" />
            <div className={`${block} mb-1.5 h-3.5 w-4/5`} />
            <div className={`${block} h-3 w-1/2`} />
          </li>
        ))}
      </ul>
    </div>
  );
}

/**
 * Estado vacío: verdad para USER y ADMIN (los dos pueden ver títulos) y con una
 * salida que no lleva a otra página vacía.
 *
 * - Dice «en StreamBox» a propósito: solo cuenta lo que se reproduce aquí (un
 *   vídeo que se abre en otra web no guarda progreso).
 * - «Elegir una película» / «Elegir una serie» solo aparecen si al otro lado hay
 *   algo ({@link ExploreLink}); si no hay ninguno, el contenedor desaparece
 *   (`empty:hidden`) y el texto sigue siendo verdad sin ellos. No dicen
 *   «Explorar películas» para no repetir, con otro sentido, el botón del estado
 *   vacío de «Géneros que más guardas», que puede estar en la misma pantalla.
 */
function HistoryEmpty() {
  return (
    <div className="flex flex-col items-start gap-4 rounded-xl border border-dashed border-white/15 px-5 py-6 sm:flex-row sm:items-center">
      <div
        aria-hidden="true"
        className="flex size-12 shrink-0 items-center justify-center rounded-full border border-line bg-surface text-muted"
      >
        <History className="size-6" />
      </div>
      <div className="min-w-0 max-w-prose">
        <p className="font-semibold text-white">Aún no has visto nada</p>
        <p className="mt-1 text-sm leading-relaxed text-muted">
          Cuando reproduzcas una película o un episodio en StreamBox, aparecerá aquí con lo que llevas visto.
        </p>
      </div>
      <div className="flex flex-wrap gap-3 empty:hidden sm:ml-auto">
        <ExploreLink catalog="/movies" to="/peliculas" className={buttonClasses('outline')}>
          Elegir una película
        </ExploreLink>
        <ExploreLink catalog="/series" to="/series" className={buttonClasses('outline')}>
          Elegir una serie
        </ExploreLink>
      </div>
    </div>
  );
}

/**
 * Texto junto al `<h2>`. El servidor solo da las {@link WATCH_HISTORY_LIMIT} más
 * recientes y no dice cuántas hay en total, así que con la página llena no se
 * escribe un total («12 títulos» podría ser falso: quizá hay 30), sino «Los 12
 * más recientes», que es verdad en los dos casos.
 */
function summaryOf(count: number): string {
  return count < WATCH_HISTORY_LIMIT
    ? `${pluralize(count, 'título', 'títulos')} · lo más reciente primero`
    : `Los ${WATCH_HISTORY_LIMIT} más recientes`;
}

/**
 * Sección «Vistos recientemente» de `/perfil`: lo último que el usuario ha
 * visto en StreamBox (`GET /api/users/me/watch-history?limit=12`), lo más
 * reciente primero. Una tarjeta por película y una por serie (la de su último
 * episodio), terminado o no y con o sin vídeo: es historial, no «Seguir viendo».
 *
 * Misma rejilla compacta que «De tu lista» y «Te gusta»
 * (`COMPACT_POSTER_GRID_CLASS`: 3 columnas en el móvil, 6 desde `md`), para que
 * las tres secciones de títulos del perfil se lean igual; con 12, filas
 * completas en todos los anchos.
 *
 * Estados (datos de {@link useWatchHistory}, independientes del resto del perfil):
 * - **Cargando**: la silueta de la rejilla.
 * - **Error**: `ErrorState` compacto con «Reintentar» (solo vuelve a pedir esta
 *   sección); el foco se queda en el contenedor de la sección.
 * - **Vacío**: {@link HistoryEmpty}.
 * - **Con títulos**: el resumen junto al título ({@link summaryOf}) y las tarjetas.
 */
export function WatchHistorySection() {
  const { items, status, errorMessage, reload } = useWatchHistory();
  const headingId = useId();

  let body: ReactNode;
  if (status === 'error') {
    body = (
      <ErrorState
        compact
        headingLevel={3}
        title="No se pudo cargar lo que has visto"
        message={errorMessage}
        onRetry={reload}
      />
    );
  } else if (status === 'loading') {
    body = <HistorySkeleton />;
  } else if (items.length === 0) {
    body = <HistoryEmpty />;
  } else {
    body = (
      <ul role="list" className={COMPACT_POSTER_GRID_CLASS}>
        {items.map((item) => (
          <li key={itemKey(item)} className="min-w-0">
            <WatchHistoryCard item={item} />
          </li>
        ))}
      </ul>
    );
  }

  return (
    <section aria-labelledby={headingId} className="mt-10">
      <div className="mb-4 flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <h2 id={headingId} className="text-lg font-bold tracking-tight text-white">
          Vistos recientemente
        </h2>
        {status === 'ready' && items.length > 0 && <p className="text-sm text-muted">{summaryOf(items.length)}</p>}
      </div>
      {/* tabIndex -1: si «Reintentar» desaparece al pulsarlo, el foco se queda aquí (`useRetryFocus`). */}
      <div tabIndex={-1} className="focus-ring rounded-xl">
        {body}
      </div>
    </section>
  );
}
