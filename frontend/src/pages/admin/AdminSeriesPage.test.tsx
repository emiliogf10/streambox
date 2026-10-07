/**
 * Tests del listado de series del panel (`AdminSeriesPage`).
 *
 * La mecánica de búsqueda, paginación y borrado es la de películas (hooks
 * compartidos `useAdminSearchList` y `useCatalogDelete`), así que aquí se
 * comprueba que la pantalla de series la usa bien con SUS endpoints
 * (`GET /api/admin/series`, que incluye las series vacías, y
 * `DELETE /api/series/{id}`) y, sobre todo, lo propio de las series: años en
 * emisión, temporadas y episodios, la marca de "sin episodios" y el número de
 * episodios en la confirmación de borrado. `fetch` está simulado; la sesión,
 * los avisos y el router son los reales.
 */
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { installManualTimers, passTime } from '../../test/fakeTimers';
import {
  errorResponse,
  jsonResponse,
  makePage,
  makeSeries,
  noContentResponse,
  renderWithProviders,
  routeFetch,
} from '../../test/helpers';
import { AdminSeriesPage } from './AdminSeriesPage';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

/** Clave de `routeFetch` del listado sin búsqueda (lo último añadido primero), página `page` (desde 0). */
const listKey = (page = 0) => `GET /api/admin/series?page=${page}&size=10&sort=createdAt&direction=desc`;
/** Clave de la búsqueda por título (orden alfabético), página `page` (desde 0). */
const searchKey = (title: string, page = 0) =>
  `GET /api/admin/series?title=${encodeURIComponent(title)}&page=${page}&size=10&sort=title`;

const dark = makeSeries({
  id: 4,
  title: 'Dark',
  releaseYear: 2017,
  endYear: 2020,
  seasonCount: 3,
  episodeCount: 26,
  imageUrl: '/covers/dark.webp',
  genres: [
    { id: 1, name: 'Ciencia ficción' },
    { id: 2, name: 'Drama' },
  ],
});
const severance = makeSeries({ id: 5, title: 'Severance', releaseYear: 2022, endYear: null, seasonCount: 2, episodeCount: 19 });
const empty = makeSeries({ id: 6, title: 'Piloto', releaseYear: 2025, endYear: null, seasonCount: 0, episodeCount: 0 });
const single = makeSeries({ id: 7, title: 'Corto', seasonCount: 1, episodeCount: 1 });

const HIDDEN_MARK = 'Sin episodios · oculta para los usuarios';

/** Muestra la búsqueda de la URL y el estado de navegación: permite comprobar `?q=&page=` y la "vuelta". */
function LocationDetails() {
  const location = useLocation();
  const state = location.state as { returnTo?: string } | null;
  return (
    <>
      <p>{`busqueda:${location.search}`}</p>
      <p>{`volver-a:${state?.returnTo ?? ''}`}</p>
    </>
  );
}

function renderList(route = '/admin/series') {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/admin/series" element={<AdminSeriesPage />} />
        <Route path="/admin/series/nueva" element={<p>Pantalla de alta</p>} />
        <Route path="/admin/series/:id/editar" element={<p>Pantalla de edición</p>} />
      </Routes>
      <LocationDetails />
    </>,
    { route, session: true },
  );
}

/** Peticiones hechas a `fetch` cuya URL empieza por `prefix`. */
function callsTo(prefix: string) {
  return fetchMock.mock.calls.filter(([url]) => String(url).startsWith(prefix));
}

/** Espera a que un aviso quede visible en la PÁGINA (mientras el diálogo está abierto se pintan dentro de él). */
async function expectToastOnPage(text: string | RegExp) {
  await waitFor(() => expect(screen.getByText(text).closest('dialog')).toBeNull());
}

const row = (title: string) => screen.getByRole('row', { name: new RegExp(`^${title}`) });

