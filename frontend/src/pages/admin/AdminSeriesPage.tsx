import type { ReactNode } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { EyeOff, Plus, SearchX, Tv } from 'lucide-react';
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
import { useInvalidateCatalog } from '../../hooks/useInvalidateCatalog';
import { apiFetch } from '../../lib/api';
import { queryKeys } from '../../lib/queryKeys';
import { formatEpisodeCount, formatSeasonCount, formatSeriesYears, isOnAir } from '../../lib/series';
import type { PageResponse, Series } from '../../lib/types';
import { AdminRowActions } from './AdminRowActions';
import { ADMIN_NEW_SERIES_PATH, adminEditSeriesPath } from './adminPaths';
import type { ReturnToState } from './adminPaths';

/** Series por página del listado (las mismas que películas: la tabla cabe en una pantalla de escritorio). */
const PAGE_SIZE = 10;

/**
 * Pide una página de la vista de gestión `GET /api/admin/series`, que a
 * diferencia de `/api/series` **incluye las series sin episodios** (las que
 * los usuarios no ven y el administrador tiene que poder completar). Con texto
 * filtra por título y ordena alfabéticamente; sin texto, lo último añadido
 * primero, igual que el listado de películas.
 *
 * Recibe el `signal` de TanStack: si se cambia de página o de búsqueda antes de que responda, se cancela.
 */
function fetchSeries(query: string, page: number, signal: AbortSignal): Promise<PageResponse<Series>> {
  const params = query
    ? { title: query, page: page - 1, size: PAGE_SIZE, sort: 'title' }
    : { page: page - 1, size: PAGE_SIZE, sort: 'createdAt', direction: 'desc' };
  return apiFetch<PageResponse<Series>>('/admin/series', { params, signal });
}

/** Ruta del `DELETE` de una serie (la base de datos arrastra sus episodios, géneros y favoritos). */
const seriesDeletePath = (series: Series) => `/series/${series.id}`;

/**
 * Texto del diálogo de borrado: dice cuántos episodios se van con la serie,
 * porque es lo que de verdad se pierde (el borrado arrastra los episodios en
 * cascada) y un número concreto hace pensar dos veces más que "todo".
 */
function deleteDescription(series: Series): string {
  const tail = 'se quitará de las listas de todos los usuarios. Esta acción no se puede deshacer.';
  if (series.episodeCount === 0) return `Todavía no tiene episodios. También ${tail}`;
  const episodes = series.episodeCount === 1 ? 'Se borrará también su episodio' : `Se borrarán también sus ${series.episodeCount} episodios`;
  return `${episodes} y ${tail}`;
}

/**
 * Listado de series del panel (`/admin/series`): tabla paginada con buscador
 * por título, alta, edición y borrado. Funciona igual que el de películas
 * (búsqueda y página en la URL con {@link useAdminSearchList}, borrado con
 * confirmación y no optimista con {@link useCatalogDelete}); lo propio de las
 * series es:
 *
 * - **Años** «2019–2022», o «2021–» si sigue en emisión (con «En emisión»
 *   escrito al lado: no se confía solo en la raya).
 * - **Temporadas y episodios** de cada serie (los recuentos vienen del servidor).
 * - **Series sin episodios marcadas** con «Sin episodios · oculta para los
 *   usuarios», con icono y texto (no solo color) y visible en TODOS los anchos,
 *   porque es justo lo que el administrador tiene que arreglar: una serie
 *   vacía no aparece en ningún sitio para los usuarios.
 * - El diálogo de borrado dice cuántos episodios se borran con ella.
 *
 * **Móvil (375 px):** como en películas, por debajo de `md` las columnas
 * secundarias se ocultan y sus datos pasan a una línea bajo el título; las
 * acciones se quedan en icono (44 px) con el nombre completo en `aria-label`.
 */
