/**
 * Tests de `AuthProvider` / `useAuth`: la única fuente de verdad de la sesión.
 *
 * Protegen que el token solo vive en `localStorage['token']`, que las pestañas
 * se mantienen coherentes entre sí (evento `storage`), que un 401 cierra la
 * sesión UNA sola vez y solo si es de la sesión actual, y que un almacenamiento
 * roto no tumba la aplicación. Se usa el `apiFetch` real con `fetch` simulado
 * para probar el puente `configureAuth` de punta a punta.
 *
 * También protegen la carga del usuario actual (`GET /api/users/me`): que se
 * pide en cada sesión, que `isAdmin` falla cerrado y que el usuario de una
 * sesión anterior nunca se cuela en la nueva.
 */
import { act, renderHook, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { apiFetch } from '../lib/api';
import type { User } from '../lib/types';
import { errorResponse, jsonResponse, makeUser } from '../test/helpers';
import { AuthProvider, useAuth } from './AuthContext';
import { ToastProvider } from './ToastContext';

const fetchMock = vi.fn<typeof fetch>();

function wrapper({ children }: { children: ReactNode }) {
  return (
    <ToastProvider>
      <AuthProvider>{children}</AuthProvider>
    </ToastProvider>
  );
}

/** Simula el evento que el navegador emite en OTRA pestaña cuando cambia `localStorage`. */
function storageEvent(init: StorageEventInit) {
  act(() => {
    window.dispatchEvent(new StorageEvent('storage', init));
  });
}

/** Cabecera Authorization de la última petición hecha con `fetch`. */
function lastAuthorization(): string | null {
  const init = fetchMock.mock.calls.at(-1)?.[1];
  return new Headers(init?.headers).get('Authorization');
}

/** Promesa controlable a mano para decidir CUÁNDO contesta el servidor. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

/** Peticiones hechas a `GET /api/users/me`. */
function userRequests() {
  return fetchMock.mock.calls.filter(([url]) => String(url) === '/api/users/me');
}

/**
 * Simula `GET /api/users/me` respondiendo según el token con el que se pide
 * (clave = token sin `Bearer `). Cualquier otra petición hace fallar la llamada
 * con un mensaje claro en lugar de inventarse una respuesta.
 */
function serveCurrentUser(byToken: Record<string, () => Response | Promise<Response>>) {
  fetchMock.mockImplementation(async (input, init) => {
    const usedToken = new Headers(init?.headers).get('Authorization')?.replace('Bearer ', '') ?? '';
    const handler = String(input) === '/api/users/me' ? byToken[usedToken] : undefined;
    if (!handler) throw new Error(`Petición no prevista en el test: ${String(input)} con token «${usedToken}»`);
    return handler();
  });
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  // Por defecto el servidor no contesta: el `/users/me` que lanza `AuthProvider`
  // en cuanto hay sesión se queda en "cargando" y no interfiere con los tests
  // que no tratan del usuario. Los que sí, configuran su propia respuesta.
  fetchMock.mockImplementation(() => new Promise<Response>(() => {}));
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('AuthProvider: login y logout', () => {
  it('sin token guardado no hay sesión', () => {
    const { result } = renderHook(() => useAuth(), { wrapper });

    expect(result.current).toMatchObject({ token: null, isAuthenticated: false });
  });

  it('arranca con la sesión guardada en localStorage', () => {
    localStorage.setItem('token', 'guardado');

    const { result } = renderHook(() => useAuth(), { wrapper });

    expect(result.current).toMatchObject({ token: 'guardado', isAuthenticated: true });
  });

  it('login guarda el token en localStorage["token"] y abre la sesión', () => {
    const { result } = renderHook(() => useAuth(), { wrapper });

    act(() => result.current.login('abc'));

    expect(localStorage.getItem('token')).toBe('abc');
    expect(result.current).toMatchObject({ token: 'abc', isAuthenticated: true });
  });

  it('logout borra el token y cierra la sesión (y es idempotente)', () => {
    localStorage.setItem('token', 'abc');
    const { result } = renderHook(() => useAuth(), { wrapper });

    act(() => result.current.logout());
    act(() => result.current.logout());

    expect(localStorage.getItem('token')).toBeNull();
    expect(result.current.isAuthenticated).toBe(false);
  });

  it('una petición lanzada justo después de login ya lleva el token nuevo', async () => {
    fetchMock.mockImplementation(async () => jsonResponse({}));
    const { result } = renderHook(() => useAuth(), { wrapper });

    act(() => result.current.login('recien-llegado'));
    await apiFetch('/movies');

    expect(lastAuthorization()).toBe('Bearer recien-llegado');
  });

  it('useAuth fuera de AuthProvider lanza un error claro', () => {
    // React registra con console.error el error de render; es lo esperado aquí.
    vi.spyOn(console, 'error').mockImplementation(() => {});

    expect(() => renderHook(() => useAuth())).toThrow('useAuth debe usarse dentro de <AuthProvider>.');
  });
});

describe('AuthProvider: sincronización entre pestañas (evento storage)', () => {
  it('un token nuevo escrito en otra pestaña abre la sesión aquí y lo usan las peticiones', async () => {
    fetchMock.mockImplementation(async () => jsonResponse({}));
    const { result } = renderHook(() => useAuth(), { wrapper });

    storageEvent({ key: 'token', newValue: 'de-otra-pestana' });
    await apiFetch('/movies');

    expect(result.current).toMatchObject({ token: 'de-otra-pestana', isAuthenticated: true });
    expect(lastAuthorization()).toBe('Bearer de-otra-pestana');
  });

  it('el token borrado en otra pestaña (newValue null) cierra la sesión', () => {
    localStorage.setItem('token', 'abc');
    const { result } = renderHook(() => useAuth(), { wrapper });

    storageEvent({ key: 'token', newValue: null });

    expect(result.current.isAuthenticated).toBe(false);
  });

  it('localStorage.clear() en otra pestaña (key === null) cierra la sesión', () => {
    localStorage.setItem('token', 'abc');
    const { result } = renderHook(() => useAuth(), { wrapper });

    storageEvent({ key: null });

    expect(result.current).toMatchObject({ token: null, isAuthenticated: false });
  });

  it('un newValue vacío no cuenta como token: cierra la sesión', () => {
    localStorage.setItem('token', 'abc');
    const { result } = renderHook(() => useAuth(), { wrapper });

    storageEvent({ key: 'token', newValue: '' });

    expect(result.current.isAuthenticated).toBe(false);
  });

  it('los cambios de claves ajenas se ignoran', () => {
    localStorage.setItem('token', 'abc');
    const { result } = renderHook(() => useAuth(), { wrapper });

    storageEvent({ key: 'tema', newValue: 'oscuro' });
    storageEvent({ key: 'tema', newValue: null });

    expect(result.current).toMatchObject({ token: 'abc', isAuthenticated: true });
  });

  it('deja de escuchar al desmontarse', () => {
    const add = vi.spyOn(window, 'addEventListener');
    const remove = vi.spyOn(window, 'removeEventListener');
    const { unmount } = renderHook(() => useAuth(), { wrapper });
    const registered = add.mock.calls.find(([type]) => type === 'storage')?.[1];
    expect(registered).toBeDefined();

    unmount();

    // Un listener que sobreviviera al componente sería una fuga de memoria y de estado.
    expect(remove).toHaveBeenCalledWith('storage', registered);
  });
});

describe('AuthProvider: 401 del servidor', () => {
  it('un 401 con el token actual cierra la sesión y avisa', async () => {
    localStorage.setItem('token', 'actual');
    const { result } = renderHook(() => useAuth(), { wrapper });
    fetchMock.mockImplementation(async () => errorResponse(401, 'UNAUTHORIZED', 'x'));

    await act(async () => {
      await apiFetch('/movies').catch(() => undefined);
    });

    expect(result.current.isAuthenticated).toBe(false);
    expect(localStorage.getItem('token')).toBeNull();
    expect(screen.getByText('Tu sesión ha caducado. Inicia sesión de nuevo.')).toBeInTheDocument();
  });

  it('varios 401 simultáneos cierran la sesión y avisan UNA sola vez', async () => {
    localStorage.setItem('token', 'actual');
    renderHook(() => useAuth(), { wrapper });
    fetchMock.mockImplementation(async () => errorResponse(401, 'UNAUTHORIZED', 'x'));

    await act(async () => {
      await Promise.all([
        apiFetch('/movies').catch(() => undefined),
        apiFetch('/genres').catch(() => undefined),
        apiFetch('/users/me/favorites').catch(() => undefined),
      ]);
    });

    expect(screen.getAllByText('Tu sesión ha caducado. Inicia sesión de nuevo.')).toHaveLength(1);
  });

  it('un 401 tardío de una petición hecha con un token ANTERIOR se ignora', async () => {
    localStorage.setItem('token', 'viejo');
    // Solo `/movies` contesta (cuando se suelte); el `/users/me` de cada sesión se queda pendiente.
    const slowMovies = deferred<Response>();
    const release = slowMovies.resolve;
    fetchMock.mockImplementation((input) =>
      String(input) === '/api/movies' ? slowMovies.promise : new Promise<Response>(() => {}),
    );
    const { result } = renderHook(() => useAuth(), { wrapper });

    // La petición sale con el token viejo...
    const slow = apiFetch('/movies').catch(() => undefined);
    // ...el usuario vuelve a entrar y ahora la sesión es otra...
    act(() => result.current.login('nuevo'));
    // ...y entonces llega el 401 de la petición antigua.
    await act(async () => {
      release(errorResponse(401, 'UNAUTHORIZED', 'x'));
      await slow;
    });

    expect(result.current).toMatchObject({ token: 'nuevo', isAuthenticated: true });
    expect(localStorage.getItem('token')).toBe('nuevo');
    expect(screen.queryByText('Tu sesión ha caducado. Inicia sesión de nuevo.')).not.toBeInTheDocument();
  });

  it('un 403 no cierra la sesión', async () => {
    localStorage.setItem('token', 'actual');
    const { result } = renderHook(() => useAuth(), { wrapper });
    fetchMock.mockImplementation(async () => errorResponse(403, 'ACCESS_DENIED', 'x'));

    await act(async () => {
      await apiFetch('/movies').catch(() => undefined);
    });

    expect(result.current.isAuthenticated).toBe(true);
  });

  it('al desmontar el proveedor se desconecta el puente: apiFetch ya no manda token', async () => {
    localStorage.setItem('token', 'actual');
    fetchMock.mockImplementation(async () => jsonResponse({}));
    const { unmount } = renderHook(() => useAuth(), { wrapper });
    unmount();

    await apiFetch('/movies');

    expect(lastAuthorization()).toBeNull();
  });
});

describe('AuthProvider: usuario actual (GET /users/me)', () => {
  const SESSION_EXPIRED = 'Tu sesión ha caducado. Inicia sesión de nuevo.';

  it('sin sesión no pide nada: user null, isAdmin false y userStatus "idle"', () => {
    const { result } = renderHook(() => useAuth(), { wrapper });

    expect(result.current).toMatchObject({ user: null, isAdmin: false, userStatus: 'idle' });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it.each([
    ['USER', false],
    ['ADMIN', true],
  ] as const)('tras login pide /users/me con el token nuevo; con rol %s, isAdmin es %s', async (role, expected) => {
    const me = makeUser({ role });
    serveCurrentUser({ nuevo: () => jsonResponse(me) });
    const { result } = renderHook(() => useAuth(), { wrapper });

    act(() => result.current.login('nuevo'));

    // `login` sigue siendo síncrono: la sesión ya está abierta y el usuario se carga detrás,
    // sin dar por hecho ningún rol mientras tanto.
    expect(result.current).toMatchObject({ isAuthenticated: true, user: null, isAdmin: false, userStatus: 'loading' });
    await waitFor(() => expect(result.current.userStatus).toBe('ready'));
    expect(result.current.user).toEqual(me);
    expect(result.current.isAdmin).toBe(expected);
    expect(userRequests()).toHaveLength(1);
    expect(lastAuthorization()).toBe('Bearer nuevo');
  });

  it('al recargar la página con un token guardado también pide el usuario de esa sesión', async () => {
    localStorage.setItem('token', 'guardado');
    serveCurrentUser({ guardado: () => jsonResponse(makeUser({ role: 'ADMIN' })) });

    const { result } = renderHook(() => useAuth(), { wrapper });

    expect(result.current.userStatus).toBe('loading');
    await waitFor(() => expect(result.current.isAdmin).toBe(true));
    expect(result.current.userStatus).toBe('ready');
    expect(userRequests()).toHaveLength(1);
  });

  it('logout borra el usuario y vuelve a "idle"', async () => {
    localStorage.setItem('token', 'jwt');
    serveCurrentUser({ jwt: () => jsonResponse(makeUser({ role: 'ADMIN' })) });
    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.isAdmin).toBe(true));

    act(() => result.current.logout());

    expect(result.current).toMatchObject({ user: null, isAdmin: false, userStatus: 'idle' });
  });

  it('un token nuevo desde otra pestaña olvida al usuario anterior y carga el de la nueva sesión', async () => {
    localStorage.setItem('token', 'admin');
    serveCurrentUser({
      admin: () => jsonResponse(makeUser({ username: 'jefa', role: 'ADMIN' })),
      normal: () => jsonResponse(makeUser({ username: 'luis', role: 'USER' })),
    });
    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.isAdmin).toBe(true));

    storageEvent({ key: 'token', newValue: 'normal' });

    // Ni un solo render con el administrador anterior asignado a la sesión nueva.
    expect(result.current).toMatchObject({ user: null, isAdmin: false, userStatus: 'loading' });
    await waitFor(() => expect(result.current.user?.username).toBe('luis'));
    expect(result.current.isAdmin).toBe(false);
  });

  it.each(['antes', 'después'] as const)(
    'una respuesta tardía de /users/me del token ANTERIOR se ignora (llega %s que la de la sesión nueva)',
    async (order) => {
      localStorage.setItem('token', 'viejo');
      const oldUser = deferred<Response>();
      const newUser = deferred<Response>();
      serveCurrentUser({ viejo: () => oldUser.promise, nuevo: () => newUser.promise });
      const { result } = renderHook(() => useAuth(), { wrapper });

      // Mientras la petición del token viejo sigue en vuelo, se entra con otra cuenta.
      act(() => result.current.login('nuevo'));
      expect(userRequests()[0][1]?.signal?.aborted).toBe(true);

      const lateAdmin = () => jsonResponse(makeUser({ username: 'admin-anterior', role: 'ADMIN' }));
      const freshUser = () => jsonResponse(makeUser({ username: 'nueva', role: 'USER' }));
      if (order === 'antes') {
        await act(async () => oldUser.resolve(lateAdmin()));
        expect(result.current).toMatchObject({ user: null, isAdmin: false, userStatus: 'loading' });
        await act(async () => newUser.resolve(freshUser()));
      } else {
        await act(async () => newUser.resolve(freshUser()));
        await waitFor(() => expect(result.current.userStatus).toBe('ready'));
        await act(async () => oldUser.resolve(lateAdmin()));
      }

      await waitFor(() => expect(result.current.userStatus).toBe('ready'));
      expect(result.current.user?.username).toBe('nueva');
      expect(result.current.isAdmin).toBe(false);
    },
  );

  it.each([
    ['red caída', () => Promise.reject(new TypeError('Failed to fetch'))],
    ['error 500', () => errorResponse(500, 'INTERNAL_ERROR', 'boom')],
  ] as const)(
    'si /users/me falla (%s): la sesión se mantiene, isAdmin false (falla cerrado) y refreshUser reintenta',
    async (_case, failure) => {
      localStorage.setItem('token', 'jwt');
      serveCurrentUser({ jwt: failure as () => Promise<Response> | Response });
      const { result } = renderHook(() => useAuth(), { wrapper });

      await waitFor(() => expect(result.current.userStatus).toBe('error'));
      expect(result.current).toMatchObject({ token: 'jwt', isAuthenticated: true, user: null, isAdmin: false });
      expect(localStorage.getItem('token')).toBe('jwt');
      expect(screen.queryByText(SESSION_EXPIRED)).not.toBeInTheDocument();

      const admin: User = makeUser({ role: 'ADMIN' });
      serveCurrentUser({ jwt: () => jsonResponse(admin) });
      act(() => result.current.refreshUser());

      expect(result.current.userStatus).toBe('loading');
      await waitFor(() => expect(result.current.userStatus).toBe('ready'));
      expect(result.current.isAdmin).toBe(true);
      expect(userRequests()).toHaveLength(2);
    },
  );

  it('un 401 en /users/me cierra la sesión UNA sola vez, aunque coincida con otros 401', async () => {
    localStorage.setItem('token', 'caducado');
    fetchMock.mockImplementation(async () => errorResponse(401, 'UNAUTHORIZED', 'x'));
    const { result } = renderHook(() => useAuth(), { wrapper });

    await act(async () => {
      await apiFetch('/movies').catch(() => undefined);
    });

    await waitFor(() => expect(result.current.isAuthenticated).toBe(false));
    expect(userRequests()).toHaveLength(1);
    expect(screen.getAllByText(SESSION_EXPIRED)).toHaveLength(1);
    expect(result.current).toMatchObject({ user: null, isAdmin: false, userStatus: 'idle' });
    expect(localStorage.getItem('token')).toBeNull();
  });
});

describe('AuthProvider: almacenamiento no disponible', () => {
  it('si leer localStorage lanza (modo privado), arranca sin sesión en lugar de romper', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new DOMException('Acceso denegado', 'SecurityError');
    });

    const { result } = renderHook(() => useAuth(), { wrapper });

    expect(result.current.isAuthenticated).toBe(false);
  });

  it('si escribir lanza, login y logout siguen funcionando en memoria', () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('Cuota excedida', 'QuotaExceededError');
    });
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new DOMException('Acceso denegado', 'SecurityError');
    });
    const { result } = renderHook(() => useAuth(), { wrapper });

    act(() => result.current.login('abc'));
    expect(result.current).toMatchObject({ token: 'abc', isAuthenticated: true });

    act(() => result.current.logout());
    expect(result.current.isAuthenticated).toBe(false);
  });
});
