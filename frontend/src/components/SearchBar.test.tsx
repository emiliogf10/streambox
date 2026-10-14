/**
 * Tests de `SearchBar`, el buscador de películas y series con el patrón ARIA
 * "combobox + listbox" con opciones agrupadas.
 *
 * Protegen: la búsqueda con retardo (una petición por pausa y por tipo, no por
 * letra) y cancelable (las DOS peticiones), los roles ARIA correctos
 * (`combobox`, `listbox`, `group`, `option`, `aria-activedescendant`), el manejo
 * con teclado (↑/↓ circular por todos los resultados, de un grupo al otro,
 * Intro, Escape) con el foco SIEMPRE en el campo, qué pasa al elegir (cada
 * resultado lleva a su página: `/peliculas/:id` o `/series/:id`; las opciones son
 * enlaces reales fuera del orden de tabulación), los estados "sin resultados" y
 * "error", y el fallo de solo una de las dos búsquedas.
 *
 * `fetch` está simulado y el retardo de la búsqueda usa temporizadores falsos
 * (ningún test espera tiempo real).
 */
import { act, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useNavigationType } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FavoritesProvider } from '../context/FavoritesContext';
import { installManualTimers, passTime } from '../test/fakeTimers';
import {
  errorResponse,
  jsonResponse,
  makeMovie,
  makePage,
  makeSeries,
  renderWithProviders,
  routeFetch,
} from '../test/helpers';
import { SearchBar } from './SearchBar';

const fetchMock = vi.fn<typeof fetch>();

const matrix = makeMovie({ id: 1, title: 'Matrix', releaseYear: 1999 });
const matrixReloaded = makeMovie({ id: 2, title: 'Matrix Reloaded', releaseYear: 2003 });
const matrixRev = makeMovie({ id: 3, title: 'Matrix Revolutions', releaseYear: 2003 });
// Misma id que una película a propósito: películas y series son listas distintas y no deben confundirse.
const matrixSeries = makeSeries({ id: 1, title: 'Matrix: la serie', releaseYear: 2021, endYear: null, seasonCount: 2 });

const SEARCH_URL = 'GET /api/movies/search?title=matrix&size=10&sort=title';
const SERIES_SEARCH_URL = 'GET /api/series/search?title=matrix&size=10&sort=title';

/** Etiqueta del campo: ahora busca en los dos tipos. */
const LABEL = 'Buscar películas y series por título';

type Handler = () => Response | Promise<Response>;

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  // Reloj falso manual (y el puente que necesita Testing Library): ver `test/fakeTimers.ts`.
  installManualTimers();
});

afterEach(() => {
  vi.useRealTimers();
});

/**
 * Monta el buscador dentro de la lista de favoritos, como en la barra real (`AppShell`).
 * Por defecto la búsqueda de películas devuelve las tres «Matrix» y la de series, nada.
 * `route` es la página en la que está abierto el buscador (por defecto, la portada).
 */
function setup(
  searchResponse: Handler = () => jsonResponse(makePage([matrix, matrixReloaded, matrixRev])),
  seriesResponse: Handler = () => jsonResponse(makePage([])),
  route = '/',
) {
  routeFetch(fetchMock, {
    'GET /api/users/me/favorites': () => jsonResponse([]),
    [SEARCH_URL]: searchResponse,
    [SERIES_SEARCH_URL]: seriesResponse,
  });
  // `delay: null`: user-event no usa temporizadores propios (estarían falseados).
  const user = userEvent.setup({ delay: null });
  renderWithProviders(
    <FavoritesProvider>
      <SearchBar />
      <NavigationTypeProbe />
    </FavoritesProvider>,
    { session: true, route },
  );
  return { user, input: screen.getByRole('combobox', { name: LABEL }) };
}

/** Escribe cómo se llegó a la ruta actual (`PUSH` añade una entrada al historial; `REPLACE`, no). */
function NavigationTypeProbe() {
  return <p>{`navegacion:${useNavigationType()}`}</p>;
}

/** Región `aria-live` del propio buscador (la zona de avisos de la app también es un `status`). */
function announced() {
  return within(screen.getByRole('search')).getByRole('status');
}

