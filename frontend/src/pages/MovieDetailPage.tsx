import { Link, useParams } from 'react-router-dom';
import { Pencil, SearchX } from 'lucide-react';
import { BackLink } from '../components/BackLink';
import { DETAIL_CONTAINER_CLASS, MOVIE_FACTS_CLASS, MOVIE_FACTS_LIST_CLASS } from '../components/bannerGridStyles';
import { buttonClasses } from '../components/buttonStyles';
import { DetailSkeleton } from '../components/CatalogSkeleton';
import { EmptyState } from '../components/EmptyState';
import { ErrorState } from '../components/ErrorState';
import { FactSheet } from '../components/FactSheet';
import type { Fact } from '../components/FactSheet';
import { FavoriteButton } from '../components/FavoriteButton';
import { FeaturedBanner } from '../components/FeaturedBanner';
import { GenreLinks } from '../components/GenreLinks';
import { MovieMetaTags } from '../components/MovieMetaTags';
import { SimilarMoviesRow } from '../components/SimilarMoviesRow';
import { WatchButton } from '../components/WatchButton';
import { useAuth } from '../context/AuthContext';
import { useDocumentTitle } from '../hooks/useDocumentTitle';
import { useMovieDetail } from '../hooks/useMovieDetail';
import { formatAddedDate, moviePath } from '../lib/catalog';
import type { Movie } from '../lib/types';
import { formatDuration } from '../lib/utils';
import { adminEditMoviePath } from './admin/adminPaths';
import type { ReturnToState } from './admin/adminPaths';

/** Texto de la sinopsis cuando la película no tiene (la página no deja el hueco en blanco). */
const NO_DESCRIPTION = 'Sin descripción disponible.';

/**
 * Página de una película (`/peliculas/:id`).
 *
 * **Por qué una página y no el diálogo de antes.** El detalle era un modal sin
 * dirección propia: no se podía compartir el enlace de una película, «Atrás» no
 * lo cerraba (salía de la pantalla entera) y al recargar se perdía. Las series
 * ya tenían su página (`/series/:id`), así que además la aplicación era
 * incoherente. Ahora todas las tarjetas, el banner, «Mi lista», el perfil y el
 * buscador enlazan aquí con un enlace de verdad (se abre en otra pestaña, se
 * copia, se guarda en marcadores).
 *
 * Estados (siempre con un único `<h1>`), como en `SeriesDetailPage`:
 * - **Cargando**: `<h1>` oculto «Película» + la silueta de la página (banner
 *   centrado y ficha, ver `DetailSkeleton`). Casi nunca se ve: si se llega desde una tarjeta,
 *   la película ya está en la caché de ese listado y se pinta al instante
 *   (ver `findCachedMovie` en `useMovieDetail`).
 * - **No encontrada** (404, o id mal formado en la URL): `<h1>` «Película no
 *   encontrada» y un enlace a `/peliculas`. Sin «Reintentar»: reintentar no la
 *   haría aparecer. El mismo texto vale para los dos roles: a diferencia de las
 *   series (que se ocultan sin episodios), una película existe o no existe, así
 *   que «se ha retirado del catálogo» es verdad también para un administrador.
 *   Si la película se borra desde el panel mientras alguien la ve, la
 *   invalidación de `movies` la vuelve a pedir y la pantalla pasa a este estado.
 * - **Error** (red, 5xx...): `<h1>` oculto + error con «Reintentar».
 * - **Lista**: ver {@link MovieDetailView}.
 */
export function MovieDetailPage() {
  const { id } = useParams();
  const { status, movie, errorMessage, confirmed, reload } = useMovieDetail(id);

  useDocumentTitle(movie?.title ?? (status === 'not-found' ? 'Película no encontrada' : 'Películas'));

  if (status === 'ready' && movie) return <MovieDetailView movie={movie} confirmed={confirmed} />;

  if (status === 'not-found') {
    return (
      <div className="px-4 py-6 sm:px-6">
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
      </div>
    );
  }

  return (
    <div className="pb-12">
      <h1 className="sr-only">Película</h1>
      {status === 'error' ? (
        <div className="px-4 py-6 sm:px-6">
          <ErrorState title="No se pudo cargar la película" message={errorMessage} onRetry={reload} />
        </div>
      ) : (
        <DetailSkeleton label="Cargando película..." kind="movie" />
      )}
    </div>
  );
}

