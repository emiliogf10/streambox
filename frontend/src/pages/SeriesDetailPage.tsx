import { useId } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { SearchX } from 'lucide-react';
import { buttonClasses } from '../components/buttonStyles';
import { EmptyState } from '../components/EmptyState';
import { EpisodeList } from '../components/EpisodeList';
import { ErrorState } from '../components/ErrorState';
import { FavoriteButton } from '../components/FavoriteButton';
import { FeaturedBanner } from '../components/FeaturedBanner';
import { LoadingState } from '../components/LoadingState';
import { SeasonPicker } from '../components/SeasonPicker';
import { SeriesMetaTags } from '../components/SeriesMetaTags';
import { WatchButton } from '../components/WatchButton';
import { useAuth } from '../context/AuthContext';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useSeriesDetail } from '../hooks/useSeriesDetail';
import { episodeCode, formatEpisodeCount, resolveSeasonNumber, SEASON_PARAM } from '../lib/series';
import type { SeriesDetail } from '../lib/types';
import { ADMIN_SERIES_PATH } from './admin/adminPaths';

/**
 * Página de una serie (`/series/:id`), con la temporada elegida en la URL (`?temporada=2`).
 *
 * Es una página y no un diálogo como el de las películas: tiene temporadas y
 * episodios (demasiado para un modal), y así cada serie y temporada tiene su
 * propia dirección que se puede compartir.
 *
 * Estados (siempre con un único `<h1>`):
 * - **Cargando**: `<h1>` oculto «Serie» + indicador.
 * - **No encontrada** (404, o id mal formado en la URL): `<h1>` «Serie no
 *   encontrada» y un enlace a `/series`. Sin «Reintentar»: reintentar no la
 *   haría aparecer. La API da 404 también si la serie existe pero no tiene
 *   episodios, para no revelar su existencia. A un **administrador** eso no le
 *   basta: puede llegar aquí con la dirección de una serie suya aún sin
 *   episodios, así que su texto nombra esa posibilidad y su botón lleva a
 *   «Gestionar series» (donde sí está, marcada como oculta). Mientras carga el
 *   usuario, falla cerrado (texto de usuario). El texto no confirma que exista:
 *   la API tampoco se lo dice a esta página.
 * - **Error** (red, 5xx...): `<h1>` oculto + error con «Reintentar».
 * - **Lista**: ver {@link SeriesDetailView}.
 */
export function SeriesDetailPage() {
  const { id } = useParams();
  const { status, series, errorMessage, reload } = useSeriesDetail(id);
  const { isAdmin } = useAuth();

  useDocumentTitle(series?.title ?? (status === 'not-found' ? 'Serie no encontrada' : 'Series'));

  if (status === 'ready' && series) return <SeriesDetailView series={series} />;

  if (status === 'not-found') {
    return (
      <div className="px-4 py-6 sm:px-6">
        {isAdmin ? (
          <EmptyState
            titleAs="h1"
            icon={<SearchX className="size-8" />}
            title="Serie no encontrada"
            description="Puede que la dirección no sea correcta, que la serie se haya borrado o que todavía no tenga episodios: las series sin episodios solo se ven en el panel de administración."
            action={
              <Link to={ADMIN_SERIES_PATH} className={buttonClasses('light')}>
                Gestionar series
              </Link>
            }
          />
        ) : (
          <EmptyState
            titleAs="h1"
            icon={<SearchX className="size-8" />}
            title="Serie no encontrada"
            description="Puede que la dirección no sea correcta o que la serie ya no esté disponible."
            action={
              <Link to="/series" className={buttonClasses('light')}>
                Ver todas las series
              </Link>
            }
          />
        )}
      </div>
    );
  }

  return (
    <div className="px-4 py-6 sm:px-6">
      <h1 className="sr-only">Serie</h1>
      {status === 'error' ? (
        <ErrorState title="No se pudo cargar la serie" message={errorMessage} onRetry={reload} />
      ) : (
        <LoadingState label="Cargando serie..." />
      )}
    </div>
  );
}

/**
 * Contenido de una serie ya cargada: cabecera y episodios de la temporada elegida.
 *
 * - **Cabecera**: el mismo {@link FeaturedBanner} del banner de `/series`, aquí
 *   con el título como `<h1>` y la sinopsis entera (no hay otro sitio donde
 *   leerla). Acciones: «Empezar a ver» (primer episodio de la primera
 *   temporada) y «Mi lista».
 * - **Temporada**: sale de `?temporada=` y se valida con `resolveSeasonNumber`
 *   (si falta, no es un número o no existe → la primera). El selector
 *   ({@link SeasonPicker}) solo aparece con dos o más temporadas.
 * - **«Temporada 2 · 8 episodios»** es una región `role="status"`: al cambiar de
 *   temporada, el foco se queda en el selector y un lector de pantalla oye que
 *   la lista ha cambiado sin tener que ir a buscarla.
 */
function SeriesDetailView({ series }: { series: SeriesDetail }) {
  const [searchParams] = useSearchParams();
  const episodesHeadingId = useId();
  const seasonNumber = resolveSeasonNumber(searchParams.get(SEASON_PARAM), series.seasons);
  const season = series.seasons.find((item) => item.seasonNumber === seasonNumber);
  const firstEpisode = series.seasons[0]?.episodes[0];

  return (
    <div className="pb-12">
      <FeaturedBanner
        title={series.title}
        imageUrl={series.imageUrl}
        headingLevel={1}
        meta={<SeriesMetaTags series={series} />}
        description={series.description}
        clampDescription={false}
        actions={
          <>
            {firstEpisode && (
              <WatchButton
                videoUrl={firstEpisode.videoUrl}
                title={firstEpisode.title}
                label="Empezar a ver"
                accessibleName={`Empezar a ver ${series.title}: ${episodeCode(firstEpisode.seasonNumber, firstEpisode.episodeNumber)} ${firstEpisode.title}`}
                unavailableSubject="este episodio"
              />
            )}
            <FavoriteButton series={series} />
          </>
        }
      />

      <section aria-labelledby={episodesHeadingId} className="px-4 sm:px-6">
        <div className="mb-4 flex flex-col gap-3">
          <h2 id={episodesHeadingId} className="text-lg font-bold tracking-tight text-white">
            Episodios
          </h2>
          {series.seasons.length > 1 && seasonNumber !== null && (
            <SeasonPicker seasons={series.seasons} current={seasonNumber} />
          )}
        </div>

        {season ? (
          <>
            <p role="status" className="mb-1 text-sm text-muted">
              Temporada {season.seasonNumber} · {formatEpisodeCount(season.episodes.length)}
            </p>
            <EpisodeList season={season} />
          </>
        ) : (
          // No debería ocurrir (la API da 404 a las series sin episodios), pero si pasa se dice, no se deja en blanco.
          <p className="text-sm text-muted">Esta serie todavía no tiene episodios.</p>
        )}
      </section>
    </div>
  );
}
