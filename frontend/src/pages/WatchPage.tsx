import { useState } from 'react';
import type { ReactNode } from 'react';
import { ExternalLink, Pencil, SearchX } from 'lucide-react';
import { Link, useParams } from 'react-router-dom';
import { buttonClasses } from '../components/buttonStyles';
import { EmptyState } from '../components/EmptyState';
import { ErrorState } from '../components/ErrorState';
import { LoadingState } from '../components/LoadingState';
import { PlayerScreen } from '../components/player/PlayerScreen';
import { PlayerShell } from '../components/player/PlayerShell';
import { WatchButton } from '../components/WatchButton';
import { useAuth } from '../context/AuthContext';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useMovieDetail } from '../hooks/useMovieDetail';
import { useSeriesDetail } from '../hooks/useSeriesDetail';
import { useEpisodeProgress, useMovieProgress } from '../hooks/useWatchProgress';
import type { TitleProgressValue } from '../hooks/useWatchProgress';
import { moviePath, seriesPath } from '../lib/catalog';
import { episodeLabel, episodePlayerPath, getDirectVideoUrl, getSubtitlesUrl, resumePositionOf } from '../lib/playback';
import type { Episode, SeriesDetail } from '../lib/types';
import { getSafeVideoUrl, parseRouteId } from '../lib/utils';
import { adminEditMoviePath, adminEditSeriesPath } from './admin/adminPaths';

/** Desde dónde empieza el reproductor de un título, decidido una sola vez (ver {@link usePlayerStart}). */
interface PlayerStart {
  /** Título al que se refiere («movie:7», «episode:41»): al cambiar de título se decide otra vez. */
  titleKey: string;
  /** Segundo desde el que empezar (`resumePositionOf`). */
  resumeAt: number;
  /** No se pudo leer el progreso: empieza desde el principio y no se guarda (ver `PlayerScreen`). */
  progressUnavailable: boolean;
}

/**
 * Decide UNA vez por título, en cuanto se sabe su progreso (o se sabe que no se
 * puede saber), desde dónde empieza el reproductor y si guarda el progreso; y
 * mantiene esa decisión mientras se vea ese título, cambie lo que cambie la consulta.
 *
 * **Por qué hace falta.** El progreso es una consulta de TanStack Query, que se
 * repite sola (al volver a la pestaña, al recuperar la red) y se reescribe con
 * cada guardado. Si el reproductor dependiera de ella en directo:
 * - tras un fallo, la repetición la pondría otra vez «cargando» y la página
 *   DESMONTARÍA el vídeo a mitad de reproducción (vuelve a «Preparando el vídeo...»);
 * - si la repetición acertara, `progressUnavailable` pasaría a `false` y el
 *   reproductor empezaría a guardar encima del progreso bueno que nunca llegó a
 *   usar (justo lo que evita la incidencia QA-3), y el aviso del pie
 *   desaparecería sin que hubiera cambiado nada en pantalla.
 *
 * Se guarda en estado, actualizado durante el render (el patrón de React para
 * «ajustar el estado cuando cambia una prop»): así el reproductor se monta en
 * el mismo render en que llega el progreso, sin un fotograma de más.
 *
 * @param titleKey título que se va a ver, o `null` mientras no hay uno con vídeo que se vea aquí
 * @param progress progreso de ese título (`useMovieProgress`/`useEpisodeProgress`)
 * @returns la decisión, o `null` mientras el progreso carga
 */
function usePlayerStart(titleKey: string | null, progress: TitleProgressValue): PlayerStart | null {
  const [start, setStart] = useState<PlayerStart | null>(null);
  if (titleKey === null) return null;
  if (start?.titleKey === titleKey) return start;
  if (progress.status === 'loading') return null;
  const decided: PlayerStart = {
    titleKey,
    resumeAt: resumePositionOf(progress.progress),
    progressUnavailable: progress.status === 'error',
  };
  setStart(decided);
  return decided;
}

/** `<h1>` de la cabecera en los estados sin vídeo (cargando, error, no encontrado...). */
function ShellHeading({ children, visuallyHidden = false }: { children: ReactNode; visuallyHidden?: boolean }) {
  return <h1 className={visuallyHidden ? 'sr-only' : 'truncate text-base font-bold tracking-tight sm:text-lg'}>{children}</h1>;
}

