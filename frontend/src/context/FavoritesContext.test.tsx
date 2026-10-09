/**
 * Tests de `FavoritesProvider` y del `FavoriteButton` que lo usa.
 *
 * Protegen la actualización optimista de "Mi lista": la interfaz cambia al
 * instante, se vuelve atrás si el servidor falla (solo esa película y en su
 * posición), y 409 al añadir / 404 al quitar se tratan como "el estado ya era
 * el correcto" (aviso informativo, sin revertir). Lo mismo para las series, que
 * son una lista aparte (y no se confunden con una película del mismo id), y el
 * vaciado de las dos con su fallo parcial. `fetch` está simulado.
 */
import { QueryClientProvider, focusManager } from '@tanstack/react-query';
import { act, renderHook, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FavoriteButton } from '../components/FavoriteButton';
import {
  errorResponse,
  jsonResponse,
  makeMovie,
  makeSeries,
  noContentResponse,
  renderWithProviders,
  routeFetch,
} from '../test/helpers';
import { FavoritesProvider, useFavorites } from './FavoritesContext';
import { AuthProvider, useAuth } from './AuthContext';
import { ToastProvider } from './ToastContext';
import { createTestQueryClient } from '../test/queryClient';
import { queryKeys } from '../lib/queryKeys';

const fetchMock = vi.fn<typeof fetch>();

/** Caché de datos nueva en cada test (ver `beforeEach`): ninguno ve la lista que cargó otro. */
let queryClient = createTestQueryClient();

const m1 = makeMovie({ id: 1, title: 'Uno' });
const m2 = makeMovie({ id: 2, title: 'Dos' });
const m3 = makeMovie({ id: 3, title: 'Tres' });

const LIST = 'GET /api/users/me/favorites';

// Nombre accesible del botón: texto visible + título para lectores de pantalla.
// Se usa una expresión porque jsdom calcula el nombre sin el espacio que el
// navegador sí deja antes del guion largo (`Mi lista— Uno`); lo importante es
// que el título de la película forme parte del nombre.
const NOT_IN_LIST = /^Mi lista\s*—\s*Uno$/;
const IN_LIST = /^En mi lista\s*—\s*Uno$/;

/** Promesa controlable a mano para observar el estado "petición en curso". */
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  queryClient = createTestQueryClient();
});