describe('AdminSeriesPage: listado', () => {
  it('pide la vista de gestión (lo último añadido primero) y pinta años, temporadas, episodios y géneros', async () => {
    routeFetch(fetchMock, {
      [listKey()]: () => jsonResponse(makePage([dark, severance], { totalElements: 12, totalPages: 2, hasNext: true, size: 10 })),
    });
    renderList();

    expect(screen.getByText('Cargando series...')).toBeInTheDocument();
    const table = await screen.findByRole('table', { name: 'Series del catálogo, página 1 de 2' });
    expect(document.title).toBe('Series · Administración — StreamBox');
    expect(screen.getByRole('heading', { level: 2, name: 'Series' })).toBeInTheDocument();

    const darkRow = within(table).getByRole('row', { name: /^Dark/ });
    expect(within(darkRow).getByRole('rowheader')).toHaveTextContent(/^Dark/);
    const cells = within(darkRow).getAllByRole('cell');
    expect(cells.some((cell) => cell.textContent === '2017–2020')).toBe(true);
    expect(cells.some((cell) => cell.textContent === '3 temporadas26 episodios')).toBe(true);
    expect(cells.some((cell) => cell.textContent === 'Ciencia ficción, Drama')).toBe(true);
    // La portada sale de imageUrl, a través de MoviePoster.
    expect(darkRow.querySelector('img')).toHaveAttribute('src', '/covers/dark.webp');
    expect(within(darkRow).getByRole('link', { name: 'Editar Dark' })).toHaveAttribute('href', '/admin/series/4/editar');
    expect(within(darkRow).getByRole('button', { name: 'Borrar Dark' })).toBeInTheDocument();
    // Una serie con episodios no lleva la marca de oculta.
    expect(within(darkRow).queryByText(HIDDEN_MARK)).not.toBeInTheDocument();

    // En emisión: «2022–» y, además, con palabras (no solo la raya).
    const onAirYears = within(row('Severance')).getAllByRole('cell').find((cell) => cell.textContent?.startsWith('2022–'));
    expect(onAirYears).toHaveTextContent('2022–En emisión');

    const pagination = screen.getByRole('navigation', { name: 'Paginación de series' });
    expect(within(pagination).getByText('Página 1 de 2')).toBeInTheDocument();
    expect(within(pagination).getByText('12 series')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Nueva serie' })).toHaveAttribute('href', '/admin/series/nueva');
  });

  it('marca las series sin episodios con texto (no solo color): «Sin episodios · oculta para los usuarios»', async () => {
    routeFetch(fetchMock, { [listKey()]: () => jsonResponse(makePage([empty, dark])) });
    renderList();

    const emptyRow = await screen.findByRole('row', { name: /^Piloto/ });
    // La marca va en la celda del título, visible en todos los anchos (no en una columna que se oculta en móvil).
    expect(within(within(emptyRow).getByRole('rowheader')).getByText(HIDDEN_MARK)).toBeInTheDocument();
    expect(screen.getAllByText(HIDDEN_MARK)).toHaveLength(1);
  });

  it('sin series: lo explica e invita a crear la primera', async () => {
    routeFetch(fetchMock, { [listKey()]: () => jsonResponse(makePage([], { totalPages: 0 })) });
    renderList();

    expect(await screen.findByRole('heading', { name: 'Todavía no hay series' })).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Nueva serie' }).length).toBeGreaterThan(0);
  });

  it('si falla la carga muestra el error y «Reintentar» vuelve a pedirla', async () => {
    routeFetch(fetchMock, { [listKey()]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });
    const user = userEvent.setup();
    renderList();

    expect(await screen.findByRole('heading', { name: 'No se pudieron cargar las series' })).toBeInTheDocument();

    routeFetch(fetchMock, { [listKey()]: () => jsonResponse(makePage([dark])) });
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByRole('row', { name: /^Dark/ })).toBeInTheDocument();
  });

  it('«Editar» pasa al formulario la URL del listado (búsqueda y página) para volver al mismo sitio', async () => {
    routeFetch(fetchMock, {
      [searchKey('da', 1)]: () => jsonResponse(makePage([dark], { page: 1, totalElements: 11, totalPages: 2 })),
    });
    const user = userEvent.setup();
    renderList('/admin/series?q=da&page=2');

    await user.click(await screen.findByRole('link', { name: 'Editar Dark' }));

    expect(screen.getByText('Pantalla de edición')).toBeInTheDocument();
    expect(screen.getByText('volver-a:/admin/series?q=da&page=2')).toBeInTheDocument();
  });
});