/** Bloque centrado para los mensajes de la pantalla de reproducción (sin vídeo que enseñar). */
function ShellMessage({ children }: { children: ReactNode }) {
  return <div className="w-full max-w-2xl px-4 py-8">{children}</div>;
}

/** Propiedades de {@link NoPlayableVideo}. */
interface NoPlayableVideoProps {
  /** Título de la película o del episodio («Interstellar», «Dark: T1:E2 · Mentiras»). */
  name: string;
  /** `videoUrl` tal como viene de la API. */
  videoUrl: string | null;
  titlePath: string;
  titleLinkLabel: string;
  editPath: string;
}

/**
 * Lo que dice la pantalla de reproducción cuando el título no tiene un vídeo que
 * se vea aquí. A ella solo se llega con la dirección escrita o guardada (los
 * botones ya llevan a cada vídeo a su sitio), pero no debe quedarse en blanco
 * ni sin salida:
 * - **Vídeo que no es un archivo directo** (una página de YouTube, Vimeo...):
 *   se dice que se ve fuera de StreamBox y se ofrece abrirlo en otra pestaña.
 * - **Sin vídeo** (o con uno inseguro, que cuenta como ninguno): «Todavía no
 *   tiene vídeo»; a un administrador, además, «Editar» para añadirlo.
 * Siempre con «Volver a…» a la página del título.
 */
function NoPlayableVideo({ name, videoUrl, titlePath, titleLinkLabel, editPath }: NoPlayableVideoProps) {
  const { isAdmin } = useAuth();
  const external = getSafeVideoUrl(videoUrl) !== null;
  const back = (
    <Link to={titlePath} className={buttonClasses(external ? 'outline' : 'light')}>
      {titleLinkLabel}
    </Link>
  );
  return (
    <ShellMessage>
      <EmptyState
        icon={external ? <ExternalLink className="size-8" /> : <SearchX className="size-8" />}
        title={external ? 'Este vídeo se ve fuera de StreamBox' : 'Todavía no hay vídeo'}
        description={
          external
            ? `El vídeo de «${name}» está en otra web y no se puede reproducir aquí. Ábrelo en una pestaña nueva (por dónde vas no se guardará).`
            : isAdmin
              ? `«${name}» todavía no tiene vídeo. Puedes añadirlo desde «Editar».`
              : `«${name}» todavía no está disponible para ver.`
        }
        action={
          <div className="flex flex-wrap justify-center gap-3">
            {external ? <WatchButton videoUrl={videoUrl} title={name} label="Abrir el vídeo" /> : null}
            {back}
            {!external && isAdmin && (
              <Link to={editPath} aria-label={`Editar ${name}`} className={buttonClasses('ghost')}>
                <Pencil aria-hidden="true" className="size-4" />
                Editar
              </Link>
            )}
          </div>
        }
      />
    </ShellMessage>
  );
}

/**
 * Reproductor de una película (`/ver/peliculas/:id`).
 *
 * Espera a tener la película y su progreso (`useMovieProgress`) antes de montar
 * el vídeo, porque de él depende desde dónde empieza. Si llega desde su página,
 * los dos ya están en la caché y la espera no se ve. Si el progreso falla, no
 * se bloquea el vídeo: empieza desde el principio, no guarda (para no machacar
 * lo que hubiera guardado) y el pie lo dice. Esa decisión se toma una sola vez
 * ({@link usePlayerStart}): una repetición posterior de la consulta no desmonta
 * el vídeo ni cambia lo decidido.
 *
 * Estados, todos con «Volver» y Escape (ver `PlayerShell`) y un único `<h1>`:
 * cargando, película no encontrada (con «Ver todas las películas»), error al
 * cargarla (con «Reintentar»), sin vídeo que se vea aquí ({@link NoPlayableVideo})
 * y el reproductor ({@link PlayerScreen}).
 */
