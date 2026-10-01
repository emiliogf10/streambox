/**
 * Tests de `FavoritesProvider` y del `FavoriteButton` que lo usa.
 *
 * Protegen la actualización optimista de "Mi lista": la interfaz cambia al
 * instante, se vuelve atrás si el servidor falla (solo esa película y en su
 * posición), y 409 al añadir / 404 al quitar se tratan como "el estado ya era
 * el correcto" (aviso informativo, sin revertir). `fetch` está simulado.
 */
import { act, renderHook, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { ReactNode } from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FavoriteButton } from '../components/FavoriteButton';
import { errorResponse, jsonResponse, makeMovie, noContentResponse, renderWithProviders, routeFetch } from '../test/helpers';
import { FavoritesProvider, useFavorites } from './FavoritesContext';
import { AuthProvider } from './AuthContext';
import { ToastProvider } from './ToastContext';

const fetchMock = vi.fn<typeof fetch>();

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
      { token: 'jwt' },
    );
    // Espera a que termine la carga inicial: el botón refleja el estado real.
    await screen.findByRole('button', { name: initial.length > 0 ? IN_LIST : NOT_IN_LIST });
    return user;
  }

  it('carga la lista con el token y refleja que la película ya está en ella', async () => {
    await setup([m1]);

    expect(new Headers(fetchMock.mock.calls[0][1]?.headers).get('Authorization')).toBe('Bearer jwt');
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
      <ToastProvider>
        <AuthProvider>
          <FavoritesProvider>{children}</FavoritesProvider>
        </AuthProvider>
      </ToastProvider>
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

  describe('clear (vaciar la lista)', () => {
    it('no es optimista: la lista se vacía solo cuando el servidor confirma, y devuelve true', async () => {
      const pending = deferred<Response>();
      const { result } = await renderLoaded([m1, m2], { 'DELETE /api/users/me/favorites': () => pending.promise });

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