export function AdminSeriesPage() {
  useDocumentTitle('Series · Administración');
  const location = useLocation();
  const invalidateCatalog = useInvalidateCatalog();
  // Tras borrar (o saber que ya no estaba) se invalida la raíz: este listado se refresca y, con él,
  // los listados públicos y «Mi lista» de quien tuviera el título.
  const refreshAfterDelete = () => void invalidateCatalog('series');
  const list = useAdminSearchList(queryKeys.series.admin, fetchSeries, 'No se pudieron cargar las series.');
  // Desestructurado: el resultado incluye una ref (`headingRef`) y leer propiedades de ese objeto al pintar
  // es lo que el analizador (regla `react(refs)`) no puede distinguir de leer `ref.current`.
  const { pending: toDelete, ask: askDelete, cancel: cancelDelete, confirm: confirmDelete, deleting, headingRef } =
    useCatalogDelete(seriesDeletePath, refreshAfterDelete);
  const { query, page, data } = list;

  const returnState: ReturnToState = { returnTo: `${location.pathname}${location.search}` };

  let content: ReactNode;
  if ((!data && list.loading) || list.pendingPageFix) {
    content = <LoadingState label="Cargando series..." />;
  } else if (list.errorMessage !== null) {
    content = <ErrorState title="No se pudieron cargar las series" message={list.errorMessage} onRetry={list.reload} />;
  } else if (data && data.totalElements === 0) {
    content = query ? (
      <EmptyState
        icon={<SearchX className="size-8" />}
        title={`Sin resultados para «${query}»`}
        description="Prueba con otra parte del título o borra la búsqueda para ver todas las series."
        action={
          <Button variant="outline" onClick={list.clearSearch}>
            Borrar búsqueda
          </Button>
        }
      />
    ) : (
      <EmptyState
        icon={<Tv className="size-8" />}
        title="Todavía no hay series"
        description="Crea la primera y añádele episodios: los usuarios la verán en cuanto tenga al menos uno."
        action={
          <Link to={ADMIN_NEW_SERIES_PATH} className={buttonClasses('primary')}>
            <Plus aria-hidden="true" className="size-4" />
            Nueva serie
          </Link>
        }
      />
    );
  } else if (data) {
    content = (
      <>
        <SeriesTable
          series={data.content}
          caption={`${query ? `Resultados para «${query}»` : 'Series del catálogo'}, página ${page} de ${Math.max(1, data.totalPages)}`}
          busy={list.loading}
          returnState={returnState}
          onDelete={askDelete}
        />
        <div className="mt-5">
          <Pagination
            page={page}
            totalPages={data.totalPages}
            totalElements={data.totalElements}
            itemNames={['serie', 'series']}
            label="Paginación de series"
            onPageChange={list.goToPage}
          />
        </div>
      </>
    );
  }

  return (
    <section aria-labelledby="admin-series-title">
      <div className="mb-5 flex flex-wrap items-center justify-between gap-4">
        <div className="min-w-0">
          {/* tabIndex -1: recibe el foco por código tras borrar; no es una parada de tabulador. */}
          <h2
            id="admin-series-title"
            ref={headingRef}
            tabIndex={-1}
            className="text-xl font-semibold tracking-tight outline-hidden"
          >
            Series
          </h2>
          <p className="mt-1 text-sm text-muted">Los usuarios solo ven las series que tienen al menos un episodio.</p>
        </div>
        <Link to={ADMIN_NEW_SERIES_PATH} className={buttonClasses('primary')}>
          <Plus aria-hidden="true" className="size-4" />
          Nueva serie
        </Link>
      </div>

      <div role="search" className="mb-5 max-w-md">
        <FormField
          id="admin-series-search"
          label="Buscar por título"
          type="search"
          autoComplete="off"
          spellCheck={false}
          enterKeyHint="search"
          placeholder="Ej.: Dark"
          value={list.input}
          onChange={(event) => list.setInput(event.target.value)}
        />
      </div>
      {/* Anuncia la búsqueda en curso; el resultado lo anuncia «Página X de Y · N series». */}
      <p role="status" className="sr-only">
        {list.searching ? 'Buscando...' : ''}
      </p>

      {content}

      <ConfirmDialog
        open={toDelete !== null}
        title={toDelete ? `¿Borrar «${toDelete.title}»?` : ''}
        description={toDelete ? deleteDescription(toDelete) : ''}
        confirmLabel="Sí, borrar serie"
        busy={deleting}
        onConfirm={confirmDelete}
        onCancel={cancelDelete}
      />
    </section>
  );
}