export function WatchMoviePage() {
  const { id } = useParams();
  const movieId = parseRouteId(id);
  const { status, movie, errorMessage, reload } = useMovieDetail(id);
  const directUrl = getDirectVideoUrl(movie?.videoUrl);
  const progress = useMovieProgress(movieId, status === 'ready' && directUrl !== null);
  const start = usePlayerStart(status === 'ready' && movie && directUrl ? `movie:${movie.id}` : null, progress);

  useDocumentTitle(movie ? movie.title : status === 'not-found' ? 'Película no encontrada' : 'Reproductor');

  const fallbackPath = movieId === null ? '/peliculas' : moviePath(movieId);

  if (movie && directUrl && start) {
    return (
      <PlayerScreen
        key={movie.id}
        title={movie.title}
        videoUrl={directUrl}
        subtitlesUrl={getSubtitlesUrl(movie.subtitlesUrl)}
        posterUrl={movie.imageUrl}
        resumeAt={start.resumeAt}
        progressUnavailable={start.progressUnavailable}
        target={{ kind: 'movie', id: movie.id }}
        titlePath={moviePath(movie.id)}
        titleLinkLabel={`Volver a ${movie.title}`}
        editPath={adminEditMoviePath(movie.id)}
        endedText={`Has terminado «${movie.title}».`}
      />
    );
  }

  let heading: ReactNode;
  let body: ReactNode;
  if (status === 'not-found') {
    heading = null;
    body = (
      <ShellMessage>
        <EmptyState
          titleAs="h1"
          icon={<SearchX className="size-8" />}
          title="Película no encontrada"
          description="Puede que la dirección no sea correcta o que la película se haya retirado del catálogo."
          action={
            <Link to="/peliculas" className={buttonClasses('light')}>
              Ver todas las películas
            </Link>
          }
        />
      </ShellMessage>
    );
  } else if (status === 'error') {
    heading = <ShellHeading visuallyHidden>Reproductor</ShellHeading>;
    body = (
      <ShellMessage>
        <ErrorState title="No se pudo cargar la película" message={errorMessage} onRetry={reload} />
      </ShellMessage>
    );
  } else if (status === 'ready' && movie && !directUrl) {
    heading = <ShellHeading>{movie.title}</ShellHeading>;
    body = (
      <NoPlayableVideo
        name={movie.title}
        videoUrl={movie.videoUrl}
        titlePath={moviePath(movie.id)}
        titleLinkLabel={`Volver a ${movie.title}`}
        editPath={adminEditMoviePath(movie.id)}
      />
    );
  } else {
    heading = movie ? <ShellHeading>{movie.title}</ShellHeading> : <ShellHeading visuallyHidden>Reproductor</ShellHeading>;
    body = <LoadingState label="Preparando el vídeo..." />;
  }
  return (
    <PlayerShell fallbackPath={fallbackPath} heading={heading}>
      {body}
    </PlayerShell>
  );
}

/** Busca un episodio dentro del detalle de su serie (la API no tiene un `GET` de un episodio suelto). */
function findEpisode(series: SeriesDetail | null, episodeId: number | null): { episode: Episode; next: Episode | null } | null {
  if (!series || episodeId === null) return null;
  const all = series.seasons.flatMap((season) => season.episodes);
  const index = all.findIndex((episode) => episode.id === episodeId);
  if (index === -1) return null;
  const next = all.slice(index + 1).find((episode) => getSafeVideoUrl(episode.videoUrl) !== null) ?? null;
  return { episode: all[index], next };
}

/**
 * Reproductor de un episodio (`/ver/series/:seriesId/episodios/:episodeId`).
 *
 * Igual que {@link WatchMoviePage}, con lo propio de una serie:
 * - la cabecera dice la serie (`<h1>`) y, debajo, «T1:E2 · Mentiras»;
 * - el episodio se busca en el detalle de la serie: si la serie no existe (o no
 *   tiene episodios, que para la API es lo mismo) o el episodio no es suyo,
 *   «Episodio no encontrado» con un enlace a la serie o a `/series`;
 * - al terminar ofrece **el siguiente episodio** (el primero posterior con vídeo,
 *   aunque sea de otra temporada): en el reproductor si es un archivo directo,
 *   en otra pestaña si no. Sin siguiente, el pie dice que era el último. El
 *   siguiente SUSTITUYE al actual en el historial: «Volver» lleva a donde se
 *   estaba antes de empezar a ver (la serie), no al episodio ya terminado.
 */
