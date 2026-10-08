/**
 * Tests del listado de películas del panel (`AdminMoviesPage`).
 *
 * Protegen lo que un administrador espera de una tabla de gestión: que la
 * búsqueda no lance una petición por letra y que una respuesta vieja no pise a
 * la nueva, que la página y la búsqueda vivan en la URL, que borrar pida
 * confirmación y espere al servidor (404 = ya estaba borrada) y que, si la
 * página se queda vacía, se retroceda. `fetch` está simulado; la sesión, los
 * avisos y el router son los reales.
 */
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Route, Routes, useLocation } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Movie } from '../../lib/types';
import { installManualTimers, passTime } from '../../test/fakeTimers';
import {
  errorResponse,
  jsonResponse,
  makeMovie,
  makePage,
  noContentResponse,
  renderWithProviders,
  routeFetch,
} from '../../test/helpers';
import { AdminMoviesPage } from './AdminMoviesPage';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

/** Clave de `routeFetch` del listado sin búsqueda (lo último añadido primero), página `page` (desde 0). */
const listKey = (page = 0) => `GET /api/movies?page=${page}&size=10&sort=createdAt&direction=desc`;
/** Clave de la búsqueda por título (orden alfabético), página `page` (desde 0). */
const searchKey = (title: string, page = 0) =>
  `GET /api/movies/search?title=${encodeURIComponent(title)}&page=${page}&size=10&sort=title`;

const interstellar = makeMovie({
  id: 7,
  title: 'Interstellar',
  releaseYear: 2014,
  duration: 169,
  imageUrl: '/covers/interstellar.webp',
  genres: [
    { id: 1, name: 'Ciencia ficción' },
    { id: 2, name: 'Drama' },
  ],
});
const dredd = makeMovie({ id: 9, title: 'Dredd', releaseYear: 2012, duration: 95 });

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

