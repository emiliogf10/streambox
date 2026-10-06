import type { ReactNode } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { Film, Plus, SearchX } from 'lucide-react';
import { Button } from '../../components/Button';
import { buttonClasses } from '../../components/buttonStyles';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { FormField } from '../../components/FormField';
import { LoadingState } from '../../components/LoadingState';
import { MoviePoster } from '../../components/MoviePoster';
import { Pagination } from '../../components/Pagination';
import { useAdminSearchList } from '../../hooks/useAdminSearchList';
import { useCatalogDelete } from '../../hooks/useCatalogDelete';
import { useDocumentTitle } from '../../hooks/useDocumentTitle';
import { apiFetch } from '../../lib/api';
import type { Movie, PageResponse } from '../../lib/types';
import { formatDuration } from '../../lib/utils';
import { AdminRowActions } from './AdminRowActions';
import { ADMIN_NEW_MOVIE_PATH, adminEditMoviePath } from './adminPaths';
import type { ReturnToState } from './adminPaths';

/** Películas por página del listado. Con 10 la tabla cabe en una pantalla de escritorio. */
const PAGE_SIZE = 10;

/**
 * Pide una página: con texto, la búsqueda por título (orden alfabético); sin texto, lo último añadido primero.
 * Es una función de módulo (estable) porque `useAdminSearchList` la usa como dependencia de la carga.
 */
function fetchMovies(query: string, page: number, signal: AbortSignal): Promise<PageResponse<Movie>> {
  return query
    ? apiFetch<PageResponse<Movie>>('/movies/search', {
        params: { title: query, page: page - 1, size: PAGE_SIZE, sort: 'title' },
        signal,
      })
    : apiFetch<PageResponse<Movie>>('/movies', {
        params: { page: page - 1, size: PAGE_SIZE, sort: 'createdAt', direction: 'desc' },
        signal,
      });
}

/** Ruta del `DELETE` de una película (estable, por lo mismo que {@link fetchMovies}). */
const movieDeletePath = (movie: Movie) => `/movies/${movie.id}`;

/**
 * Listado de películas del panel (`/admin/peliculas`): tabla paginada con
 * buscador por título, alta, edición y borrado.
 *
 * La búsqueda y la página viven en la URL (`?q=blade&page=2`), con *debounce*,
 * cancelación de peticiones y corrección de páginas fuera de rango: todo eso lo
 * pone {@link useAdminSearchList}, compartido con la pestaña Series. Mientras
 * llega una página nueva se sigue viendo la anterior atenuada (`aria-busy`), en
 * lugar de un parpadeo a "cargando".
 *
 * **Móvil (375 px).** Es una tabla de verdad en todos los tamaños (con
 * `<caption>`, cabeceras y el título como cabecera de fila), porque convertirla
 * en tarjetas con CSS hace que algunos navegadores dejen de anunciarla como
 * tabla. En lugar de eso, por debajo de `md` se ocultan las columnas secundarias
 * (año, duración y, hasta `lg`, géneros) y ese dato pasa a una línea bajo el
 * título; los botones se quedan en su icono (44 px) con el nombre completo en
 * `aria-label`. `table-fixed` con anchos fijos garantiza que nada empuje la
 * tabla fuera de la pantalla.
 *
 * **Borrar** pide confirmación y espera al servidor ({@link useCatalogDelete}):
 * no es optimista porque es destructivo y afecta a las listas de todos los
 * usuarios. Un 404 significa que ya estaba borrada: se informa y se recarga.
 */
