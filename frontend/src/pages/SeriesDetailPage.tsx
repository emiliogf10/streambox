import { useId } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import { Pencil, SearchX } from 'lucide-react';
import { BackLink } from '../components/BackLink';
import {
  DETAIL_CONTAINER_CLASS,
  SERIES_BODY_GRID_CLASS,
  SERIES_FACTS_CLASS,
  SERIES_FACTS_LIST_CLASS,
} from '../components/bannerGridStyles';
import { buttonClasses } from '../components/buttonStyles';
import { DetailSkeleton } from '../components/CatalogSkeleton';
import { EmptyState } from '../components/EmptyState';
import { EpisodeList } from '../components/EpisodeList';
import { ErrorState } from '../components/ErrorState';
import { FactSheet } from '../components/FactSheet';
import type { Fact } from '../components/FactSheet';
import { FavoriteButton } from '../components/FavoriteButton';
import { FeaturedBanner } from '../components/FeaturedBanner';
import { GenreLinks } from '../components/GenreLinks';
import { SeasonPicker } from '../components/SeasonPicker';
import { SeriesMetaTags } from '../components/SeriesMetaTags';
import { WatchButton } from '../components/WatchButton';
import { useAuth } from '../context/AuthContext';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useSeriesDetail } from '../hooks/useSeriesDetail';
import { formatAddedDate, seriesPath } from '../lib/catalog';
import {
  episodeCode,
  formatEpisodeCount,
  formatSeriesYears,
  isOnAir,
  resolveSeasonNumber,
  SEASON_PARAM,
} from '../lib/series';
import type { SeriesDetail } from '../lib/types';
import { ADMIN_SERIES_PATH, adminEditSeriesPath } from './admin/adminPaths';
import type { ReturnToState } from './admin/adminPaths';

/**
 * Página de una serie (`/series/:id`), con la temporada elegida en la URL (`?temporada=2`).
 *
 * Como la de cada película (`/peliculas/:id`), es una página con dirección
 * propia: cada serie, y cada temporada, se puede compartir, guardar en
 * marcadores o recargar, y «Atrás» vuelve a donde se estaba.
 *
 * Estados (siempre con un único `<h1>`):
 * - **Cargando**: `<h1>` oculto «Serie» + la silueta de la página (`DetailSkeleton`).
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
    <div className="pb-12">
      <h1 className="sr-only">Serie</h1>
      {status === 'error' ? (
        <div className="px-4 py-6 sm:px-6">
          <ErrorState title="No se pudo cargar la serie" message={errorMessage} onRetry={reload} />
        </div>
      ) : (
        <DetailSkeleton label="Cargando serie..." kind="series" />
      )}
    </div>
  );
}

/**
 * Contenido de una serie ya cargada: cabecera, episodios de la temporada elegida y ficha.
 *
 * - **Cabecera**: el mismo {@link FeaturedBanner} del banner de `/series`, aquí
 *   con el título como `<h1>`, la sinopsis entera (no hay otro sitio donde
 *   leerla) y el contenido centrado (`layout="page"`), igual que la página de
 *   una película. Encima, «Volver a series» ({@link BackLink}), gemelo de
 *   «Volver a películas». Acciones: «Empezar a ver» (primer episodio de la
 *   primera temporada), «Mi lista» y, solo para un administrador (rol
 *   confirmado por el servidor; mientras carga el usuario no aparece),
 *   **«Editar»**: lleva al formulario del panel, con el nombre accesible
 *   «Editar Dark» como en el listado del panel, y pasa esta página como vuelta
 *   (`returnTo`), así que el formulario ofrece «Volver a la serie» en lugar de
 *   «Volver al listado». Es interfaz, no seguridad: el backend responde 403 a
 *   un `USER` que intente editar.
 * - **Cuerpo** (`SERIES_BODY_GRID_CLASS`), en el mismo contenedor centrado: los
 *   episodios y la {@link FactSheet ficha}; desde `lg`, la ficha va en una
 *   columna lateral, y en móvil y tableta, debajo de los episodios (que son lo
 *   que se viene a buscar).
 * - **Temporada**: sale de `?temporada=` y se valida con `resolveSeasonNumber`
 *   (si falta, no es un número o no existe → la primera). El selector
 *   ({@link SeasonPicker}) solo aparece con dos o más temporadas.
 * - **«Temporada 2 · 8 episodios»** es una región `role="status"`: al cambiar de
 *   temporada, el foco se queda en el selector y un lector de pantalla oye que
 *   la lista ha cambiado sin tener que ir a buscarla.
 */
function SeriesDetailView({ series }: { series: SeriesDetail }) {
  const { isAdmin } = useAuth();
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
        layout="page"
        backLink={<BackLink to="/series">Volver a series</BackLink>}
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
            {isAdmin && (
              <Link
                to={adminEditSeriesPath(series.id)}
                state={{ returnTo: seriesPath(series.id) } satisfies ReturnToState}
                aria-label={`Editar ${series.title}`}
                className={buttonClasses('ghost', 'col-span-2 sm:col-span-1')}
              >
                <Pencil aria-hidden="true" className="size-4" />
                Editar
              </Link>
            )}
          </>
        }
      />

      <div className={`${DETAIL_CONTAINER_CLASS} ${SERIES_BODY_GRID_CLASS} px-4 sm:px-6`}>
        <section aria-labelledby={episodesHeadingId} className="min-w-0">
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

        <FactSheet facts={seriesFacts(series)} className={SERIES_FACTS_CLASS} listClassName={SERIES_FACTS_LIST_CLASS} />
      </div>
    </div>
  );
}

/**
 * Datos de la ficha de una serie. «Emisión» dice «Desde 2021» si no tiene año
 * de fin (la raya sola de `2021–` se entiende a medias) y el rango «2021–2027»
 * si lo tiene, aunque ese año aún no haya llegado. «Estado» lo dice con
 * palabras según {@link isOnAir}: con un año de fin futuro la serie sigue «En
 * emisión», y solo es «Finalizada» si ese año es el actual o uno pasado. Los géneros son enlaces a `/series`
 * filtrada por cada uno ({@link GenreLinks}, como los de una película): llevan
 * a series, no a películas, porque es lo que se está explorando. Géneros y
 * fecha de alta solo si los hay.
 */
function seriesFacts(series: SeriesDetail): Fact[] {
  const onAir = isOnAir(series);
  const facts: Fact[] = [
    {
      term: 'Emisión',
      detail: series.endYear === null ? `Desde ${series.releaseYear}` : formatSeriesYears(series.releaseYear, series.endYear),
    },
    { term: 'Estado', detail: onAir ? 'En emisión' : 'Finalizada' },
    { term: 'Temporadas', detail: series.seasonCount },
    { term: 'Episodios', detail: series.episodeCount },
  ];
  if (series.genres.length > 0) {
    facts.push({ term: 'Géneros', detail: <GenreLinks kind="series" genres={series.genres} />, wide: true });
  }
  const added = formatAddedDate(series.createdAt);
  if (added) facts.push({ term: 'En el catálogo desde', detail: added, wide: true });
  return facts;
}