function renderList(route = '/admin/peliculas') {
  return renderWithProviders(
    <>
      <Routes>
        <Route path="/admin/peliculas" element={<AdminMoviesPage />} />
        <Route path="/admin/peliculas/nueva" element={<p>Pantalla de alta</p>} />
        <Route path="/admin/peliculas/:id/editar" element={<p>Pantalla de edición</p>} />
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

/**
 * Letra A, B, C... para títulos de prueba. No se usan números: el nombre accesible de la fila junta el
 * título con el año de la línea secundaria ("Uno 12020"), y "Uno 1" no se distinguiría de "Uno 10".
 */
const letter = (index: number) => String.fromCharCode(65 + index);

/**
 * Espera a que un aviso (toast) quede visible en la PÁGINA. Mientras el diálogo de confirmación está
 * abierto los avisos se pintan dentro de él (fuera serían inertes) y, al cerrarse, vuelven a la página:
 * se comprueba ese destino final, no el paso intermedio.
 */
async function expectToastOnPage(text: string | RegExp) {
  await waitFor(() => expect(screen.getByText(text).closest('dialog')).toBeNull());
}

const row = (title: string) => screen.getByRole('row', { name: new RegExp(`^${title}`) });

describe('AdminMoviesPage: listado', () => {
  it('carga la primera página (lo último añadido primero) y la pinta en una tabla accesible', async () => {
    routeFetch(fetchMock, {
      [listKey()]: () => jsonResponse(makePage([interstellar, dredd], { totalElements: 25, totalPages: 3, hasNext: true, size: 10 })),
    });
    renderList();

    expect(screen.getByText('Cargando películas...')).toBeInTheDocument();
    const table = await screen.findByRole('table', { name: 'Películas del catálogo, página 1 de 3' });
    expect(document.title).toBe('Películas · Administración — StreamBox');
    expect(screen.getByRole('heading', { level: 2, name: 'Películas' })).toBeInTheDocument();

    const first = within(table).getByRole('row', { name: /^Interstellar/ });
    // El título es la cabecera de la fila; año, duración y géneros, sus celdas.
    expect(within(first).getByRole('rowheader')).toHaveTextContent('Interstellar');
    expect(within(first).getAllByRole('cell').map((cell) => cell.textContent)).toEqual(
      expect.arrayContaining(['2014', '2h 49m', 'Ciencia ficción, Drama']),
    );
    // La portada sale de imageUrl, a través de MoviePoster.
    expect(first.querySelector('img')).toHaveAttribute('src', '/covers/interstellar.webp');
    // Las acciones nombran la película: «Editar» a secas no sirve en una lista de veinte.
    expect(within(first).getByRole('link', { name: 'Editar Interstellar' })).toHaveAttribute(
      'href',
      '/admin/peliculas/7/editar',
    );
    expect(within(first).getByRole('button', { name: 'Borrar Interstellar' })).toBeInTheDocument();

    const pagination = screen.getByRole('navigation', { name: 'Paginación de películas' });
    expect(within(pagination).getByText('Página 1 de 3')).toBeInTheDocument();
    expect(within(pagination).getByText('25 películas')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Nueva película' })).toHaveAttribute('href', '/admin/peliculas/nueva');
  });

  it('catálogo vacío: lo explica e invita a crear la primera película', async () => {
    routeFetch(fetchMock, { [listKey()]: () => jsonResponse(makePage([], { totalPages: 0 })) });
    renderList();

    expect(await screen.findByRole('heading', { name: 'Todavía no hay películas' })).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
    expect(screen.getAllByRole('link', { name: 'Nueva película' }).length).toBeGreaterThan(0);
  });

  it('si falla la carga muestra el error y «Reintentar» vuelve a pedirla', async () => {
    routeFetch(fetchMock, { [listKey()]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });
    const user = userEvent.setup();
    renderList();

    expect(await screen.findByRole('heading', { name: 'No se pudieron cargar las películas' })).toBeInTheDocument();

    routeFetch(fetchMock, { [listKey()]: () => jsonResponse(makePage([dredd])) });
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByRole('row', { name: /^Dredd/ })).toBeInTheDocument();
  });

  it('«Editar» pasa al formulario la URL del listado (búsqueda y página) para volver al mismo sitio', async () => {
    routeFetch(fetchMock, {
      [searchKey('inter', 1)]: () => jsonResponse(makePage([interstellar], { page: 1, totalElements: 11, totalPages: 2 })),
    });
    const user = userEvent.setup();
    renderList('/admin/peliculas?q=inter&page=2');

    await user.click(await screen.findByRole('link', { name: 'Editar Interstellar' }));

    expect(screen.getByText('Pantalla de edición')).toBeInTheDocument();
    expect(screen.getByText('volver-a:/admin/peliculas?q=inter&page=2')).toBeInTheDocument();
  });
});

describe('AdminMoviesPage: búsqueda', () => {
  /** Espera tras la última tecla antes de buscar (`SEARCH_DEBOUNCE_MS` de `AdminMoviesPage`). */
  const DEBOUNCE_MS = 300;

  // El debounce va con reloj falso: el tiempo solo avanza cuando el test lo pide (`passTime`), así que el
  // resultado no depende de lo cargada que esté la máquina. Antes se esperaba el debounce REAL dentro de un
  // `waitFor` de 1 s y, con la suite completa en paralelo, a veces no daba tiempo (ver `test/fakeTimers.ts`).
  beforeEach(() => installManualTimers());
  afterEach(() => vi.useRealTimers());

  /** user-event sin pausas propias entre acciones (usarían el reloj falso y nadie las haría avanzar). */
  const setupUser = () => userEvent.setup({ delay: null });

  it('espera a que se deje de escribir (debounce): UNA petición por búsqueda, no una por letra', async () => {
    routeFetch(fetchMock, {
      [listKey()]: () => jsonResponse(makePage([interstellar, dredd])),
      [searchKey('inter')]: () => jsonResponse(makePage([interstellar])),
    });
    const user = setupUser();
    renderList();
    await screen.findByRole('row', { name: /^Dredd/ });
    await user.click(screen.getByRole('searchbox', { name: 'Buscar por título' }));

    // Teclea «inter» como una persona, con pausas más cortas que la espera: ninguna petición por letra.
    for (const key of ['i', 'n', 't', 'e', 'r']) {
      await user.keyboard(key);
      passTime(DEBOUNCE_MS - 50);
      expect(callsTo('/api/movies/search')).toHaveLength(0);
    }
    // Hasta que no pasan 300 ms sin teclear no se busca; entonces, UNA petición con el texto completo.
    passTime(49);
    expect(callsTo('/api/movies/search')).toHaveLength(0);
    passTime(1);
    expect(callsTo('/api/movies/search').map(([url]) => String(url))).toEqual([searchKey('inter').slice(4)]);

    // Estado final: los resultados sustituyen al listado anterior. Se espera a que desaparezca la fila vieja
    // y no al título de la tabla: mientras llega la respuesta se sigue viendo la página anterior (atenuada,
    // `aria-busy`) y el título ya dice «Resultados para...», así que no indica que hayan llegado.
    await waitFor(() => expect(screen.queryByRole('row', { name: /^Dredd/ })).not.toBeInTheDocument());
    expect(row('Interstellar')).toBeInTheDocument();
    expect(screen.getByRole('table', { name: 'Resultados para «inter», página 1 de 1' })).not.toHaveAttribute('aria-busy');
    expect(screen.getByText('busqueda:?q=inter')).toBeInTheDocument();
    expect(callsTo('/api/movies/search')).toHaveLength(1);
  });

  it('cancela la búsqueda anterior si cambia el texto antes de que responda (AbortController)', async () => {
    routeFetch(fetchMock, {
      [listKey()]: () => jsonResponse(makePage([dredd])),
      [searchKey('a')]: () => new Promise<Response>(() => {}), // no responde nunca
      [searchKey('ab')]: () => jsonResponse(makePage([interstellar])),
    });
    const user = setupUser();
    renderList();
    await screen.findByRole('row', { name: /^Dredd/ });
    const input = screen.getByRole('searchbox', { name: 'Buscar por título' });

    await user.type(input, 'a');
    passTime(DEBOUNCE_MS);
    expect(callsTo('/api/movies/search')).toHaveLength(1);
    await user.type(input, 'b');
    passTime(DEBOUNCE_MS);

    expect(await screen.findByRole('row', { name: /^Interstellar/ })).toBeInTheDocument();
    expect(callsTo('/api/movies/search')).toHaveLength(2);
    const [, firstInit] = callsTo('/api/movies/search')[0];
    expect(firstInit?.signal?.aborted).toBe(true);
  });

  it('una búsqueda sin coincidencias lo dice y «Borrar búsqueda» vuelve al catálogo completo', async () => {
    routeFetch(fetchMock, {
      [listKey()]: () => jsonResponse(makePage([dredd])),
      [searchKey('zzz')]: () => jsonResponse(makePage([], { totalPages: 0 })),
    });
    const user = setupUser();
    renderList('/admin/peliculas?q=zzz');

    expect(await screen.findByRole('heading', { name: 'Sin resultados para «zzz»' })).toBeInTheDocument();
    // Borrar la búsqueda muestra las películas, no "todo el catálogo" (las series están en otra pestaña).
    expect(
      screen.getByText('Prueba con otra parte del título o borra la búsqueda para ver todas las películas.'),
    ).toBeInTheDocument();
    // El campo refleja la búsqueda de la URL.
    expect(screen.getByRole('searchbox', { name: 'Buscar por título' })).toHaveValue('zzz');

    await user.click(screen.getByRole('button', { name: 'Borrar búsqueda' }));

    expect(await screen.findByRole('row', { name: /^Dredd/ })).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: 'Buscar por título' })).toHaveValue('');
    expect(screen.getByText('busqueda:')).toBeInTheDocument();
  });
});