describe('AdminSeriesPage: búsqueda', () => {
  /** Espera tras la última tecla antes de buscar (`ADMIN_SEARCH_DEBOUNCE_MS`). */
  const DEBOUNCE_MS = 300;

  // Reloj falso: el debounce se comprueba exacto y sin depender de lo cargada que esté la máquina.
  beforeEach(() => installManualTimers());
  afterEach(() => vi.useRealTimers());
  const setupUser = () => userEvent.setup({ delay: null });

  it('espera a que se deje de escribir: UNA petición con el título, y la búsqueda queda en la URL', async () => {
    routeFetch(fetchMock, {
      [listKey()]: () => jsonResponse(makePage([dark, severance])),
      [searchKey('sev')]: () => jsonResponse(makePage([severance])),
    });
    const user = setupUser();
    renderList();
    await screen.findByRole('row', { name: /^Dark/ });
    await user.click(screen.getByRole('searchbox', { name: 'Buscar por título' }));

    for (const key of ['s', 'e', 'v']) {
      await user.keyboard(key);
      passTime(DEBOUNCE_MS - 50);
      expect(callsTo('/api/admin/series?title')).toHaveLength(0);
    }
    passTime(49);
    expect(callsTo('/api/admin/series?title')).toHaveLength(0);
    passTime(1);
    expect(callsTo('/api/admin/series?title').map(([url]) => String(url))).toEqual([searchKey('sev').slice(4)]);

    await waitFor(() => expect(screen.queryByRole('row', { name: /^Dark/ })).not.toBeInTheDocument());
    expect(row('Severance')).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Resultados para «sev», página 1 de 1' })).not.toHaveAttribute('aria-busy');
    expect(screen.getByText('busqueda:?q=sev')).toBeInTheDocument();
  });

  it('cancela la búsqueda anterior si cambia el texto antes de que responda (AbortController)', async () => {
    routeFetch(fetchMock, {
      [listKey()]: () => jsonResponse(makePage([dark])),
      [searchKey('s')]: () => new Promise<Response>(() => {}), // no responde nunca
      [searchKey('se')]: () => jsonResponse(makePage([severance])),
    });
    const user = setupUser();
    renderList();
    await screen.findByRole('row', { name: /^Dark/ });
    const input = screen.getByRole('searchbox', { name: 'Buscar por título' });

    await user.type(input, 's');
    passTime(DEBOUNCE_MS);
    await user.type(input, 'e');
    passTime(DEBOUNCE_MS);

    expect(await screen.findByRole('row', { name: /^Severance/ })).toBeInTheDocument();
    const [, firstInit] = callsTo('/api/admin/series?title')[0];
    expect(firstInit?.signal?.aborted).toBe(true);
  });

  it('sin coincidencias lo dice y «Borrar búsqueda» vuelve a todas las series', async () => {
    routeFetch(fetchMock, {
      [listKey()]: () => jsonResponse(makePage([dark])),
      [searchKey('zzz')]: () => jsonResponse(makePage([], { totalPages: 0 })),
    });
    const user = setupUser();
    renderList('/admin/series?q=zzz');

    expect(await screen.findByRole('heading', { name: 'Sin resultados para «zzz»' })).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: 'Buscar por título' })).toHaveValue('zzz');

    await user.click(screen.getByRole('button', { name: 'Borrar búsqueda' }));

    expect(await screen.findByRole('row', { name: /^Dark/ })).toBeInTheDocument();
    expect(screen.getByText('busqueda:')).toBeInTheDocument();
  });
});

