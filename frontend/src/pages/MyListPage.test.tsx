/**
 * Tests de `MyListPage` («Mi lista») con la lista compartida real y `fetch` simulado.
 *
 * Protegen: las dos secciones «Películas» y «Series» con su encabezado, qué hace
 * cada tarjeta (película → diálogo; serie → su página), la sección vacía cuando
 * la otra tiene algo (con «Explorar...» solo si al otro lado hay algo que ver),
 * el estado vacío cuando no hay nada, el error con reintento
 * y «Vaciar lista» (confirmación que nombra lo que se borra, dos peticiones y el
 * fallo de solo una de ellas).
 */
import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FavoritesProvider } from '../context/FavoritesContext';
import {
  errorResponse,
  FAVORITE_SERIES,
  jsonResponse,
  makeMovie,
  makePage,
  makeSeries,
  noContentResponse,
  renderWithProviders,
  routeFetch,
} from '../test/helpers';
import type { Movie, Series } from '../lib/types';
import { MyListPage } from './MyListPage';

const fetchMock = vi.fn<typeof fetch>();

const MOVIES = 'GET /api/users/me/favorites';
const CLEAR_MOVIES = 'DELETE /api/users/me/favorites';
const CLEAR_SERIES = 'DELETE /api/users/me/favorites/series';

const dune = makeMovie({ id: 1, title: 'Dune', releaseYear: 2021 });
const arrival = makeMovie({ id: 2, title: 'Arrival', releaseYear: 2016 });
const dark = makeSeries({ id: 1, title: 'Dark', releaseYear: 2017, endYear: 2020, seasonCount: 3 });

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

/**
 * Lo que pregunta una sección vacía para saber si ofrecer «Explorar...»: una
 * página de tamaño 1 del catálogo (la misma consulta que la página de destino).
 */
const ANY_MOVIE = 'GET /api/movies?page=0&size=1&sort=createdAt&direction=desc';
const ANY_SERIES = 'GET /api/series?page=0&size=1&sort=createdAt&direction=desc';

/**
 * Monta «Mi lista» con las listas indicadas (y las rutas extra que necesite el test).
 * Por defecto el catálogo tiene películas y series visibles.
 */
function renderList(movies: Movie[], series: Series[], extra: Parameters<typeof routeFetch>[1] = {}) {
  routeFetch(fetchMock, {
    [MOVIES]: () => jsonResponse(movies),
    [FAVORITE_SERIES]: () => jsonResponse(series),
    [ANY_MOVIE]: () => jsonResponse(makePage([arrival])),
    [ANY_SERIES]: () => jsonResponse(makePage([dark])),
    ...extra,
  });
  const user = userEvent.setup();
  renderWithProviders(
    <FavoritesProvider>
      <MyListPage />
    </FavoritesProvider>,
    { session: true, route: '/favorites' },
  );
  return user;
}

const section = (name: string) => screen.getByRole('region', { name });

/** Respuesta que el test sirve a mano, para saber exactamente cuándo ha llegado. */
function deferredResponse() {
  let resolve!: (response: Response) => void;
  const promise = new Promise<Response>((res) => (resolve = res));
  return { promise, resolve };
}

/**
 * Sirve la respuesta y deja terminar todo lo que provoca (leer el cuerpo,
 * actualizar el estado y pintar) antes de seguir. Hace falta para comprobar una
 * AUSENCIA: sin esperar, «no hay enlace» se cumpliría también antes de que
 * llegara la respuesta. Una vuelta del bucle de eventos (`setTimeout 0`) dentro
 * de `act` basta: las promesas encadenadas se resuelven antes y `act` aplica los renders.
 */
async function answerAndSettle(answer: ReturnType<typeof deferredResponse>, response: Response) {
  await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => String(url).includes('size=1'))).toBe(true));
  await act(async () => {
    answer.resolve(response);
    await new Promise<void>((resolve) => setTimeout(resolve, 0));
  });
}