describe('AdminMoviesPage: paginación', () => {
  it('«Siguiente» y «Anterior» cambian de página (en la URL) y anuncian «Página X de Y»', async () => {
    const pageOne = Array.from({ length: 10 }, (_, index) => makeMovie({ id: index + 1, title: `Título ${letter(index)}` }));
    routeFetch(fetchMock, {
      [listKey(0)]: () => jsonResponse(makePage(pageOne, { totalElements: 12, totalPages: 2, hasNext: true })),
      [listKey(1)]: () =>
        jsonResponse(makePage([dredd, interstellar], { page: 1, totalElements: 12, totalPages: 2, hasPrevious: true })),
    });
    const user = userEvent.setup();
    renderList();
    await screen.findByRole('row', { name: /^Título A/ });

    const previous = screen.getByRole('button', { name: 'Anterior' });
    // En la primera página «Anterior» no está disponible, pero conserva el foco (aria-disabled, no disabled).
    expect(previous).toHaveAttribute('aria-disabled', 'true');
    await user.click(previous);
    expect(callsTo('/api/movies?page=1')).toHaveLength(0);

    await user.click(screen.getByRole('button', { name: 'Siguiente' }));

    expect(await screen.findByRole('row', { name: /^Dredd/ })).toBeInTheDocument();
    expect(screen.getByText('busqueda:?page=2')).toBeInTheDocument();
    expect(screen.getByText('Página 2 de 2')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Siguiente' })).toHaveAttribute('aria-disabled', 'true');

    await user.click(screen.getByRole('button', { name: 'Anterior' }));
    expect(await screen.findByRole('row', { name: /^Título A/ })).toBeInTheDocument();
    expect(screen.getByText('busqueda:')).toBeInTheDocument();
  });

  it('abre directamente la búsqueda y la página de la URL (?q=&page=)', async () => {
    routeFetch(fetchMock, {
      [searchKey('blade', 1)]: () =>
        jsonResponse(makePage([makeMovie({ id: 3, title: 'Blade Runner' })], { page: 1, totalElements: 11, totalPages: 2 })),
    });
    renderList('/admin/peliculas?q=blade&page=2');

    expect(await screen.findByRole('row', { name: /^Blade Runner/ })).toBeInTheDocument();
    expect(screen.getByRole('searchbox', { name: 'Buscar por título' })).toHaveValue('blade');
    expect(screen.getByText('Página 2 de 2')).toBeInTheDocument();
  });

  it('una página fuera de rango (?page=9) lleva a la última que existe', async () => {
    routeFetch(fetchMock, {
      [listKey(8)]: () => jsonResponse(makePage([], { page: 8, totalElements: 12, totalPages: 2 })),
      [listKey(1)]: () => jsonResponse(makePage([dredd], { page: 1, totalElements: 12, totalPages: 2 })),
    });
    renderList('/admin/peliculas?page=9');

    expect(await screen.findByRole('row', { name: /^Dredd/ })).toBeInTheDocument();
    expect(screen.getByText('busqueda:?page=2')).toBeInTheDocument();
  });
});

describe('AdminMoviesPage: borrar', () => {
  /** Lista con Interstellar y Dredd; tras borrar, el servidor devuelve solo Dredd. */
  function routesWithDelete(deleteHandler: () => Response) {
    let deleted = false;
    routeFetch(fetchMock, {
      [listKey()]: () => jsonResponse(makePage(deleted ? [dredd] : [interstellar, dredd])),
      'DELETE /api/movies/7': () => {
        deleted = true;
        return deleteHandler();
      },
    });
  }

  it('pide confirmación con el título y el aviso de que se quita de todas las listas; «Cancelar» no borra', async () => {
    routesWithDelete(() => noContentResponse());
    const user = userEvent.setup();
    renderList();

    await user.click(await screen.findByRole('button', { name: 'Borrar Interstellar' }));

    const dialog = screen.getByRole('alertdialog', { name: '¿Borrar «Interstellar»?' });
    expect(dialog).toHaveAccessibleDescription(
      'También se quitará de las listas de todos los usuarios. Esta acción no se puede deshacer.',
    );
    // El foco empieza en la opción segura.
    expect(within(dialog).getByRole('button', { name: 'Cancelar' })).toHaveFocus();

    await user.click(within(dialog).getByRole('button', { name: 'Cancelar' }));

    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
    expect(callsTo('/api/movies/7')).toHaveLength(0);
    expect(row('Interstellar')).toBeInTheDocument();
  });

  it('al confirmar espera al servidor (no es optimista), avisa y recarga la página actual', async () => {
    let resolveDelete: (response: Response) => void = () => {};
    let deleted = false;
    routeFetch(fetchMock, {
      [listKey()]: () => jsonResponse(makePage(deleted ? [dredd] : [interstellar, dredd])),
      'DELETE /api/movies/7': () =>
        new Promise<Response>((resolve) => {
          resolveDelete = (response) => {
            deleted = true;
            resolve(response);
          };
        }),
    });
    const user = userEvent.setup();
    renderList();

    await user.click(await screen.findByRole('button', { name: 'Borrar Interstellar' }));
    await user.click(screen.getByRole('button', { name: 'Sí, borrar película' }));

    // Mientras el servidor no responde, la película sigue ahí y el diálogo no se puede cerrar.
    expect(screen.getByRole('button', { name: 'Procesando...' })).toBeDisabled();
    expect(row('Interstellar')).toBeInTheDocument();

    resolveDelete(noContentResponse());

    await expectToastOnPage('«Interstellar» se ha borrado del catálogo.');
    await waitFor(() => expect(screen.queryByRole('row', { name: /^Interstellar/ })).not.toBeInTheDocument());
    expect(callsTo(listKey().slice(4))).toHaveLength(2);
    // La fila (y el botón que tenía el foco) ya no existe: el foco pasa al título de la sección.
    expect(screen.getByRole('heading', { level: 2, name: 'Películas' })).toHaveFocus();
  });

  it('404 al borrar: ya estaba borrada; se informa (sin error) y se recarga', async () => {
    routesWithDelete(() => errorResponse(404, 'RESOURCE_NOT_FOUND', 'Película no encontrada'));
    const user = userEvent.setup();
    renderList();

    await user.click(await screen.findByRole('button', { name: 'Borrar Interstellar' }));
    await user.click(screen.getByRole('button', { name: 'Sí, borrar película' }));

    await expectToastOnPage('«Interstellar» ya no estaba en el catálogo. Se ha actualizado el listado.');
    await waitFor(() => expect(screen.queryByRole('row', { name: /^Interstellar/ })).not.toBeInTheDocument());
  });

  it('otro error (p. ej. 500): avisa del fallo y la película sigue en la lista', async () => {
    routeFetch(fetchMock, {
      [listKey()]: () => jsonResponse(makePage([interstellar, dredd])),
      'DELETE /api/movies/7': () => errorResponse(500, 'INTERNAL_ERROR', 'boom'),
    });
    const user = userEvent.setup();
    renderList();

    await user.click(await screen.findByRole('button', { name: 'Borrar Interstellar' }));
    await user.click(screen.getByRole('button', { name: 'Sí, borrar película' }));

    await expectToastOnPage(/El servidor ha tenido un problema/);
    expect(await screen.findByRole('row', { name: /^Interstellar/ })).toBeInTheDocument();
  });

  it('si borra la única película de una página que no es la primera, retrocede a la anterior', async () => {
    const pageOne: Movie[] = Array.from({ length: 10 }, (_, index) => makeMovie({ id: 100 + index, title: `Otra ${letter(index)}` }));
    let deleted = false;
    routeFetch(fetchMock, {
      [listKey(1)]: () =>
        jsonResponse(
          deleted
            ? makePage([], { page: 1, totalElements: 10, totalPages: 1 })
            : makePage([interstellar], { page: 1, totalElements: 11, totalPages: 2 }),
        ),
      [listKey(0)]: () => jsonResponse(makePage(pageOne, { totalElements: 10, totalPages: 1 })),
      'DELETE /api/movies/7': () => {
        deleted = true;
        return noContentResponse();
      },
    });
    const user = userEvent.setup();
    renderList('/admin/peliculas?page=2');

    await user.click(await screen.findByRole('button', { name: 'Borrar Interstellar' }));
    await user.click(screen.getByRole('button', { name: 'Sí, borrar película' }));

    expect(await screen.findByRole('row', { name: /^Otra A/ })).toBeInTheDocument();
    expect(screen.getByText('busqueda:')).toBeInTheDocument();
    expect(screen.getByText('Página 1 de 1')).toBeInTheDocument();
  });
});