describe('FavoriteButton (interfaz)', () => {
  /** Monta el botón de `movie` con la lista inicial indicada. */
  async function setup(initial: ReturnType<typeof makeMovie>[], extra: Parameters<typeof routeFetch>[1] = {}) {
    routeFetch(fetchMock, { [LIST]: () => jsonResponse(initial), ...extra });
    const user = userEvent.setup();
    renderWithProviders(
      <FavoritesProvider>
        <FavoriteButton movie={m1} />
      </FavoritesProvider>,
      { session: true },
    );
    // Espera a que termine la carga inicial: el botón refleja el estado real.
    await screen.findByRole('button', { name: initial.length > 0 ? IN_LIST : NOT_IN_LIST });
    return user;
  }

  it('carga la lista con la cookie de sesión (sin cabecera Authorization) y refleja que la película ya está en ella', async () => {
    await setup([m1]);

    // Se busca la petición de la lista (no "la primera"): `AuthProvider` también pide `/users/me`.
    const listCall = fetchMock.mock.calls.find(([url]) => String(url) === '/api/users/me/favorites');
    expect(new Headers(listCall?.[1]?.headers).has('Authorization')).toBe(false);
    expect(listCall?.[1]?.credentials).toBe('same-origin');
    expect(screen.getByRole('button', { name: IN_LIST })).toBeInTheDocument();
  });

  it('añadir es optimista: cambia al instante, se bloquea mientras dura la petición y confirma con un aviso', async () => {
    const pending = deferred<Response>();
    const user = await setup([], { 'POST /api/users/me/favorites/1': () => pending.promise });

    await user.click(screen.getByRole('button', { name: NOT_IN_LIST }));

    // El servidor aún no ha respondido, pero la interfaz ya muestra el resultado.
    const button = screen.getByRole('button', { name: IN_LIST });
    expect(button).toBeDisabled();
    expect(screen.queryByText(/se ha añadido a tu lista/)).not.toBeInTheDocument();

    await act(async () => pending.resolve(jsonResponse({}, 201)));

    expect(screen.getByRole('button', { name: IN_LIST })).toBeEnabled();
    expect(screen.getByText('«Uno» se ha añadido a tu lista.')).toBeInTheDocument();
  });

  it('si añadir falla (500), vuelve atrás y avisa del error', async () => {
    const user = await setup([], {
      'POST /api/users/me/favorites/1': () => errorResponse(500, 'INTERNAL_ERROR', 'boom'),
    });

    await user.click(screen.getByRole('button', { name: NOT_IN_LIST }));

    expect(await screen.findByText('El servidor ha tenido un problema. Inténtalo de nuevo en unos minutos.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: NOT_IN_LIST })).toBeEnabled();
    expect(screen.queryByText(/se ha añadido/)).not.toBeInTheDocument();
  });

  it('si la red cae al añadir, vuelve atrás y avisa', async () => {
    const user = await setup([], {
      'POST /api/users/me/favorites/1': () => {
        throw new TypeError('Failed to fetch');
      },
    });

    await user.click(screen.getByRole('button', { name: NOT_IN_LIST }));

    expect(await screen.findByText(/No se pudo conectar con el servidor/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: NOT_IN_LIST })).toBeInTheDocument();
  });

  it('409 al añadir significa "ya estaba": se queda en la lista, sin revertir, con aviso informativo', async () => {
    const user = await setup([], {
      'POST /api/users/me/favorites/1': () =>
        errorResponse(409, 'MOVIE_ALREADY_IN_FAVORITES', 'La película ya está en favoritos'),
    });

    await user.click(screen.getByRole('button', { name: NOT_IN_LIST }));

    expect(await screen.findByText('«Uno» ya estaba en tu lista.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: IN_LIST })).toBeInTheDocument();
  });

  it('quitar es optimista (DELETE) y confirma con un aviso', async () => {
    const user = await setup([m1], { 'DELETE /api/users/me/favorites/1': () => noContentResponse() });

    await user.click(screen.getByRole('button', { name: IN_LIST }));

    expect(await screen.findByText('«Uno» se ha quitado de tu lista.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: NOT_IN_LIST })).toBeInTheDocument();
  });

  it('404 al quitar significa "ya no estaba": se queda fuera de la lista, sin revertir', async () => {
    const user = await setup([m1], {
      'DELETE /api/users/me/favorites/1': () => errorResponse(404, 'MOVIE_NOT_IN_FAVORITES', 'No estaba'),
    });

    await user.click(screen.getByRole('button', { name: IN_LIST }));

    expect(await screen.findByText('«Uno» ya no estaba en tu lista.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: NOT_IN_LIST })).toBeInTheDocument();
  });

  it('si quitar falla (500), la película vuelve a la lista', async () => {
    const user = await setup([m1], {
      'DELETE /api/users/me/favorites/1': () => errorResponse(500, 'INTERNAL_ERROR', 'boom'),
    });

    await user.click(screen.getByRole('button', { name: IN_LIST }));

    expect(await screen.findByText(/El servidor ha tenido un problema/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: IN_LIST })).toBeEnabled();
  });

  it('un 404 al AÑADIR (película borrada) NO se toma por "ya estaba": se revierte y avisa', async () => {
    const user = await setup([], {
      'POST /api/users/me/favorites/1': () => errorResponse(404, 'MOVIE_NOT_FOUND', 'La película no existe'),
    });

    await user.click(screen.getByRole('button', { name: NOT_IN_LIST }));

    expect(await screen.findByText('La película no existe')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: NOT_IN_LIST })).toBeInTheDocument();
  });
});

describe('useFavorites (estado compartido)', () => {
  function wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <AuthProvider>
            <FavoritesProvider>{children}</FavoritesProvider>
          </AuthProvider>
        </ToastProvider>
      </QueryClientProvider>
    );
  }

  async function renderLoaded(initial = [m1, m2, m3], extra: Parameters<typeof routeFetch>[1] = {}) {
    routeFetch(fetchMock, { [LIST]: () => jsonResponse(initial), ...extra });
    const hook = renderHook(() => useFavorites(), { wrapper });
    await waitFor(() => expect(hook.result.current.status).toBe('ready'));
    return hook;
  }

  it('carga la lista y expone isFavorite', async () => {
    const { result } = await renderLoaded([m1, m2]);

    expect(result.current.movies.map((m) => m.id)).toEqual([1, 2]);
    expect(result.current.isFavorite(1)).toBe(true);
    expect(result.current.isFavorite(3)).toBe(false);
  });

  it('un fallo de carga deja status "error" con mensaje y se puede reintentar con reload', async () => {
    routeFetch(fetchMock, { [LIST]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });
    const { result } = renderHook(() => useFavorites(), { wrapper });
    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(result.current.errorMessage).toMatch(/El servidor ha tenido un problema/);

    routeFetch(fetchMock, { [LIST]: () => jsonResponse([m1]) });
    act(() => result.current.reload());
    await waitFor(() => expect(result.current.status).toBe('ready'));

    expect(result.current.movies).toHaveLength(1);
  });

  it('al añadir, la película nueva va la primera (la más reciente arriba)', async () => {
    const m4 = makeMovie({ id: 4, title: 'Cuatro' });
    const { result } = await renderLoaded([m1], { 'POST /api/users/me/favorites/4': () => jsonResponse({}, 201) });

    await act(() => result.current.toggle(m4));

    expect(result.current.movies.map((m) => m.id)).toEqual([4, 1]);
  });

  it('si quitar falla, la película vuelve a su POSICIÓN original', async () => {
    const { result } = await renderLoaded([m1, m2, m3], {
      'DELETE /api/users/me/favorites/2': () => errorResponse(500, 'INTERNAL_ERROR', 'boom'),
    });

    await act(() => result.current.toggle(m2));

    expect(result.current.movies.map((m) => m.id)).toEqual([1, 2, 3]);
  });

  it('un segundo toggle de la misma película mientras hay una petición en curso se ignora', async () => {
    const pending = deferred<Response>();
    const { result } = await renderLoaded([m1], { 'DELETE /api/users/me/favorites/1': () => pending.promise });

    let first!: Promise<void>;
    await act(async () => {
      first = result.current.toggle(m1);
      void result.current.toggle(m1);
    });

    expect(result.current.isPending(1)).toBe(true);
    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'DELETE')).toHaveLength(1);

    await act(async () => {
      pending.resolve(noContentResponse());
      await first;
    });
    expect(result.current.isPending(1)).toBe(false);
  });

  it('una película que falla no deshace los cambios de OTRA que va en paralelo', async () => {
    const slowFail = deferred<Response>();
    const { result } = await renderLoaded([m1, m2], {
      'DELETE /api/users/me/favorites/1': () => slowFail.promise, // fallará más tarde
      'DELETE /api/users/me/favorites/2': () => noContentResponse(), // acaba bien enseguida
    });

    let firstToggle!: Promise<void>;
    await act(async () => {
      firstToggle = result.current.toggle(m1);
    });
    await act(async () => {
      await result.current.toggle(m2);
    });
    await act(async () => {
      slowFail.resolve(errorResponse(500, 'INTERNAL_ERROR', 'boom'));
      await firstToggle;
    });

    // Se restaura SOLO la 1; la 2 sigue quitada.
    expect(result.current.movies.map((m) => m.id)).toEqual([1]);
  });

  describe('caché de TanStack Query', () => {
    it('el cambio optimista está en la caché al instante y, si falla, se deshace también allí', async () => {
      const pending = deferred<Response>();
      const { result } = await renderLoaded([m1, m2], { 'DELETE /api/users/me/favorites/1': () => pending.promise });
      const cachedIds = () =>
        queryClient.getQueryData<{ movie: { id: number }[] }>(queryKeys.favorites.all)?.movie.map((m) => m.id);

      let toggling!: Promise<void>;
      await act(async () => {
        toggling = result.current.toggle(m1);
      });
      expect(cachedIds()).toEqual([2]);
      expect(result.current.isFavorite(1)).toBe(false);

      await act(async () => {
        pending.resolve(errorResponse(500, 'INTERNAL_ERROR', 'boom'));
        await toggling;
      });
      expect(cachedIds()).toEqual([1, 2]);
      expect(result.current.isFavorite(1)).toBe(true);
    });

    it('un refresco de la lista que estaba en vuelo NO pisa el cambio optimista (se cancela)', async () => {
      const staleRefresh = deferred<Response>();
      let refreshing = false;
      const { result } = await renderLoaded([m1], {
        [LIST]: () => (refreshing ? staleRefresh.promise : jsonResponse([m1])),
        'POST /api/users/me/favorites/2': () => jsonResponse({}, 201),
      });
      // Algo pide refrescar la lista (volver a la pestaña, una invalidación...) y el servidor tarda.
      refreshing = true;
      await act(async () => {
        void queryClient.invalidateQueries({ queryKey: queryKeys.favorites.all });
      });

      await act(() => result.current.toggle(m2));
      // La respuesta del refresco era de ANTES del clic (sin la 2): llega tarde y se descarta.
      await act(async () => staleRefresh.resolve(jsonResponse([m1])));

      expect(result.current.movies.map((m) => m.id)).toEqual([2, 1]);
    });

    it('tras el cambio la lista queda «anticuada» pero no se vuelve a pedir (una petición por clic, no dos)', async () => {
      const { result } = await renderLoaded([m1], { 'POST /api/users/me/favorites/2': () => jsonResponse({}, 201) });

      await act(() => result.current.toggle(m2));

      expect(queryClient.getQueryState(queryKeys.favorites.all)?.isInvalidated).toBe(true);
      expect(fetchMock.mock.calls.filter(([url, init]) => String(url) === '/api/users/me/favorites' && !init?.method)).toHaveLength(1);
    });

    it('si se pulsa mientras la lista aún carga, al terminar se vuelve a pedir (esa carga pudo salir antes del cambio)', async () => {
      const firstLoad = deferred<Response>();
      let loads = 0;
      routeFetch(fetchMock, {
        [LIST]: () => {
          loads += 1;
          return loads === 1 ? firstLoad.promise : jsonResponse([m2]);
        },
        'POST /api/users/me/favorites/2': () => jsonResponse({}, 201),
      });
      const { result } = renderHook(() => useFavorites(), { wrapper });
      await waitFor(() => expect(loads).toBe(1));

      await act(() => result.current.toggle(m2));
      await act(async () => firstLoad.resolve(jsonResponse([])));

      await waitFor(() => expect(result.current.movies.map((m) => m.id)).toEqual([2]));
      expect(loads).toBe(2);
    });
  });

  describe('clear (vaciar la lista)', () => {
    it('no es optimista: la lista se vacía solo cuando el servidor confirma, y devuelve true', async () => {
      const pending = deferred<Response>();
      const { result } = await renderLoaded([m1, m2], {
        'DELETE /api/users/me/favorites': () => pending.promise,
        'DELETE /api/users/me/favorites/series': () => noContentResponse(),
      });

      let outcome!: Promise<boolean>;
      await act(async () => {
        outcome = result.current.clear();
      });
      expect(result.current.movies).toHaveLength(2);

      await act(async () => {
        pending.resolve(noContentResponse());
        await outcome;
      });

      await expect(outcome).resolves.toBe(true);
      expect(result.current.movies).toEqual([]);
      expect(screen.getByText('Tu lista se ha vaciado.')).toBeInTheDocument();
    });

    it('si falla conserva la lista, avisa y devuelve false', async () => {
      const { result } = await renderLoaded([m1, m2], {
        'DELETE /api/users/me/favorites': () => errorResponse(500, 'INTERNAL_ERROR', 'boom'),
        'DELETE /api/users/me/favorites/series': () => errorResponse(500, 'INTERNAL_ERROR', 'boom'),
      });

      let ok: boolean | undefined;
      await act(async () => {
        ok = await result.current.clear();
      });

      expect(ok).toBe(false);
      expect(result.current.movies).toHaveLength(2);
      expect(screen.getByText(/El servidor ha tenido un problema/)).toBeInTheDocument();
    });
  });

  it('useFavorites fuera del proveedor lanza un error claro', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {});

    expect(() => renderHook(() => useFavorites())).toThrow('useFavorites debe usarse dentro de <FavoritesProvider>.');
  });
});

