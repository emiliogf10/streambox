import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { Link, useLocation, useSearchParams } from 'react-router-dom';
import { Film, Pencil, Plus, SearchX, Trash } from 'lucide-react';
import { Button } from '../../components/Button';
import { buttonClasses } from '../../components/buttonStyles';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { EmptyState } from '../../components/EmptyState';
import { ErrorState } from '../../components/ErrorState';
import { FormField } from '../../components/FormField';
import { LoadingState } from '../../components/LoadingState';
import { MoviePoster } from '../../components/MoviePoster';
import { Pagination } from '../../components/Pagination';
import { useToast } from '../../context/ToastContext';
import { useDebouncedValue } from '../../hooks/useDebouncedValue';
import { useDocumentTitle } from '../../hooks/useDocumentTitle';
import { ApiError, apiFetch, getErrorMessage, isAbortError } from '../../lib/api';
import type { Movie, PageResponse } from '../../lib/types';
import { formatDuration } from '../../lib/utils';
import { ADMIN_NEW_MOVIE_PATH, adminEditMoviePath } from './adminPaths';
import type { ReturnToState } from './adminPaths';

/** Películas por página del listado. Con 10 la tabla cabe en una pantalla de escritorio. */
const PAGE_SIZE = 10;

/** Espera tras la última tecla antes de buscar (igual que el buscador de la barra). */
const SEARCH_DEBOUNCE_MS = 300;

/** Resultado de una carga, junto con la "clave" (búsqueda + página + intento) que lo pidió. */
interface ListResult {
  key: string;
  status: 'ready' | 'error';
  page: PageResponse<Movie> | null;
  errorMessage: string;
}

/** Página (1, 2, 3...) leída de la URL; cualquier valor raro (`abc`, `0`, `-2`) cuenta como la 1. */
function parsePage(raw: string | null): number {
  const page = Number(raw);
  return Number.isInteger(page) && page >= 1 ? page : 1;
}

/** Pide una página: con texto, la búsqueda por título (orden alfabético); sin texto, lo último añadido primero. */
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

/**
 * Listado de películas del panel (`/admin/peliculas`): tabla paginada con
 * buscador por título, alta, edición y borrado.
 *
 * **La búsqueda y la página viven en la URL** (`?q=blade&page=2`). Así se puede
 * recargar o compartir el listado tal cual, «Atrás» funciona, y al volver de
 * editar una película el administrador sigue en la misma página (el enlace
 * «Editar» pasa esta URL al formulario, ver `getReturnTo`). El campo de texto
 * tiene su propio estado y se vuelca a la URL con un *debounce* de 300 ms: no se
 * hace una petición por letra.
 *
 * **Peticiones sin carreras.** Cada combinación de búsqueda y página cancela la
 * petición anterior (`AbortController`) y, además, el resultado se guarda con la
 * clave que lo pidió: una respuesta antigua nunca se pinta como si fuera la
 * actual. Mientras llega la nueva página se sigue viendo la anterior atenuada
 * (`aria-busy`), en lugar de un parpadeo a "cargando".
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
 * **Borrar** pide confirmación y espera al servidor (no es optimista: es
 * destructivo y afecta a las listas de todos los usuarios). Un 404 significa que
 * ya estaba borrada: se informa y se recarga. Si la página se queda vacía y no
 * es la primera, se retrocede a la última que tenga películas.
 */