/** Deja pasar el retardo de la búsqueda (300 ms). */
function passDebounce(ms = 300) {
  passTime(ms);
}

/** Escribe "matrix", espera el retardo y a que aparezca la lista de resultados. */
async function searchMatrix(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByRole('combobox'), 'matrix');
  passDebounce();
  return screen.findByRole('listbox', { name: 'Resultados de la búsqueda' });
}

/** Peticiones de búsqueda lanzadas hasta ahora (de los dos tipos). */
function searchCalls() {
  return fetchMock.mock.calls.filter(([url]) => String(url).includes('/search'));
}

describe('SearchBar: petición', () => {
  it('es un combobox con etiqueta, cerrado y sin lista al empezar', () => {
    const { input } = setup();

    expect(input).toHaveAttribute('aria-expanded', 'false');
    expect(input).toHaveAttribute('aria-autocomplete', 'list');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    expect(screen.getByRole('search')).toBeInTheDocument();
  });

  it('el contorno del campo usa `field-border` (≥3:1, WCAG 1.4.11) y no el borde casi invisible de `line`', () => {
    const { input } = setup();

    const box = input.parentElement as HTMLElement;
    expect(box).toHaveClass('border-field-border');
    expect(box).not.toHaveClass('border-line');
  });

  it('espera 300 ms tras la última tecla y entonces hace UNA petición por tipo (películas y series) con el texto completo', async () => {
    const { user } = setup();

    await user.type(screen.getByRole('combobox'), 'matrix');
    passDebounce(299);
    expect(searchCalls()).toHaveLength(0);

    passDebounce(1);
    await screen.findByRole('listbox');
    expect(searchCalls().map(([url]) => String(url))).toEqual([
      '/api/movies/search?title=matrix&size=10&sort=title',
      '/api/series/search?title=matrix&size=10&sort=title',
    ]);
  });

  it('un texto de solo espacios no busca nada', async () => {
    const { user } = setup();

    await user.type(screen.getByRole('combobox'), '   ');
    passDebounce(1000);

    expect(searchCalls()).toHaveLength(0);
  });

  it('seguir escribiendo cancela LAS DOS peticiones anteriores para que una respuesta lenta no pise a la nueva', async () => {
    const { user } = setup();
    // Todas las búsquedas se quedan sin respuesta: solo importa si se cancelan.
    const never = () => new Promise<Response>(() => {});
    routeFetch(fetchMock, {
      'GET /api/users/me/favorites': () => jsonResponse([]),
      'GET /api/movies/search?title=m&size=10&sort=title': never,
      'GET /api/series/search?title=m&size=10&sort=title': never,
      'GET /api/movies/search?title=ma&size=10&sort=title': never,
      'GET /api/series/search?title=ma&size=10&sort=title': never,
    });

    await user.type(screen.getByRole('combobox'), 'm');
    passDebounce();
    const firstSignals = searchCalls()
      .filter(([url]) => String(url).includes('title=m&'))
      .map(([, init]) => init?.signal);
    expect(firstSignals).toHaveLength(2);
    expect(firstSignals.map((signal) => signal?.aborted)).toEqual([false, false]);

    await user.type(screen.getByRole('combobox'), 'a');

    expect(firstSignals.map((signal) => signal?.aborted)).toEqual([true, true]);
  });

  it('no pinta nada hasta que han respondido las dos: la lista no crece bajo el puntero', async () => {
    let resolveSeries!: (response: Response) => void;
    const { user } = setup(undefined, () => new Promise<Response>((resolve) => (resolveSeries = resolve)));

    await user.type(screen.getByRole('combobox'), 'matrix');
    passDebounce();
    expect(searchCalls()).toHaveLength(2);
    // Deja que se resuelvan todas las promesas pendientes: las películas llegan, las series no.
    await act(async () => {});
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    expect(announced()).toHaveTextContent('Buscando...');

    resolveSeries(jsonResponse(makePage([matrixSeries])));

    const list = await screen.findByRole('listbox');
    expect(within(list).getAllByRole('option')).toHaveLength(4);
  });
});