/** Propiedades de {@link SeriesTable}. */
interface SeriesTableProps {
  series: Series[];
  /** Texto de `<caption>` (solo para lectores de pantalla): qué se lista y en qué página. */
  caption: string;
  /** `true` mientras llega otra página: la tabla se atenúa y se marca `aria-busy`. */
  busy: boolean;
  returnState: ReturnToState;
  onDelete: (series: Series) => void;
}

/**
 * Tabla del listado de series. Columnas: portada, título (con la marca de
 * "sin episodios"), años y contenido (desde `md`), géneros (desde `xl`, para
 * no estrechar el título en tablet) y acciones.
 */
function SeriesTable({ series, caption, busy, returnState, onDelete }: SeriesTableProps) {
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
            <th scope="col" className="hidden w-28 px-3 py-3 md:table-cell">
              Años
            </th>
            <th scope="col" className="hidden w-36 px-3 py-3 md:table-cell">
              Contenido
            </th>
            <th scope="col" className="hidden w-1/5 px-3 py-3 xl:table-cell">
              Géneros
            </th>
            <th scope="col" className="w-30 px-3 py-3 text-right sm:w-56">
              Acciones
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {series.map((item) => (
            <SeriesRow
              key={item.id}
              series={item}
              returnState={returnState}
              onDelete={() => onDelete(item)}
            />
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** Una fila de {@link SeriesTable}. */
function SeriesRow({ series, returnState, onDelete }: { series: Series; returnState: ReturnToState; onDelete: () => void }) {
  const genres = series.genres.map((genre) => genre.name).join(', ');
  const years = formatSeriesYears(series.releaseYear, series.endYear);
  const onAir = isOnAir(series);
  const empty = series.episodeCount === 0;
  const seasons = formatSeasonCount(series.seasonCount);
  const episodes = formatEpisodeCount(series.episodeCount);

  return (
    <tr className="align-middle transition-colors hover:bg-white/3">
      <td className="px-3 py-2">
        <MoviePoster title={series.title} src={series.imageUrl} compact className="h-16 w-11 rounded-md" />
      </td>
      <th scope="row" className="px-3 py-2 font-normal">
        <span className="line-clamp-2 font-semibold wrap-anywhere text-white">{series.title}</span>
        {/* Datos de las columnas que se ocultan en pantallas estrechas. */}
        <span className="mt-0.5 block text-xs text-muted tabular-nums md:hidden">
          {years}
          {onAir && <span className="sr-only"> (en emisión)</span>}
          {!empty && ` · ${seasons} · ${episodes}`}
        </span>
        {genres && <span className="mt-0.5 block truncate text-xs text-muted xl:hidden">{genres}</span>}
        {empty && (
          <span className="mt-1.5 inline-flex max-w-full items-start gap-1.5 rounded-md border border-accent/40 bg-accent/10 px-2 py-0.5 text-xs font-medium text-accent">
            <EyeOff aria-hidden="true" className="mt-px size-3.5 shrink-0" />
            <span className="min-w-0">Sin episodios · oculta para los usuarios</span>
          </span>
        )}
      </th>
      <td className="hidden px-3 py-2 text-white/90 tabular-nums md:table-cell">
        {years}
        {onAir && <span className="block text-xs text-muted">En emisión</span>}
      </td>
      <td className="hidden px-3 py-2 text-white/90 md:table-cell">
        <span className="block">{seasons}</span>
        <span className="block text-xs text-muted">{episodes}</span>
      </td>
      <td className="hidden px-3 py-2 text-white/90 xl:table-cell">
        <span className="line-clamp-2">{genres || '—'}</span>
      </td>
      <td className="px-3 py-2">
        <AdminRowActions
          title={series.title}
          editTo={adminEditSeriesPath(series.id)}
          returnState={returnState}
          onDelete={onDelete}
        />
      </td>
    </tr>
  );
}