/**
 * Series en "Mi lista": su propia lista en el servidor (`/users/me/favorites/series`)
 * con el MISMO comportamiento que las películas (optimista, 409/404 = estado ya
 * correcto) y sin mezclarse con ellas aunque compartan id.
 */
describe('useFavorites: series', () => {
  const SERIES_LIST = 'GET /api/users/me/favorites/series';
  const s1 = makeSeries({ id: 1, title: 'Serie Uno' });
  const s2 = makeSeries({ id: 2, title: 'Serie Dos' });

  function wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <AuthProvider>
            <FavoritesProvider>{children}</FavoritesProvider>
          </AuthProvider>
        </ToastProvider>
      </QueryClientProvider>
    );
  }

  /** Monta el hook con ambas listas cargadas (películas y series). */
  async function renderLoaded(
    movies = [m1],
    series = [s1],
    extra: Parameters<typeof routeFetch>[1] = {},
  ) {
    routeFetch(fetchMock, {
      [LIST]: () => jsonResponse(movies),
      [SERIES_LIST]: () => jsonResponse(series),
      ...extra,
    });
    const hook = renderHook(() => useFavorites(), { wrapper });
    await waitFor(() => expect(hook.result.current.status).toBe('ready'));
    return hook;
  }

  it('carga las dos listas, y una película y una serie con el mismo id no se confunden', async () => {
    const { result } = await renderLoaded([m1], [s2]);

    expect(result.current.movies.map((m) => m.id)).toEqual([1]);
    expect(result.current.series.map((s) => s.id)).toEqual([2]);
    expect(result.current.isFavorite(1)).toBe(true); // sin tipo = película
    expect(result.current.isFavorite(1, 'series')).toBe(false);
    expect(result.current.isFavorite(2, 'series')).toBe(true);
    expect(result.current.isFavorite(2, 'movie')).toBe(false);
  });

  it('si falla la carga de las series la lista entera queda en error (no se enseña media lista) y reload pide las dos', async () => {
    routeFetch(fetchMock, {
      [LIST]: () => jsonResponse([m1]),
      [SERIES_LIST]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom'),
    });
    const { result } = renderHook(() => useFavorites(), { wrapper });
    await waitFor(() => expect(result.current.status).toBe('error'));
    expect(result.current.errorMessage).toMatch(/El servidor ha tenido un problema/);

    routeFetch(fetchMock, { [LIST]: () => jsonResponse([m1]), [SERIES_LIST]: () => jsonResponse([s1]) });
    act(() => result.current.reload());
    await waitFor(() => expect(result.current.status).toBe('ready'));

    expect(result.current.movies).toHaveLength(1);
    expect(result.current.series).toHaveLength(1);
  });

  it('añadir una serie es optimista, va la primera y usa el endpoint de series', async () => {
    const pending = deferred<Response>();
    const { result } = await renderLoaded([], [s1], { 'POST /api/users/me/favorites/series/2': () => pending.promise });

    let toggling!: Promise<void>;
    await act(async () => {
      toggling = result.current.toggleSeries(s2);
    });
    // El servidor aún no ha respondido, pero la lista ya la incluye (y bloquea su botón).
    expect(result.current.series.map((s) => s.id)).toEqual([2, 1]);
    expect(result.current.isPending(2, 'series')).toBe(true);
    expect(result.current.isPending(2, 'movie')).toBe(false);

    await act(async () => {
      pending.resolve(noContentResponse());
      await toggling;
    });
    expect(result.current.isPending(2, 'series')).toBe(false);
    expect(screen.getByText('«Serie Dos» se ha añadido a tu lista.')).toBeInTheDocument();
  });

  it('409 SERIES_ALREADY_IN_FAVORITES al añadir = ya estaba: se queda, con aviso informativo', async () => {
    const { result } = await renderLoaded([], [], {
      'POST /api/users/me/favorites/series/1': () =>
        errorResponse(409, 'SERIES_ALREADY_IN_FAVORITES', 'La serie ya está en favoritos'),
    });

    await act(() => result.current.toggleSeries(s1));

    expect(result.current.isFavorite(1, 'series')).toBe(true);
    expect(screen.getByText('«Serie Uno» ya estaba en tu lista.')).toBeInTheDocument();
  });

  it('404 SERIES_NOT_IN_FAVORITES al quitar = ya no estaba: se queda fuera, sin revertir', async () => {
    const { result } = await renderLoaded([], [s1], {
      'DELETE /api/users/me/favorites/series/1': () =>
        errorResponse(404, 'SERIES_NOT_IN_FAVORITES', 'La serie no está en favoritos'),
    });

    await act(() => result.current.toggleSeries(s1));

    expect(result.current.series).toEqual([]);
    expect(screen.getByText('«Serie Uno» ya no estaba en tu lista.')).toBeInTheDocument();
  });

  it('un 404 al AÑADIR (serie inexistente o sin episodios) NO es "ya estaba": se revierte y avisa', async () => {
    const { result } = await renderLoaded([], [], {
      'POST /api/users/me/favorites/series/1': () => errorResponse(404, 'RESOURCE_NOT_FOUND', 'La serie no existe'),
    });

    await act(() => result.current.toggleSeries(s1));

    expect(result.current.series).toEqual([]);
    expect(screen.getByText('La serie no existe')).toBeInTheDocument();
  });

  it('si quitar una serie falla (500), vuelve a su posición', async () => {
    const { result } = await renderLoaded([], [s1, s2], {
      'DELETE /api/users/me/favorites/series/1': () => errorResponse(500, 'INTERNAL_ERROR', 'boom'),
    });

    await act(() => result.current.toggleSeries(s1));

    expect(result.current.series.map((s) => s.id)).toEqual([1, 2]);
    expect(screen.getByText(/El servidor ha tenido un problema/)).toBeInTheDocument();
  });

  it('quitar una serie no toca la película con el mismo id', async () => {
    const { result } = await renderLoaded([m1], [s1], {
      'DELETE /api/users/me/favorites/series/1': () => noContentResponse(),
    });

    await act(() => result.current.toggleSeries(s1));

    expect(result.current.series).toEqual([]);
    expect(result.current.movies.map((m) => m.id)).toEqual([1]);
  });

  describe('clear con películas y series', () => {
    it('envía los DOS DELETE (aunque una lista parezca vacía) y, si van bien, vacía ambas', async () => {
      const { result } = await renderLoaded([m1], [], {
        'DELETE /api/users/me/favorites': () => noContentResponse(),
        'DELETE /api/users/me/favorites/series': () => noContentResponse(),
      });

      let ok: boolean | undefined;
      await act(async () => {
        ok = await result.current.clear();
      });

      expect(ok).toBe(true);
      const deletes = fetchMock.mock.calls.filter(([, init]) => init?.method === 'DELETE').map(([url]) => String(url));
      expect(deletes.sort()).toEqual(['/api/users/me/favorites', '/api/users/me/favorites/series']);
      expect(result.current.movies).toEqual([]);
      expect(result.current.series).toEqual([]);
      expect(screen.getByText('Tu lista se ha vaciado.')).toBeInTheDocument();
    });

    it('si solo fallan las series: quita las películas, conserva las series, lo explica y devuelve false', async () => {
      const { result } = await renderLoaded([m1, m2], [s1], {
        'DELETE /api/users/me/favorites': () => noContentResponse(),
        'DELETE /api/users/me/favorites/series': () => errorResponse(500, 'INTERNAL_ERROR', 'boom'),
      });

      let ok: boolean | undefined;
      await act(async () => {
        ok = await result.current.clear();
      });

      expect(ok).toBe(false);
      expect(result.current.movies).toEqual([]);
      expect(result.current.series.map((s) => s.id)).toEqual([1]);
      expect(
        screen.getByText(/^Se han quitado las películas de tu lista, pero no las series\. El servidor ha tenido un problema/),
      ).toBeInTheDocument();
      expect(screen.queryByText('Tu lista se ha vaciado.')).not.toBeInTheDocument();
    });

    it('si solo fallan las películas: quita las series y conserva las películas', async () => {
      const { result } = await renderLoaded([m1], [s1], {
        'DELETE /api/users/me/favorites': () => errorResponse(500, 'INTERNAL_ERROR', 'boom'),
        'DELETE /api/users/me/favorites/series': () => noContentResponse(),
      });

      await act(async () => {
        await result.current.clear();
      });

      expect(result.current.movies.map((m) => m.id)).toEqual([1]);
      expect(result.current.series).toEqual([]);
      expect(screen.getByText(/^Se han quitado las series de tu lista, pero no las películas\./)).toBeInTheDocument();
    });
  });
});