describe('SearchBar: resultados y roles ARIA', () => {
  it('los resultados forman un listbox de options y el combobox queda expandido', async () => {
    const { user, input } = setup();

    const list = await searchMatrix(user);

    expect(input).toHaveAttribute('aria-expanded', 'true');
    expect(input).toHaveAttribute('aria-controls', list.id);
    const options = within(list).getAllByRole('option');
    expect(options).toHaveLength(3);
    expect(options[0]).toHaveTextContent('Matrix1999');
    expect(options[1]).toHaveTextContent('Matrix Reloaded2003');
    options.forEach((option) => expect(option).toHaveAttribute('aria-selected', 'false'));
  });

  it('agrupa los resultados en «Películas» y «Series», cada grupo con sus opciones y en ese orden', async () => {
    const { user } = setup(undefined, () => jsonResponse(makePage([matrixSeries])));

    const list = await searchMatrix(user);

    expect(within(list).getAllByRole('group')).toHaveLength(2);
    const movies = within(list).getByRole('group', { name: 'Películas' });
    const series = within(list).getByRole('group', { name: 'Series' });
    expect(within(movies).getAllByRole('option')).toHaveLength(3);
    expect(within(series).getAllByRole('option')).toHaveLength(1);
    // La serie lleva sus años y temporadas (no la duración de una película).
    expect(within(series).getByRole('option')).toHaveTextContent('Matrix: la serie2021– · 2 temporadas');
    // Los encabezados nombran los grupos, pero no son opciones: no se recorren con las flechas.
    expect(within(list).getAllByRole('option')).toHaveLength(4);
    expect(movies.compareDocumentPosition(series) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('si solo hay series, solo aparece ese grupo', async () => {
    const { user } = setup(
      () => jsonResponse(makePage([])),
      () => jsonResponse(makePage([matrixSeries])),
    );

    const list = await searchMatrix(user);

    expect(within(list).queryByRole('group', { name: 'Películas' })).not.toBeInTheDocument();
    expect(within(list).getByRole('group', { name: 'Series' })).toBeInTheDocument();
    expect(announced()).toHaveTextContent(/^1 resultado\. 1 serie\./);
  });

  it('anuncia a los lectores de pantalla cuántos resultados hay y cómo se reparten (región role="status")', async () => {
    const { user } = setup(undefined, () => jsonResponse(makePage([matrixSeries])));

    await searchMatrix(user);

    expect(announced()).toHaveTextContent(/^4 resultados\. 3 películas y 1 serie\. Usa las flechas/);
  });

  it('sin coincidencias en ninguno de los dos lo dice, tanto en pantalla como en la región anunciada', async () => {
    const { user, input } = setup(() => jsonResponse(makePage([])));

    await user.type(input, 'matrix');
    passDebounce();

    expect(await screen.findByText('Sin resultados para «matrix».')).toBeInTheDocument();
    expect(announced()).toHaveTextContent('Sin resultados para matrix.');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    expect(input).toHaveAttribute('aria-expanded', 'false');
  });

  it('si fallan las dos búsquedas muestra el error del servidor en lugar de quedarse muda', async () => {
    const tooLong = () => errorResponse(400, 'VALIDATION_ERROR', 'El título es demasiado largo');
    const { user, input } = setup(tooLong, tooLong);

    await user.type(input, 'matrix');
    passDebounce();

    // Aparece dos veces: en el desplegable visible y en la región anunciada a lectores de pantalla.
    expect(await screen.findAllByText('El título es demasiado largo')).toHaveLength(2);
    expect(announced()).toHaveTextContent('El título es demasiado largo');
  });
});

describe('SearchBar: fallo de una sola de las dos búsquedas', () => {
  const serverError = () => errorResponse(500, 'INTERNAL_ERROR', 'boom');

  it('si fallan las series, enseña las películas y avisa de que faltan las series', async () => {
    const { user } = setup(undefined, serverError);

    const list = await searchMatrix(user);

    expect(within(list).getAllByRole('option')).toHaveLength(3);
    const notice = screen.getByRole('note');
    expect(notice).toHaveTextContent('No se pudo buscar en series. El servidor ha tenido un problema.');
    expect(announced()).toHaveTextContent(/^3 resultados\. .*No se pudo buscar en series\./);
  });

  it('si fallan las películas, enseña las series y avisa de que faltan las películas', async () => {
    const { user } = setup(serverError, () => jsonResponse(makePage([matrixSeries])));

    const list = await searchMatrix(user);

    expect(within(list).getByRole('group', { name: 'Series' })).toBeInTheDocument();
    expect(screen.getByRole('note')).toHaveTextContent(/^No se pudo buscar en películas\./);
  });

  it('si la que responde no tiene nada, NO dice solo «sin resultados»: dice dónde no hay y qué falló', async () => {
    const { user, input } = setup(() => jsonResponse(makePage([])), serverError);

    await user.type(input, 'matrix');
    passDebounce();

    expect(
      await screen.findByText(/^Sin resultados en películas para «matrix»\. No se pudo buscar en series\./),
    ).toBeInTheDocument();
    expect(screen.queryByText('Sin resultados para «matrix».')).not.toBeInTheDocument();
  });
});

describe('SearchBar: teclado', () => {
  it('↓ recorre las opciones (circular), marca aria-selected y aria-activedescendant, y el foco no sale del campo', async () => {
    const { user, input } = setup();
    const list = await searchMatrix(user);
    const options = within(list).getAllByRole('option');

    await user.keyboard('{ArrowDown}');
    expect(options[0]).toHaveAttribute('aria-selected', 'true');
    expect(input).toHaveAttribute('aria-activedescendant', options[0].id);

    await user.keyboard('{ArrowDown}{ArrowDown}');
    expect(options[2]).toHaveAttribute('aria-selected', 'true');

    await user.keyboard('{ArrowDown}'); // da la vuelta
    expect(options[0]).toHaveAttribute('aria-selected', 'true');
    expect(options[2]).toHaveAttribute('aria-selected', 'false');
    expect(input).toHaveFocus();
    expect(Element.prototype.scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' });
  });

  it('↓ pasa de la última película a la primera serie y, tras la última serie, vuelve a la primera película', async () => {
    const { user, input } = setup(undefined, () => jsonResponse(makePage([matrixSeries])));
    const list = await searchMatrix(user);
    const seriesOption = within(within(list).getByRole('group', { name: 'Series' })).getByRole('option');
    const firstMovie = within(within(list).getByRole('group', { name: 'Películas' })).getAllByRole('option')[0];

    await user.keyboard('{ArrowDown}{ArrowDown}{ArrowDown}{ArrowDown}');
    expect(seriesOption).toHaveAttribute('aria-selected', 'true');
    expect(input).toHaveAttribute('aria-activedescendant', seriesOption.id);

    await user.keyboard('{ArrowDown}');
    expect(firstMovie).toHaveAttribute('aria-selected', 'true');

    await user.keyboard('{ArrowUp}'); // hacia atrás, de la primera película a la última serie
    expect(seriesOption).toHaveAttribute('aria-selected', 'true');
  });

  it('↑ sin selección salta a la última opción y recorre hacia atrás', async () => {
    const { user, input } = setup();
    const list = await searchMatrix(user);
    const options = within(list).getAllByRole('option');

    await user.keyboard('{ArrowUp}');
    expect(options[2]).toHaveAttribute('aria-selected', 'true');
    expect(input).toHaveAttribute('aria-activedescendant', options[2].id);

    await user.keyboard('{ArrowUp}');
    expect(options[1]).toHaveAttribute('aria-selected', 'true');
  });

  it('Intro con una opción resaltada lleva a la página de ESA película y cierra y vacía el buscador', async () => {
    const { user, input } = setup();
    await searchMatrix(user);

    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}');

    expect(screen.getByText(`ruta:/peliculas/${matrixReloaded.id}`)).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(input).toHaveValue('');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('Intro sobre una SERIE lleva a su página (sin diálogo) y cierra y vacía el buscador', async () => {
    const { user, input } = setup(undefined, () => jsonResponse(makePage([matrixSeries])));
    await searchMatrix(user);

    await user.keyboard('{ArrowUp}{Enter}'); // ↑ sin selección = la última, que es la serie

    expect(screen.getByText('ruta:/series/1')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(input).toHaveValue('');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('Intro sin ninguna resaltada abre la primera', async () => {
    const { user } = setup();
    await searchMatrix(user);

    await user.keyboard('{Enter}');

    expect(screen.getByText(`ruta:/peliculas/${matrix.id}`)).toBeInTheDocument();
  });

  it('Intro sobre la película que ya está abierta no añade otra entrada al historial (sustituye la actual)', async () => {
    const { user } = setup(undefined, undefined, `/peliculas/${matrix.id}`);
    await searchMatrix(user);

    await user.keyboard('{Enter}'); // la primera, «Matrix»: la misma página

    expect(screen.getByText(`ruta:/peliculas/${matrix.id}`)).toBeInTheDocument();
    expect(screen.getByText('navegacion:REPLACE')).toBeInTheDocument();
  });

  it('Intro sobre OTRA película desde la página de una película sí añade una entrada (Atrás vuelve a la anterior)', async () => {
    const { user } = setup(undefined, undefined, `/peliculas/${matrix.id}`);
    await searchMatrix(user);

    await user.keyboard('{ArrowDown}{ArrowDown}{Enter}');

    expect(screen.getByText(`ruta:/peliculas/${matrixReloaded.id}`)).toBeInTheDocument();
    expect(screen.getByText('navegacion:PUSH')).toBeInTheDocument();
  });

  it('las opciones son enlaces a la página de cada título, fuera del orden de tabulación', async () => {
    const { user } = setup(undefined, () => jsonResponse(makePage([matrixSeries])));
    const list = await searchMatrix(user);

    // `role="option"` sobre un `<a href>`: para el lector de pantalla es una opción; con el ratón, un enlace.
    const movie = within(list).getByRole('option', { name: /Matrix Reloaded/ });
    expect(movie.tagName).toBe('A');
    expect(movie).toHaveAttribute('href', `/peliculas/${matrixReloaded.id}`);
    expect(movie).toHaveAttribute('tabindex', '-1');
    // La serie tiene el mismo id que «Matrix» (1): cada tipo enlaza a SU página.
    expect(within(list).getByRole('option', { name: /Matrix: la serie/ })).toHaveAttribute('href', '/series/1');
    expect(within(list).getByRole('option', { name: /^Matrix 1999$/ })).toHaveAttribute('href', '/peliculas/1');
  });

  it('Escape cierra la lista y vacía el campo', async () => {
    const { user, input } = setup();
    await searchMatrix(user);

    await user.keyboard('{Escape}');

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    expect(input).toHaveValue('');
    expect(input).toHaveAttribute('aria-expanded', 'false');
    expect(input).toHaveFocus();
  });

  it('Escape con el buscador ya cerrado y vacío no se consume (otros componentes pueden usarlo)', async () => {
    const { user } = setup();
    const prevented: boolean[] = [];
    const record = (event: KeyboardEvent) => prevented.push(event.defaultPrevented);
    // Escucha en `document`: ahí llega el evento DESPUÉS de que React lo haya gestionado.
    document.addEventListener('keydown', record);

    await user.click(screen.getByRole('combobox')); // al enfocar se "abre" aunque no haya nada
    await user.keyboard('{Escape}'); // 1.º: cierra el desplegable y se consume
    await user.keyboard('{Escape}'); // 2.º: ya no hay nada que cerrar
    document.removeEventListener('keydown', record);

    expect(prevented).toEqual([true, false]);
  });
});

describe('SearchBar: ratón', () => {
  it('pulsar una película lleva a su página y cierra y vacía el buscador', async () => {
    const { user, input } = setup();
    const list = await searchMatrix(user);

    await user.click(within(list).getByRole('option', { name: /Matrix Revolutions/ }));

    expect(screen.getByText(`ruta:/peliculas/${matrixRev.id}`)).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(input).toHaveValue('');
    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
  });

  it('Ctrl+clic (abrir en otra pestaña) no navega aquí y deja el buscador abierto para elegir otro', async () => {
    const { user, input } = setup();
    const list = await searchMatrix(user);

    await user.keyboard('{Control>}');
    await user.click(within(list).getByRole('option', { name: /Matrix Revolutions/ }));
    await user.keyboard('{/Control}');

    expect(screen.getByText('ruta:/')).toBeInTheDocument();
    expect(input).toHaveValue('matrix');
    expect(screen.getByRole('listbox')).toBeInTheDocument();
  });

  it('pulsar una serie lleva a su página', async () => {
    const { user } = setup(undefined, () => jsonResponse(makePage([matrixSeries])));
    const list = await searchMatrix(user);

    await user.click(within(list).getByRole('option', { name: /Matrix: la serie/ }));

    expect(screen.getByText('ruta:/series/1')).toBeInTheDocument();
  });

  it('pulsar fuera del buscador cierra la lista y vacía el campo', async () => {
    const { user, input } = setup();
    await searchMatrix(user);

    await user.click(document.body);

    expect(screen.queryByRole('listbox')).not.toBeInTheDocument();
    expect(input).toHaveValue('');
  });
});

describe('SearchBar: títulos parecidos (approximate)', () => {
  // Búsqueda con una errata: ningún título CONTIENE «interstelar», pero el servidor devuelve los parecidos
  // (`approximate: true`, solo en PostgreSQL). Cada tipo decide por su cuenta si es aproximado.
  const QUERY = 'interstelar';
  const MOVIES_URL = `GET /api/movies/search?title=${QUERY}&size=10&sort=title`;
  const SERIES_URL = `GET /api/series/search?title=${QUERY}&size=10&sort=title`;
  const interstellar = makeMovie({ id: 7, title: 'Interstellar', releaseYear: 2014 });
  const stellarSeries = makeSeries({ id: 8, title: 'Interestelar: la serie', releaseYear: 2020, endYear: 2021 });
  // Serie que SÍ contiene el texto (coincidencia real), para el caso mixto.
  const exactSeries = makeSeries({ id: 9, title: 'Viaje interstelar', releaseYear: 2019, endYear: null });

  const approx = <T,>(content: T[]) => () => jsonResponse(makePage(content, { approximate: true }));
  const exact = <T,>(content: T[]) => () => jsonResponse(makePage(content));

  /** Monta el buscador, escribe la errata y deja pasar el retardo. */
  async function search(moviesResponse: Handler, seriesResponse: Handler) {
    routeFetch(fetchMock, {
      'GET /api/users/me/favorites': () => jsonResponse([]),
      [MOVIES_URL]: moviesResponse,
      [SERIES_URL]: seriesResponse,
    });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(
      <FavoritesProvider>
        <SearchBar />
      </FavoritesProvider>,
      { session: true },
    );
    await user.type(screen.getByRole('combobox', { name: LABEL }), QUERY);
    passDebounce();
  }

  it('si los dos grupos son parecidos, dice que NINGÚN título lo contiene antes de la lista y lo anuncia', async () => {
    await search(approx([interstellar]), approx([stellarSeries]));

    const list = await screen.findByRole('listbox', { name: 'Resultados de la búsqueda' });
    // El aviso describe la lista y va antes que ella.
    expect(list).toHaveAccessibleDescription('No hay títulos que contengan «interstelar». Quizá buscabas:');
    const notice = screen.getByText('No hay títulos que contengan «interstelar». Quizá buscabas:');
    expect(notice.compareDocumentPosition(list) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    // Los encabezados (nombres de los grupos) también lo dicen.
    expect(within(list).getByRole('group', { name: 'Películas con título parecido' })).toBeInTheDocument();
    expect(within(list).getByRole('group', { name: 'Series con título parecido' })).toBeInTheDocument();
    expect(announced()).toHaveTextContent(
      /^No hay títulos que contengan interstelar\. 2 títulos parecidos: 1 película y 1 serie\. Usa las flechas/,
    );
  });

  it('si un grupo es parecido y el otro está vacío, sigue siendo verdad que ningún título lo contiene', async () => {
    await search(approx([interstellar]), exact([]));

    const list = await screen.findByRole('listbox', { name: 'Resultados de la búsqueda' });
    expect(list).toHaveAccessibleDescription('No hay títulos que contengan «interstelar». Quizá buscabas:');
    expect(within(list).getByRole('group', { name: 'Películas con título parecido' })).toBeInTheDocument();
    expect(announced()).toHaveTextContent(/^No hay títulos que contengan interstelar\. 1 título parecido: 1 película\./);
  });

  it('mixto: películas parecidas y series que SÍ lo contienen; el aviso habla solo de las películas', async () => {
    await search(approx([interstellar]), exact([exactSeries]));

    const list = await screen.findByRole('listbox', { name: 'Resultados de la búsqueda' });
    const text = 'Ninguna película contiene «interstelar»: las películas de la lista tienen un título parecido.';
    expect(list).toHaveAccessibleDescription(text);
    // «No hay títulos que contengan…» sería mentira: la serie sí lo contiene.
    expect(screen.queryByText(/No hay títulos/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Quizá buscabas/)).not.toBeInTheDocument();
    expect(within(list).getByRole('group', { name: 'Películas con título parecido' })).toBeInTheDocument();
    expect(within(list).getByRole('group', { name: 'Series' })).toBeInTheDocument();
    expect(announced()).toHaveTextContent(
      /^2 resultados\. 1 película y 1 serie\. Ninguna película contiene interstelar: las películas de la lista tienen un título parecido\. Usa las flechas/,
    );
  });

  it('mixto al revés: películas que lo contienen y series parecidas; el aviso habla solo de las series', async () => {
    const exactMovie = makeMovie({ id: 3, title: 'Interstelar 2', releaseYear: 2030 });
    await search(exact([exactMovie]), approx([stellarSeries]));

    const list = await screen.findByRole('listbox', { name: 'Resultados de la búsqueda' });
    expect(list).toHaveAccessibleDescription(
      'Ninguna serie contiene «interstelar»: las series de la lista tienen un título parecido.',
    );
    expect(within(list).getByRole('group', { name: 'Películas' })).toBeInTheDocument();
    expect(within(list).getByRole('group', { name: 'Series con título parecido' })).toBeInTheDocument();
  });

  it('si las películas son parecidas y las series fallan, no afirma nada de las series', async () => {
    await search(approx([interstellar]), () => errorResponse(500, 'INTERNAL_ERROR', 'boom'));

    const list = await screen.findByRole('listbox', { name: 'Resultados de la búsqueda' });
    expect(list).toHaveAccessibleDescription('Ninguna película contiene «interstelar». Quizá buscabas:');
    expect(screen.getByRole('note')).toHaveTextContent(/^No se pudo buscar en series\./);
    expect(announced()).toHaveTextContent(
      /^Ninguna película contiene interstelar\. 1 título parecido: 1 película\. .*No se pudo buscar en series\./,
    );
  });

  it('con coincidencias de verdad (approximate: false) no hay aviso ni encabezados de «parecido»', async () => {
    await search(exact([makeMovie({ id: 3, title: 'Interstelar 2' })]), exact([exactSeries]));

    const list = await screen.findByRole('listbox', { name: 'Resultados de la búsqueda' });
    expect(list).not.toHaveAttribute('aria-describedby');
    expect(screen.queryByText(/Quizá buscabas|título parecido|Ninguna/)).not.toBeInTheDocument();
    expect(within(list).getByRole('group', { name: 'Películas' })).toBeInTheDocument();
    expect(within(list).getByRole('group', { name: 'Series' })).toBeInTheDocument();
    expect(announced()).toHaveTextContent(/^2 resultados\. 1 película y 1 serie\. Usa las flechas/);
  });

  it('un «aproximado» sin títulos no sugiere nada: es «Sin resultados»', async () => {
    await search(approx([]), approx([]));

    expect(await screen.findByText('Sin resultados para «interstelar».')).toBeInTheDocument();
    expect(screen.queryByText(/Quizá buscabas/)).not.toBeInTheDocument();
  });
});

describe('SearchBar: texto que el servidor no admite (máx. 150, sin caracteres de control)', () => {
  /** Monta el buscador con las búsquedas de `routes` (además de la lista de favoritos). */
  function mount(routes: Record<string, Handler>) {
    routeFetch(fetchMock, { 'GET /api/users/me/favorites': () => jsonResponse([]), ...routes });
    const user = userEvent.setup({ delay: null });
    renderWithProviders(
      <FavoritesProvider>
        <SearchBar />
      </FavoritesProvider>,
      { session: true },
    );
    return { user, input: screen.getByRole('combobox', { name: LABEL }) };
  }

  it('el campo no admite más de 150 caracteres (`maxLength`, el límite del servidor)', () => {
    const { input } = setup();

    expect(input).toHaveAttribute('maxLength', '150');
  });

  it('un tabulador pegado se envía como un espacio: busca «Matrix Reloaded», no recibe un 400', async () => {
    const { user, input } = mount({
      'GET /api/movies/search?title=Matrix+Reloaded&size=10&sort=title': () => jsonResponse(makePage([matrixReloaded])),
      'GET /api/series/search?title=Matrix+Reloaded&size=10&sort=title': () => jsonResponse(makePage([])),
    });

    await user.click(input);
    await user.paste('Matrix\tReloaded');
    passDebounce();

    expect(await screen.findByRole('option', { name: /Matrix Reloaded/ })).toBeInTheDocument();
    const urls = searchCalls().map(([url]) => String(url));
    expect(urls).toEqual([
      '/api/movies/search?title=Matrix+Reloaded&size=10&sort=title',
      '/api/series/search?title=Matrix+Reloaded&size=10&sort=title',
    ]);
    // Ni el tabulador (%09) ni ningún otro carácter de control llega al servidor.
    expect(urls.some((url) => /%0[0-9A-F]|%1[0-9A-F]|%7F/i.test(url))).toBe(false);
  });

  it('un texto que solo tiene caracteres de control no busca nada', async () => {
    const { user, input } = mount({});

    await user.click(input);
    await user.paste('\t\t');
    passDebounce(1000);

    expect(searchCalls()).toHaveLength(0);
  });

  it('si aun así llega el 400 de validación, enseña el motivo del servidor junto al campo (no el genérico) y no cierra la sesión', async () => {
    const rejected = () =>
      errorResponse(400, 'VALIDATION_ERROR', 'Los datos proporcionados no son válidos', {
        validationErrors: { title: 'La búsqueda no puede superar los 150 caracteres' },
      });
    const { user, input } = mount({
      [SEARCH_URL]: rejected,
      [SERIES_SEARCH_URL]: rejected,
      'GET /api/movies/search?title=matri&size=10&sort=title': () => jsonResponse(makePage([matrix])),
      'GET /api/series/search?title=matri&size=10&sort=title': () => jsonResponse(makePage([])),
    });

    await user.type(input, 'matrix');
    passDebounce();

    const reason = 'La búsqueda no puede superar los 150 caracteres.';
    // En el panel visible bajo el campo y en la región anunciada.
    expect(await screen.findAllByText(reason)).toHaveLength(2);
    expect(announced()).toHaveTextContent(reason);
    expect(screen.queryByText('Los datos proporcionados no son válidos')).not.toBeInTheDocument();
    // El campo queda marcado como inválido y descrito por el motivo.
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription(reason);
    // Ni se cierra la sesión ni se intenta renovar: un 400 no es un 401.
    expect(fetchMock.mock.calls.some(([url]) => String(url) === '/api/auth/refresh')).toBe(false);

    // El buscador sigue funcionando: al corregir el texto, vuelve a buscar y deja de marcarse como inválido.
    await user.type(input, '{Backspace}');
    expect(input).not.toHaveAttribute('aria-invalid');
    passDebounce();
    expect(await screen.findByRole('option', { name: /Matrix/ })).toBeInTheDocument();
  });

  it('si solo una mitad recibe el 400, el aviso parcial también dice el motivo real', async () => {
    const { user, input } = mount({
      [SEARCH_URL]: () => jsonResponse(makePage([matrix])),
      [SERIES_SEARCH_URL]: () =>
        errorResponse(400, 'VALIDATION_ERROR', 'Los datos proporcionados no son válidos', {
          validationErrors: { title: 'La búsqueda no puede contener caracteres de control' },
        }),
    });

    await user.type(input, 'matrix');
    passDebounce();

    expect(
      await screen.findByText('No se pudo buscar en series. La búsqueda no puede contener caracteres de control.'),
    ).toBeInTheDocument();
  });
});