/**
 * Contenido de una película ya cargada: el mismo {@link FeaturedBanner} de la
 * portada y de las series, aquí con el título como `<h1>`, la sinopsis entera
 * (no hay otro sitio donde leerla) y el contenido centrado (`layout="page"`),
 * y debajo su {@link FactSheet ficha}.
 *
 * **Maquetación.** Antes la página era solo el banner de la portada: en
 * escritorio, el póster y el texto quedaban en una franja a la izquierda y el
 * resto de la pantalla, a la derecha y debajo, vacío. Ahora banner y ficha
 * comparten un contenedor centrado con ancho máximo (`DETAIL_CONTAINER_CLASS`),
 * así que la composición se equilibra en cualquier ancho, de 1024 a 1920 px, y
 * en móvil y tableta todo sigue en una columna.
 *
 * Al pie, la fila **«Películas similares»** ({@link SimilarMoviesRow}), en el
 * mismo contenedor centrado: se pide solo cuando el servidor ha confirmado la
 * película (`confirmed`; no con la copia provisional de un listado) y, si no
 * hay similares, no se pinta. Un fallo suyo queda dentro de la fila.
 *
 * Encima del póster, **«Volver a películas»** ({@link BackLink}): la salida a
 * la sección cuando el historial no sirve (se entró con un enlace compartido o
 * desde la portada). Es lo primero que alcanza el tabulador en el contenido; la
 * serie tiene su gemelo «Volver a series».
 *
 * Acciones, de más a menos importante:
 * - **«Ver ahora»** ({@link WatchButton}): enlace al vídeo validado con
 *   `getSafeVideoUrl`; sin URL segura, botón desactivado con el motivo.
 * - **«Mi lista»** ({@link FavoriteButton}): la lista compartida de `FavoritesContext`.
 * - **«Editar»**, solo para un administrador (rol confirmado por el servidor;
 *   mientras carga el usuario no aparece): lleva al formulario del panel. Es
 *   interfaz, no seguridad: el backend responde 403 a un `USER` que llegue a
 *   editar. Su nombre accesible incluye el título («Editar Interstellar»), como
 *   en el listado del panel. Pasa esta página como vuelta (`returnTo`): al
 *   guardar o cancelar, el formulario vuelve aquí («Volver a la película») y
 *   no al listado del panel.
 */
function MovieDetailView({ movie, confirmed }: { movie: Movie; confirmed: boolean }) {
  const { isAdmin } = useAuth();

  return (
    <div className="pb-12">
      <FeaturedBanner
        title={movie.title}
        imageUrl={movie.imageUrl}
        headingLevel={1}
        meta={<MovieMetaTags movie={movie} />}
        description={movie.description || NO_DESCRIPTION}
        clampDescription={false}
        layout="page"
        backLink={<BackLink to="/peliculas">Volver a películas</BackLink>}
        actions={
          <>
            <WatchButton videoUrl={movie.videoUrl} title={movie.title} />
            <FavoriteButton movie={movie} />
            {isAdmin && (
              <Link
                to={adminEditMoviePath(movie.id)}
                state={{ returnTo: moviePath(movie.id) } satisfies ReturnToState}
                aria-label={`Editar ${movie.title}`}
                className={buttonClasses('ghost', 'col-span-2 sm:col-span-1')}
              >
                <Pencil aria-hidden="true" className="size-4" />
                Editar
              </Link>
            )}
          </>
        }
      />

      <div className={`${DETAIL_CONTAINER_CLASS} px-4 sm:px-6`}>
        <FactSheet facts={movieFacts(movie)} className={MOVIE_FACTS_CLASS} listClassName={MOVIE_FACTS_LIST_CLASS} />
      </div>
      {/* Mismo contenedor centrado, pero sin relleno propio: la fila ya lleva el suyo (y sus tarjetas se alinean con la ficha).
          `empty:hidden`: sin similares la fila no pinta nada, y su margen no debe dejar un hueco al pie.
          `key`: la ruta /peliculas/:id reutiliza este componente al pasar de una película a otra; si las dos
          están en caché nada pasa por «cargando» y React conservaría el mismo <ul>, con el desplazamiento
          horizontal de la película anterior. Con la clave, cada película empieza su fila por el principio. */}
      <div className={`${DETAIL_CONTAINER_CLASS} mt-10 empty:hidden md:mt-12`}>
        <SimilarMoviesRow key={movie.id} movieId={movie.id} enabled={confirmed} />
      </div>
    </div>
  );
}

/**
 * Datos de la ficha de una película. Los géneros solo aparecen si tiene alguno
 * y la fecha de alta solo si es válida: nunca un dato vacío. Hoy una película
 * siempre tiene géneros (la API exige al menos uno al darla de alta y al
 * editarla, y no deja borrar un género en uso: `GenreInUseException`), así que
 * la comprobación es solo defensiva, por si la respuesta llegara sin ellos.
 */
function movieFacts(movie: Movie): Fact[] {
  const facts: Fact[] = [
    { term: 'Estreno', detail: movie.releaseYear },
    { term: 'Duración', detail: formatDuration(movie.duration) },
  ];
  if (movie.genres.length > 0) {
    facts.push({ term: 'Géneros', detail: <GenreLinks kind="movies" genres={movie.genres} />, wide: true });
  }
  const added = formatAddedDate(movie.createdAt);
  if (added) facts.push({ term: 'En el catálogo desde', detail: added });
  return facts;
}