export function AdminMoviesPage() {
  useDocumentTitle('Películas · Administración');
  const toast = useToast();
  const location = useLocation();
  const [searchParams, setSearchParams] = useSearchParams();
  const query = (searchParams.get('q') ?? '').trim();
  const page = parsePage(searchParams.get('page'));

  const [input, setInput] = useState(query);
  const debouncedInput = useDebouncedValue(input.trim(), SEARCH_DEBOUNCE_MS);
  /**
   * Última búsqueda que este componente ha llevado a la URL (o ha recibido de ella). Distingue un cambio
   * de `q` PROPIO (el debounce) de uno EXTERNO (Atrás/Adelante, la pestaña «Películas»): solo el externo
   * debe reescribir el campo. Sin esta marca, si la navegación del debounce llega tarde (React Router la
   * hace en una transición) mientras se sigue escribiendo, el campo volvería al texto anterior y se
   * perderían las últimas letras.
   */
  const appliedQuery = useRef(query);

  const [reloadCount, setReloadCount] = useState(0);
  const [result, setResult] = useState<ListResult | null>(null);
  const [movieToDelete, setMovieToDelete] = useState<Movie | null>(null);
  const [deleting, setDeleting] = useState(false);
  const headingRef = useRef<HTMLHeadingElement>(null);
  const focusHeadingAfterClose = useRef(false);

  const requestKey = `${query}|${page}|${reloadCount}`;

  // El texto ya "asentado" pasa a la URL y vuelve a la página 1. `replace`: el historial no se llena
  // con una entrada por letra. Solo reacciona a lo que se escribe (no a cambios de la URL).
  useEffect(() => {
    if (debouncedInput === appliedQuery.current) return;
    appliedQuery.current = debouncedInput;
    setSearchParams(debouncedInput ? { q: debouncedInput } : {}, { replace: true });
  }, [debouncedInput, setSearchParams]);

  // La búsqueda ha cambiado desde fuera: el campo se pone al día.
  useEffect(() => {
    if (query === appliedQuery.current) return;
    appliedQuery.current = query;
    setInput(query);
  }, [query]);

  useEffect(() => {
    const controller = new AbortController();
    fetchMovies(query, page, controller.signal)
      .then((data) => setResult({ key: requestKey, status: 'ready', page: data, errorMessage: '' }))
      .catch((error: unknown) => {
        if (isAbortError(error) || controller.signal.aborted) return;
        if (error instanceof ApiError && error.sessionExpired) return; // ya lo gestiona AuthProvider
        setResult({
          key: requestKey,
          status: 'error',
          page: null,
          errorMessage: getErrorMessage(error, 'No se pudieron cargar las películas.'),
        });
      });
    return () => controller.abort();
  }, [query, page, requestKey]);

  const current = result?.key === requestKey ? result : null;
  const data = current?.page ?? result?.page ?? null; // mientras carga, se sigue viendo la página anterior
  const loading = current === null;

  // Página fuera de rango (`?page=99`, o la última se quedó vacía tras borrar): ir a la última que exista.
  useEffect(() => {
    if (current?.status !== 'ready' || !current.page) return;
    const { content, totalPages } = current.page;
    if (content.length === 0 && page > 1) {
      const last = Math.max(1, totalPages);
      setSearchParams(
        (params) => {
          const next = new URLSearchParams(params);
          if (last > 1) next.set('page', String(last));
          else next.delete('page');
          return next;
        },
        { replace: true },
      );
    }
  }, [current, page, setSearchParams]);

  /** Cambia de página conservando la búsqueda (sí deja entrada en el historial: «Atrás» vuelve a la anterior). */
  const goToPage = (next: number) => {
    setSearchParams((params) => {
      const updated = new URLSearchParams(params);
      if (next > 1) updated.set('page', String(next));
      else updated.delete('page');
      return updated;
    });
  };

  const clearSearch = () => {
    setInput('');
    setSearchParams({}, { replace: true });
  };

  const reload = () => setReloadCount((n) => n + 1);

  const handleConfirmDelete = async () => {
    if (!movieToDelete) return;
    const { id, title } = movieToDelete;
    setDeleting(true);
    try {
      await apiFetch(`/movies/${id}`, { method: 'DELETE' });
      toast.success(`«${title}» se ha borrado del catálogo.`);
      focusHeadingAfterClose.current = true;
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) {
        // Ya no existía (otra persona la borró, u otra pestaña): el estado deseado ya se cumple.
        toast.info(`«${title}» ya no estaba en el catálogo. Se ha actualizado el listado.`);
        focusHeadingAfterClose.current = true;
      } else {
        toast.errorFrom(error, `No se pudo borrar «${title}».`);
      }
    } finally {
      setDeleting(false);
      setMovieToDelete(null);
      reload();
    }
  };

  // Tras borrar, la fila (y su botón, que tenía el foco) desaparece: el foco va al título de la sección.
  // Se hace al cerrarse el diálogo porque mientras está abierto el resto de la página es inerte.
  useEffect(() => {
    if (!movieToDelete && focusHeadingAfterClose.current) {
      focusHeadingAfterClose.current = false;
      headingRef.current?.focus();
    }
  }, [movieToDelete]);

  const returnState: ReturnToState = { returnTo: `${location.pathname}${location.search}` };
  const searching = input.trim() !== query; // el texto aún no se ha aplicado (debounce)

  let content: ReactNode;
  // Una página vacía que no es la primera está a punto de corregirse (efecto de arriba): no se enseña.
  const pendingPageFix = data !== null && data.content.length === 0 && data.totalElements > 0 && page > 1;
  if ((!data && loading) || pendingPageFix) {
    content = <LoadingState label="Cargando películas..." />;
  } else if (current?.status === 'error') {
    content = <ErrorState title="No se pudieron cargar las películas" message={current.errorMessage} onRetry={reload} />;
  } else if (data && data.totalElements === 0) {
    content = query ? (
      <EmptyState
        icon={<SearchX className="size-8" />}
        title={`Sin resultados para «${query}»`}
        description="Prueba con otra parte del título o borra la búsqueda para ver todo el catálogo."
        action={
          <Button variant="outline" onClick={clearSearch}>
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
          busy={loading}
          returnState={returnState}
          onDelete={setMovieToDelete}
        />
        <div className="mt-5">
          <Pagination
            page={page}
            totalPages={data.totalPages}
            totalElements={data.totalElements}
            itemNames={['película', 'películas']}
            label="Paginación de películas"
            onPageChange={goToPage}
          />
        </div>
      </>
    );
  }

  return (
    <section aria-labelledby="admin-movies-title">
      <div className="mb-5 flex flex-wrap items-center justify-between gap-4">
        {/* tabIndex -1: recibe el foco por código tras borrar; no es una parada de tabulador. */}
        <h2 id="admin-movies-title" ref={headingRef} tabIndex={-1} className="text-xl font-semibold tracking-tight outline-hidden">
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
          value={input}
          onChange={(event) => setInput(event.target.value)}
        />
      </div>
      {/* Anuncia la búsqueda en curso; el resultado lo anuncia «Página X de Y · N películas». */}
      <p role="status" className="sr-only">
        {searching || (loading && data) ? 'Buscando...' : ''}
      </p>

      {content}

      <ConfirmDialog
        open={movieToDelete !== null}
        title={movieToDelete ? `¿Borrar «${movieToDelete.title}»?` : ''}
        description="También se quitará de las listas de todos los usuarios. Esta acción no se puede deshacer."
        confirmLabel="Sí, borrar película"
        busy={deleting}
        onConfirm={() => void handleConfirmDelete()}
        onCancel={() => setMovieToDelete(null)}
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

/**
 * Tabla del listado. Ver en {@link AdminMoviesPage} cómo se adapta al móvil.
 * Las acciones llevan el título en su nombre accesible («Editar Interstellar»):
 * en una tabla con veinte botones «Editar», un lector de pantalla que los
 * liste no sabría cuál es cuál.
 */
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
                  <div className="flex justify-end gap-1 sm:gap-2">
                    <Link
                      to={adminEditMoviePath(movie.id)}
                      state={returnState}
                      aria-label={`Editar ${movie.title}`}
                      className={buttonClasses('ghost', 'font-medium max-sm:w-11 max-sm:px-0 sm:px-3')}
                    >
                      <Pencil aria-hidden="true" className="size-4 shrink-0" />
                      <span className="max-sm:sr-only">Editar</span>
                    </Link>
                    <Button
                      variant="ghost"
                      aria-label={`Borrar ${movie.title}`}
                      onClick={() => onDelete(movie)}
                      className="font-medium text-danger hover:bg-red-500/10 hover:text-danger max-sm:w-11 max-sm:px-0 sm:px-3"
                    >
                      <Trash aria-hidden="true" className="size-4 shrink-0" />
                      <span className="max-sm:sr-only">Borrar</span>
                    </Button>
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