describe('AdminSeriesPage: paginación', () => {
  it('«Siguiente» y «Anterior» cambian de página en la URL', async () => {
    routeFetch(fetchMock, {
      [listKey(0)]: () => jsonResponse(makePage([dark], { totalElements: 11, totalPages: 2, hasNext: true })),
      [listKey(1)]: () => jsonResponse(makePage([severance], { page: 1, totalElements: 11, totalPages: 2, hasPrevious: true })),
    });
    const user = userEvent.setup();
    renderList();
    await screen.findByRole('row', { name: /^Dark/ });

    await user.click(screen.getByRole('button', { name: 'Siguiente' }));

    expect(await screen.findByRole('row', { name: /^Severance/ })).toBeInTheDocument();
    expect(screen.getByText('busqueda:?page=2')).toBeInTheDocument();
    expect(screen.getByText('Página 2 de 2')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Anterior' }));
    expect(await screen.findByRole('row', { name: /^Dark/ })).toBeInTheDocument();
    expect(screen.getByText('busqueda:')).toBeInTheDocument();
  });

  it('una página fuera de rango (?page=9) lleva a la última que existe', async () => {
    routeFetch(fetchMock, {
      [listKey(8)]: () => jsonResponse(makePage([], { page: 8, totalElements: 11, totalPages: 2 })),
      [listKey(1)]: () => jsonResponse(makePage([severance], { page: 1, totalElements: 11, totalPages: 2 })),
    });
    renderList('/admin/series?page=9');

    expect(await screen.findByRole('row', { name: /^Severance/ })).toBeInTheDocument();
    expect(screen.getByText('busqueda:?page=2')).toBeInTheDocument();
  });
});

describe('AdminSeriesPage: borrar', () => {
  it('la confirmación dice cuántos episodios se borran con la serie; «Cancelar» no borra', async () => {
    routeFetch(fetchMock, { [listKey()]: () => jsonResponse(makePage([dark])) });
    const user = userEvent.setup();
    renderList();

    await user.click(await screen.findByRole('button', { name: 'Borrar Dark' }));

    const dialog = screen.getByRole('alertdialog', { name: '¿Borrar «Dark»?' });
    expect(dialog).toHaveAccessibleDescription(
      'Se borrarán también sus 26 episodios y se quitará de las listas de todos los usuarios. Esta acción no se puede deshacer.',
    );
    expect(within(dialog).getByRole('button', { name: 'Cancelar' })).toHaveFocus();

    await user.click(within(dialog).getByRole('button', { name: 'Cancelar' }));

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(callsTo('/api/series/4')).toHaveLength(0);
    expect(row('Dark')).toBeInTheDocument();
  });

  it.each([
    [empty, 'Todavía no tiene episodios. También se quitará de las listas de todos los usuarios. Esta acción no se puede deshacer.'],
    [single, 'Se borrará también su episodio y se quitará de las listas de todos los usuarios. Esta acción no se puede deshacer.'],
  ])('sin episodios o con uno, el texto se adapta (%#)', async (series, description) => {
    routeFetch(fetchMock, { [listKey()]: () => jsonResponse(makePage([series])) });
    const user = userEvent.setup();
    renderList();

    await user.click(await screen.findByRole('button', { name: `Borrar ${series.title}` }));

    expect(screen.getByRole('alertdialog')).toHaveAccessibleDescription(description);
  });

  it('al confirmar espera al servidor (DELETE /api/series/{id}), avisa, recarga y lleva el foco al título', async () => {
    let resolveDelete: (response: Response) => void = () => {};
    let deleted = false;
    routeFetch(fetchMock, {
      [listKey()]: () => jsonResponse(makePage(deleted ? [severance] : [dark, severance])),
      'DELETE /api/series/4': () =>
        new Promise<Response>((resolve) => {
          resolveDelete = (response) => {
            deleted = true;
            resolve(response);
          };
        }),
    });
    const user = userEvent.setup();
    renderList();

    await user.click(await screen.findByRole('button', { name: 'Borrar Dark' }));
    await user.click(screen.getByRole('button', { name: 'Sí, borrar serie' }));

    // No es optimista: mientras el servidor no responde, la serie sigue y el diálogo no se puede cerrar.
    expect(screen.getByRole('button', { name: 'Procesando...' })).toBeDisabled();
    expect(row('Dark')).toBeInTheDocument();

    resolveDelete(noContentResponse());

    await expectToastOnPage('«Dark» se ha borrado del catálogo.');
    await waitFor(() => expect(screen.queryByRole('row', { name: /^Dark/ })).not.toBeInTheDocument());
    expect(screen.getByRole('heading', { level: 2, name: 'Series' })).toHaveFocus();
  });

  it('404 al borrar: ya estaba borrada; se informa (sin error) y se recarga', async () => {
    let deleted = false;
    routeFetch(fetchMock, {
      [listKey()]: () => jsonResponse(makePage(deleted ? [severance] : [dark, severance])),
      'DELETE /api/series/4': () => {
        deleted = true;
        return errorResponse(404, 'RESOURCE_NOT_FOUND', 'Serie no encontrada');
      },
    });
    const user = userEvent.setup();
    renderList();

    await user.click(await screen.findByRole('button', { name: 'Borrar Dark' }));
    await user.click(screen.getByRole('button', { name: 'Sí, borrar serie' }));

    await expectToastOnPage('«Dark» ya no estaba en el catálogo. Se ha actualizado el listado.');
    await waitFor(() => expect(screen.queryByRole('row', { name: /^Dark/ })).not.toBeInTheDocument());
  });

  it('otro error (p. ej. 500): avisa del fallo y la serie sigue en la lista', async () => {
    routeFetch(fetchMock, {
      [listKey()]: () => jsonResponse(makePage([dark])),
      'DELETE /api/series/4': () => errorResponse(500, 'INTERNAL_ERROR', 'boom'),
    });
    const user = userEvent.setup();
    renderList();

    await user.click(await screen.findByRole('button', { name: 'Borrar Dark' }));
    await user.click(screen.getByRole('button', { name: 'Sí, borrar serie' }));

    await expectToastOnPage(/El servidor ha tenido un problema/);
    expect(await screen.findByRole('row', { name: /^Dark/ })).toBeInTheDocument();
  });
});