describe('FavoriteButton de una serie', () => {
  it('refleja el estado de la serie (no el de la película con el mismo id) y añade por el endpoint de series', async () => {
    const series = makeSeries({ id: 1, title: 'Serie Uno' });
    routeFetch(fetchMock, {
      [LIST]: () => jsonResponse([m1]), // la PELÍCULA 1 está en la lista...
      'GET /api/users/me/favorites/series': () => jsonResponse([]), // ...pero la SERIE 1 no
      'POST /api/users/me/favorites/series/1': () => noContentResponse(),
    });
    const user = userEvent.setup();
    renderWithProviders(
      <FavoritesProvider>
        <FavoriteButton series={series} />
      </FavoritesProvider>,
      { session: true },
    );

    const button = await screen.findByRole('button', { name: /^Mi lista\s*—\s*Serie Uno$/ });
    await user.click(button);

    expect(await screen.findByText('«Serie Uno» se ha añadido a tu lista.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /^En mi lista\s*—\s*Serie Uno$/ })).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([url, init]) => String(url) === '/api/users/me/favorites/series/1' && init?.method === 'POST')).toBe(true);
  });
});

describe('useFavorites: carreras con refrescos, avisos y cambios de sesión', () => {
  function wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>
        <ToastProvider>
          <AuthProvider>
            <FavoritesProvider>{children}</FavoritesProvider>
          </AuthProvider>
        </ToastProvider>
      </QueryClientProvider>
    );
  }

  /** Monta la lista y la sesión juntas (para poder cerrar sesión desde el test). */
  async function renderLoaded(extra: Parameters<typeof routeFetch>[1] = {}) {
    routeFetch(fetchMock, { [LIST]: () => jsonResponse([m1]), ...extra });
    const hook = renderHook(() => ({ fav: useFavorites(), auth: useAuth() }), { wrapper });
    await waitFor(() => expect(hook.result.current.fav.status).toBe('ready'));
    await waitFor(() => expect(hook.result.current.auth.isAuthenticated).toBe(true));
    return hook;
  }

  const ids = (movies: { id: number }[]) => movies.map((m) => m.id);

  it.each([
    ['201 (añadida)', () => jsonResponse({}, 201)],
    ['409 (ya estaba)', () => errorResponse(409, 'MOVIE_ALREADY_IN_FAVORITES', 'La película ya está en favoritos')],
  ])(
    'un refresco que empieza DURANTE el POST y trae la lista de antes no deja fuera el título confirmado: %s',
    async (_name, answer) => {
      const post = deferred<Response>();
      const { result } = await renderLoaded({ 'POST /api/users/me/favorites/2': () => post.promise });

      let toggling!: Promise<void>;
      await act(async () => {
        toggling = result.current.fav.toggle(m2);
      });
      expect(ids(result.current.fav.movies)).toEqual([2, 1]);
      // Mientras el POST viaja, algo refresca la lista y el servidor aún no la tiene: pisa el cambio optimista.
      await act(async () => {
        await queryClient.invalidateQueries({ queryKey: queryKeys.favorites.all });
      });
      expect(ids(result.current.fav.movies)).toEqual([1]);

      await act(async () => {
        post.resolve(answer());
        await toggling;
      });

      // El servidor confirmó que está: se vuelve a aplicar, sin pedir la lista otra vez y sin duplicarla.
      expect(ids(result.current.fav.movies)).toEqual([2, 1]);
      expect(fetchMock.mock.calls.filter(([url, init]) => String(url) === '/api/users/me/favorites' && !init?.method)).toHaveLength(2);
    },
  );

  it('lo mismo al quitar: un refresco a medias del DELETE no devuelve el título quitado', async () => {
    const del = deferred<Response>();
    const { result } = await renderLoaded({
      [LIST]: () => jsonResponse([m1]),
      'DELETE /api/users/me/favorites/1': () => del.promise,
    });

    let toggling!: Promise<void>;
    await act(async () => {
      toggling = result.current.fav.toggle(m1);
    });
    await act(async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.favorites.all });
    });
    expect(ids(result.current.fav.movies)).toEqual([1]);

    await act(async () => {
      del.resolve(noContentResponse());
      await toggling;
    });

    expect(ids(result.current.fav.movies)).toEqual([]);
  });

  it('si la respuesta llega cuando la sesión ya se cerró, no avisa ni toca la lista (es de otra sesión)', async () => {
    const post = deferred<Response>();
    const { result } = await renderLoaded({
      'POST /api/users/me/favorites/2': () => post.promise,
      'POST /api/auth/logout': () => noContentResponse(),
    });

    let toggling!: Promise<void>;
    await act(async () => {
      toggling = result.current.fav.toggle(m2);
    });
    await act(async () => {
      await result.current.auth.logout();
    });
    await act(async () => {
      post.resolve(jsonResponse({}, 201));
      await toggling;
    });

    expect(screen.queryByText(/se ha añadido a tu lista/)).not.toBeInTheDocument();
    expect(result.current.fav.isFavorite(2)).toBe(false);
    expect(result.current.fav.isPending(2)).toBe(false);
  });

  it('un fallo que llega con la sesión ya cerrada tampoco avisa (ni deshace nada en la lista de otra sesión)', async () => {
    const del = deferred<Response>();
    const { result } = await renderLoaded({
      'DELETE /api/users/me/favorites/1': () => del.promise,
      'POST /api/auth/logout': () => noContentResponse(),
    });

    let toggling!: Promise<void>;
    await act(async () => {
      toggling = result.current.fav.toggle(m1);
    });
    await act(async () => {
      await result.current.auth.logout();
    });
    await act(async () => {
      del.resolve(errorResponse(500, 'INTERNAL_ERROR', 'boom'));
      await toggling;
    });

    expect(screen.queryByText(/El servidor ha tenido un problema/)).not.toBeInTheDocument();
    expect(screen.queryByText(/No se pudo quitar/)).not.toBeInTheDocument();
  });

  describe('con el servidor caído y volviendo a la pestaña', () => {
    afterEach(() => {
      focusManager.setFocused(undefined);
    });

    /** Simula salir de la pestaña y volver a ella (lo que dispara `refetchOnWindowFocus`). */
    async function returnToTab() {
      await act(async () => {
        focusManager.setFocused(false);
        focusManager.setFocused(true);
      });
    }

    it('avisa UNA vez por racha de fallos; tras una carga correcta, un fallo nuevo vuelve a avisar', async () => {
      // La app real refresca al volver a la pestaña (el cliente de los tests lo desactiva por defecto).
      queryClient.setDefaultOptions({
        queries: { ...queryClient.getDefaultOptions().queries, refetchOnWindowFocus: true },
      });
      let up = false;
      let loads = 0;
      routeFetch(fetchMock, {
        [LIST]: () => {
          loads += 1;
          return up ? jsonResponse([m1]) : errorResponse(500, 'INTERNAL_ERROR', 'boom');
        },
      });
      const { result } = renderHook(() => useFavorites(), { wrapper });
      await waitFor(() => expect(result.current.status).toBe('error'));
      const SERVER_DOWN = 'El servidor ha tenido un problema. Inténtalo de nuevo en unos minutos.';
      expect(screen.getAllByText(SERVER_DOWN)).toHaveLength(1);

      for (const expectedLoads of [2, 3]) {
        await returnToTab();
        await waitFor(() => expect(loads).toBe(expectedLoads));
        await waitFor(() => expect(result.current.status).toBe('error'));
      }
      // Tres fallos seguidos, un solo aviso (la pantalla ya enseña el error con «Reintentar»).
      expect(screen.getAllByText(SERVER_DOWN)).toHaveLength(1);

      up = true;
      await returnToTab();
      await waitFor(() => expect(result.current.status).toBe('ready'));

      // Racha nueva (p. ej. se descarta la lista y vuelve a fallar): sí se avisa otra vez.
      up = false;
      await act(async () => {
        await queryClient.resetQueries({ queryKey: queryKeys.favorites.all });
      });
      await waitFor(() => expect(result.current.status).toBe('error'));
      expect(screen.getAllByText(SERVER_DOWN)).toHaveLength(2);
    });
  });
});