export function WatchEpisodePage() {
  const { seriesId: rawSeriesId, episodeId: rawEpisodeId } = useParams();
  const seriesId = parseRouteId(rawSeriesId);
  const episodeId = parseRouteId(rawEpisodeId);
  const { status, series, errorMessage, reload } = useSeriesDetail(rawSeriesId);
  const found = findEpisode(series, episodeId);
  const episode = found?.episode ?? null;
  const directUrl = getDirectVideoUrl(episode?.videoUrl);
  const progress = useEpisodeProgress(episodeId, status === 'ready' && directUrl !== null);
  const start = usePlayerStart(status === 'ready' && series && episode && directUrl ? `episode:${episode.id}` : null, progress);
  const notFound = status === 'not-found' || (status === 'ready' && !episode);

  useDocumentTitle(
    series && episode ? `${series.title}: ${episodeLabel(episode)}` : notFound ? 'Episodio no encontrado' : 'Reproductor',
  );

  const fallbackPath = seriesId === null ? '/series' : seriesPath(seriesId);

  if (series && episode && directUrl && start) {
    const next = found?.next ?? null;
    const nextAction = next && (
      <WatchButton
        videoUrl={next.videoUrl}
        title={next.title}
        label={`Ver el siguiente: ${episodeLabel(next)}`}
        accessibleName={`Ver el siguiente: ${episodeLabel(next)}`}
        playerPath={episodePlayerPath(series.id, next.id)}
        replace
      />
    );
    return (
      <PlayerScreen
        key={episode.id}
        title={series.title}
        subtitle={episodeLabel(episode)}
        videoUrl={directUrl}
        subtitlesUrl={getSubtitlesUrl(episode.subtitlesUrl)}
        posterUrl={series.imageUrl}
        resumeAt={start.resumeAt}
        progressUnavailable={start.progressUnavailable}
        target={{ kind: 'episode', id: episode.id, seriesId: series.id }}
        titlePath={seriesPath(series.id)}
        titleLinkLabel={`Volver a ${series.title}`}
        editPath={adminEditSeriesPath(series.id)}
        nextAction={nextAction}
        endedText={next ? `Has terminado ${episodeLabel(episode)}.` : `Has visto el último episodio de «${series.title}».`}
      />
    );
  }

  let heading: ReactNode;
  let body: ReactNode;
  if (notFound) {
    heading = null;
    body = (
      <ShellMessage>
        <EmptyState
          titleAs="h1"
          icon={<SearchX className="size-8" />}
          title="Episodio no encontrado"
          description={
            series
              ? `Puede que la dirección no sea correcta o que el episodio ya no esté en «${series.title}».`
              : 'Puede que la dirección no sea correcta o que la serie ya no esté disponible.'
          }
          action={
            series ? (
              <Link to={seriesPath(series.id)} className={buttonClasses('light')}>
                Ver los episodios de {series.title}
              </Link>
            ) : (
              <Link to="/series" className={buttonClasses('light')}>
                Ver todas las series
              </Link>
            )
          }
        />
      </ShellMessage>
    );
  } else if (status === 'error') {
    heading = <ShellHeading visuallyHidden>Reproductor</ShellHeading>;
    body = (
      <ShellMessage>
        <ErrorState title="No se pudo cargar el episodio" message={errorMessage} onRetry={reload} />
      </ShellMessage>
    );
  } else if (status === 'ready' && series && episode && !directUrl) {
    heading = <ShellHeading>{series.title}</ShellHeading>;
    body = (
      <NoPlayableVideo
        name={`${series.title}: ${episodeLabel(episode)}`}
        videoUrl={episode.videoUrl}
        titlePath={seriesPath(series.id)}
        titleLinkLabel={`Volver a ${series.title}`}
        editPath={adminEditSeriesPath(series.id)}
      />
    );
  } else {
    heading = series ? <ShellHeading>{series.title}</ShellHeading> : <ShellHeading visuallyHidden>Reproductor</ShellHeading>;
    body = <LoadingState label="Preparando el vídeo..." />;
  }
  return (
    <PlayerShell fallbackPath={fallbackPath} heading={heading}>
      {body}
    </PlayerShell>
  );
}