export function AdminMoviesPage() {
  useDocumentTitle('Películas · Administración');
  const location = useLocation();
  const list = useAdminSearchList(fetchMovies, 'No se pudieron cargar las películas.');
  // Desestructurado: el resultado incluye una ref (`headingRef`) y leer propiedades de ese objeto al pintar
  // es lo que el analizador (regla `react(refs)`) no puede distinguir de leer `ref.current`.
  const { pending: toDelete, ask: askDelete, cancel: cancelDelete, confirm: confirmDelete, deleting, headingRef } =
    useCatalogDelete(movieDeletePath, list.reload);
  const { query, page, data } = list;

  const returnState: ReturnToState = { returnTo: `${location.pathname}${location.search}` };

  let content: ReactNode;
  if ((!data && list.loading) || list.pendingPageFix) {
    content = <LoadingState label="Cargando películas..." />;
  } else if (list.errorMessage !== null) {
    content = <ErrorState title="No se pudieron cargar las películas" message={list.errorMessage} onRetry={list.reload} />;
  } else if (data && data.totalElements === 0) {
    content = query ? (
      <EmptyState
        icon={<SearchX className="size-8" />}
        title={`Sin resultados para «${query}»`}
        description="Prueba con otra parte del título o borra la búsqueda para ver todas las películas."
        action={
          <Button variant="outline" onClick={list.clearSearch}>
            Borrar búsqueda
          </Button>
        }
      />
    ) : (
      <EmptyState
        icon={<Film className="size-8" />}
        title="Todavía no hay películas"
        description="Añade la primera y aparecerá en el catálogo de todos los usuarios."
        action={
          <Link to={ADMIN_NEW_MOVIE_PATH} className={buttonClasses('primary')}>
            <Plus aria-hidden="true" className="size-4" />
            Nueva película
          </Link>
        }
      />
    );
  } else if (data) {
    content = (
      <>
        <MoviesTable
          movies={data.content}
          caption={`${query ? `Resultados para «${query}»` : 'Películas del catálogo'}, página ${page} de ${Math.max(1, data.totalPages)}`}
          busy={list.loading}
          returnState={returnState}
          onDelete={askDelete}
        />
        <div className="mt-5">
          <Pagination
            page={page}
            totalPages={data.totalPages}
            totalElements={data.totalElements}
            itemNames={['película', 'películas']}
            label="Paginación de películas"
            onPageChange={list.goToPage}
          />
        </div>
      </>
    );
  }

  return (
    <section aria-labelledby="admin-movies-title">
      <div className="mb-5 flex flex-wrap items-center justify-between gap-4">
        {/* tabIndex -1: recibe el foco por código tras borrar; no es una parada de tabulador. */}
        <h2
          id="admin-movies-title"
          ref={headingRef}
          tabIndex={-1}
          className="text-xl font-semibold tracking-tight outline-hidden"
        >
          Películas
        </h2>
        <Link to={ADMIN_NEW_MOVIE_PATH} className={buttonClasses('primary')}>
          <Plus aria-hidden="true" className="size-4" />
          Nueva película
        </Link>
      </div>

      <div role="search" className="mb-5 max-w-md">
        <FormField
          id="admin-movies-search"
          label="Buscar por título"
          type="search"
          autoComplete="off"
          spellCheck={false}
          enterKeyHint="search"
          placeholder="Ej.: Blade Runner"
          value={list.input}
          onChange={(event) => list.setInput(event.target.value)}
        />
      </div>
      {/* Anuncia la búsqueda en curso; el resultado lo anuncia «Página X de Y · N películas». */}
      <p role="status" className="sr-only">
        {list.searching ? 'Buscando...' : ''}
      </p>

      {content}

      <ConfirmDialog
        open={toDelete !== null}
        title={toDelete ? `¿Borrar «${toDelete.title}»?` : ''}
        description="También se quitará de las listas de todos los usuarios. Esta acción no se puede deshacer."
        confirmLabel="Sí, borrar película"
        busy={deleting}
        onConfirm={confirmDelete}
        onCancel={cancelDelete}
      />
    </section>
  );
}

/** Propiedades de {@link MoviesTable}. */
interface MoviesTableProps {
  movies: Movie[];
  /** Texto de `<caption>` (solo para lectores de pantalla): qué se lista y en qué página. */
  caption: string;
  /** `true` mientras llega otra página: la tabla se atenúa y se marca `aria-busy`. */
  busy: boolean;
  returnState: ReturnToState;
  onDelete: (movie: Movie) => void;
}

/** Tabla del listado. Ver en {@link AdminMoviesPage} cómo se adapta al móvil. */
function MoviesTable({ movies, caption, busy, returnState, onDelete }: MoviesTableProps) {
  return (
    <div className="overflow-hidden rounded-xl border border-line">
      <table
        aria-busy={busy || undefined}
        className={`w-full table-fixed text-left text-sm transition-opacity ${busy ? 'opacity-60' : ''}`}
      >
        <caption className="sr-only">{caption}</caption>
        <thead className="bg-surface text-xs font-semibold tracking-wide text-muted uppercase">
          <tr>
            <th scope="col" className="w-17 px-3 py-3">
              <span className="sr-only">Portada</span>
            </th>
            <th scope="col" className="px-3 py-3">
              Título
            </th>
            <th scope="col" className="hidden w-20 px-3 py-3 md:table-cell">
              Año
            </th>
            <th scope="col" className="hidden w-24 px-3 py-3 md:table-cell">
              Duración
            </th>
            <th scope="col" className="hidden w-1/4 px-3 py-3 lg:table-cell">
              Géneros
            </th>
            <th scope="col" className="w-30 px-3 py-3 text-right sm:w-56">
              Acciones
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {movies.map((movie) => {
            const genres = movie.genres.map((genre) => genre.name).join(', ');
            return (
              <tr key={movie.id} className="align-middle transition-colors hover:bg-white/3">
                <td className="px-3 py-2">
                  <MoviePoster title={movie.title} src={movie.imageUrl} compact className="h-16 w-11 rounded-md" />
                </td>
                <th scope="row" className="px-3 py-2 font-normal">
                  <span className="line-clamp-2 font-semibold wrap-anywhere text-white">{movie.title}</span>
                  {/* Datos de las columnas que se ocultan en pantallas estrechas. */}
                  <span className="mt-0.5 block text-xs text-muted tabular-nums md:hidden">
                    {movie.releaseYear} · {formatDuration(movie.duration)}
                  </span>
                  {genres && <span className="mt-0.5 block truncate text-xs text-muted lg:hidden">{genres}</span>}
                </th>
                <td className="hidden px-3 py-2 text-white/90 tabular-nums md:table-cell">{movie.releaseYear}</td>
                <td className="hidden px-3 py-2 text-white/90 tabular-nums md:table-cell">
                  {formatDuration(movie.duration)}
                </td>
                <td className="hidden px-3 py-2 text-white/90 lg:table-cell">
                  <span className="line-clamp-2">{genres || '—'}</span>
                </td>
                <td className="px-3 py-2">
                  <AdminRowActions
                    title={movie.title}
                    editTo={adminEditMoviePath(movie.id)}
                    returnState={returnState}
                    onDelete={() => onDelete(movie)}
                  />
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