describe('MyListPage: secciones', () => {
  it('muestra «Películas» y «Series» como h2 bajo el único h1, cada una con sus tarjetas', async () => {
    renderList([dune, arrival], [dark]);

    await screen.findByRole('region', { name: 'Películas' });
    expect(screen.getAllByRole('heading', { level: 1 }).map((h) => h.textContent)).toEqual(['Mi lista']);
    expect(screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)).toEqual(['Películas', 'Series']);
    expect(within(section('Películas')).getAllByRole('button', { name: /\d{4} ·/ })).toHaveLength(2);
    expect(within(section('Series')).getByRole('link', { name: 'Dark 2017–2020 · 3 temporadas' })).toHaveAttribute(
      'href',
      '/series/1',
    );
  });

  it('una película abre su diálogo de detalles; una serie es un enlace (no abre diálogo)', async () => {
    const user = renderList([dune], [dark]);

    await user.click(await within(await screen.findByRole('region', { name: 'Películas' })).findByRole('button', { name: /^Dune/ }));

    expect(await screen.findByRole('dialog', { name: 'Dune' })).toBeInTheDocument();
    expect(within(section('Series')).queryByRole('button', { name: /^Dark/ })).not.toBeInTheDocument();
  });

  it('si solo hay películas, la sección «Series» lo dice y enlaza a /series', async () => {
    renderList([dune], []);

    const series = await screen.findByRole('region', { name: 'Series' });
    expect(within(series).getByText('Todavía no has guardado ninguna serie.')).toBeInTheDocument();
    expect(await within(series).findByRole('link', { name: 'Explorar series' })).toHaveAttribute('href', '/series');
  });

  it('si solo hay series, la sección «Películas» lo dice y enlaza a /peliculas', async () => {
    renderList([], [dark]);

    const movies = await screen.findByRole('region', { name: 'Películas' });
    expect(within(movies).getByText('Todavía no has guardado ninguna película.')).toBeInTheDocument();
    expect(await within(movies).findByRole('link', { name: 'Explorar películas' })).toHaveAttribute('href', '/peliculas');
  });

  it('si en el catálogo no hay ninguna serie visible, «Series» no ofrece «Explorar series» (llevaría a otra página vacía)', async () => {
    const answer = deferredResponse();
    renderList([dune], [], { [ANY_SERIES]: () => answer.promise });

    const series = await screen.findByRole('region', { name: 'Series' });
    expect(within(series).getByText('Todavía no has guardado ninguna serie.')).toBeInTheDocument();
    await answerAndSettle(answer, jsonResponse(makePage([])));

    expect(within(series).queryByRole('link')).not.toBeInTheDocument();
  });

  it('lo mismo con las películas: sin ninguna en el catálogo, «Películas» no ofrece «Explorar películas»', async () => {
    const answer = deferredResponse();
    renderList([], [dark], { [ANY_MOVIE]: () => answer.promise });

    const movies = await screen.findByRole('region', { name: 'Películas' });
    expect(within(movies).getByText('Todavía no has guardado ninguna película.')).toBeInTheDocument();
    await answerAndSettle(answer, jsonResponse(makePage([])));

    expect(within(movies).queryByRole('link')).not.toBeInTheDocument();
  });

  it('control de lo anterior: con la misma espera, si SÍ hay series el enlace aparece', async () => {
    const answer = deferredResponse();
    renderList([dune], [], { [ANY_SERIES]: () => answer.promise });

    const series = await screen.findByRole('region', { name: 'Series' });
    await answerAndSettle(answer, jsonResponse(makePage([dark])));

    expect(within(series).getByRole('link', { name: 'Explorar series' })).toHaveAttribute('href', '/series');
  });

  it('mientras pregunta no enseña el enlace; si la pregunta falla, lo enseña (mejor ofrecer el camino que esconderlo)', async () => {
    const answer = deferredResponse();
    renderList([dune], [], { [ANY_SERIES]: () => answer.promise });

    const series = await screen.findByRole('region', { name: 'Series' });
    expect(within(series).queryByRole('link')).not.toBeInTheDocument();

    answer.resolve(errorResponse(500, 'INTERNAL_ERROR', 'boom'));

    expect(await within(series).findByRole('link', { name: 'Explorar series' })).toHaveAttribute('href', '/series');
  });

  it('con las dos secciones llenas no pregunta nada al catálogo', async () => {
    renderList([dune], [dark]);

    await screen.findByRole('region', { name: 'Series' });
    expect(fetchMock.mock.calls.map(([url]) => String(url)).filter((url) => url.includes('size=1'))).toEqual([]);
  });

  it('sin películas NI series: el estado vacío de siempre, sin secciones ni «Vaciar lista»', async () => {
    renderList([], []);

    expect(await screen.findByRole('heading', { level: 2, name: 'Tu lista está vacía' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Explorar catálogo' })).toHaveAttribute('href', '/');
    expect(screen.queryByRole('region', { name: 'Películas' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Vaciar lista' })).not.toBeInTheDocument();
  });

  it('si falla la carga de las series, error con «Reintentar» (no media lista) y reintentar la recupera', async () => {
    let fail = true;
    const user = renderList([dune], [], {
      [FAVORITE_SERIES]: () => (fail ? errorResponse(500, 'INTERNAL_ERROR', 'boom') : jsonResponse([dark])),
    });

    expect(await screen.findByRole('heading', { level: 2, name: 'No se pudo cargar tu lista' })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: 'Películas' })).not.toBeInTheDocument();

    fail = false;
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    expect(await screen.findByRole('link', { name: /^Dark/ })).toBeInTheDocument();
  });
  it('al pulsar «Reintentar» vuelve al estado de carga (sin el error a la vista) hasta que llega la respuesta', async () => {
    let retry: ReturnType<typeof deferredResponse> | null = null;
    const user = renderList([dune], [], {
      [FAVORITE_SERIES]: () => (retry ? retry.promise : errorResponse(500, 'INTERNAL_ERROR', 'boom')),
    });
    expect(await screen.findByRole('heading', { level: 2, name: 'No se pudo cargar tu lista' })).toBeInTheDocument();

    retry = deferredResponse();
    await user.click(screen.getByRole('button', { name: 'Reintentar' }));

    // Mientras el servidor no contesta: el esqueleto de carga, anunciado como estado, y no el error de antes.
    const loading = await screen.findByText('Cargando tu lista...');
    expect(loading.closest('[role="status"]')).not.toBeNull();
    expect(screen.queryByRole('heading', { name: 'No se pudo cargar tu lista' })).not.toBeInTheDocument();

    await act(async () => retry?.resolve(jsonResponse([dark])));

    expect(await screen.findByRole('link', { name: /^Dark/ })).toBeInTheDocument();
    expect(screen.queryByText('Cargando tu lista...')).not.toBeInTheDocument();
  });
});

describe('MyListPage: vaciar lista', () => {
  it('la confirmación nombra películas y series; confirmar vacía las dos y lleva el foco al título', async () => {
    const user = renderList([dune, arrival], [dark], {
      [CLEAR_MOVIES]: () => noContentResponse(),
      [CLEAR_SERIES]: () => noContentResponse(),
    });

    await user.click(await screen.findByRole('button', { name: 'Vaciar lista' }));
    const confirm = screen.getByRole('alertdialog', { name: '¿Vaciar tu lista?' });
    expect(confirm).toHaveTextContent('Se quitarán las 2 películas y la serie de Mi lista. Esta acción no se puede deshacer.');

    await user.click(within(confirm).getByRole('button', { name: 'Sí, vaciar lista' }));

    expect(await screen.findByRole('heading', { level: 2, name: 'Tu lista está vacía' })).toBeInTheDocument();
    expect(screen.getByText('Tu lista se ha vaciado.')).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 1, name: 'Mi lista' })).toHaveFocus();
    const deletes = fetchMock.mock.calls.filter(([, init]) => init?.method === 'DELETE').map(([url]) => String(url));
    expect(deletes.sort()).toEqual(['/api/users/me/favorites', '/api/users/me/favorites/series']);
  });

  it('solo con películas, el texto es el de siempre («Se quitarán las 2 películas de Mi lista»)', async () => {
    const user = renderList([dune, arrival], []);

    await user.click(await screen.findByRole('button', { name: 'Vaciar lista' }));

    expect(screen.getByRole('alertdialog')).toHaveTextContent(
      'Se quitarán las 2 películas de Mi lista. Esta acción no se puede deshacer.',
    );
  });

  it('solo con una serie, en singular', async () => {
    const user = renderList([], [dark]);

    await user.click(await screen.findByRole('button', { name: 'Vaciar lista' }));

    expect(screen.getByRole('alertdialog')).toHaveTextContent('Se quitará la serie de Mi lista.');
  });

  it('si solo se pudieron quitar las películas: se ven las series que quedan, el aviso lo explica y «Vaciar lista» sigue', async () => {
    const user = renderList([dune], [dark], {
      [CLEAR_MOVIES]: () => noContentResponse(),
      [CLEAR_SERIES]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom'),
    });

    await user.click(await screen.findByRole('button', { name: 'Vaciar lista' }));
    await user.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Sí, vaciar lista' }));

    // El aviso nace dentro del diálogo abierto (ver ToastContext) y vuelve a la página al cerrarse:
    // se busca cuando el diálogo ya se ha cerrado, en su sitio definitivo.
    await waitFor(() => expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument());
    expect(screen.getByText(/^Se han quitado las películas de tu lista, pero no las series\./)).toBeInTheDocument();
    expect(within(section('Películas')).getByText('Todavía no has guardado ninguna película.')).toBeInTheDocument();
    expect(within(section('Series')).getByRole('link', { name: /^Dark/ })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Vaciar lista' })).toBeInTheDocument();
  });
});
