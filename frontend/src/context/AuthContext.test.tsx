/**
 * Tests de `AuthProvider` / `useAuth`: la única fuente de verdad de la sesión.
 *
 * Protegen el modelo de sesión por cookie HttpOnly:
 * - No hay token en JavaScript: nunca se escribe en `localStorage`, y el que dejaron
 *   versiones anteriores se borra al arrancar.
 * - La sesión se descubre al arrancar con `GET /api/users/me` (200 = hay sesión,
 *   401 = no hay, SIN aviso de «sesión caducada»; red/5xx = error recuperable).
 * - `login` llama a `/auth/login` y carga el usuario; `logout` limpia el estado al
 *   instante y pide borrar la cookie (best-effort).
 * - Un 401 en un endpoint autenticado con la sesión abierta la cierra UNA sola vez,
 *   y solo si es de la sesión actual.
 * - `isAdmin` falla cerrado y el usuario de una sesión anterior nunca se cuela en la nueva.
 *
 * Se usa el `apiFetch` real con `fetch` simulado para probar el puente `configureAuth`
 * de punta a punta.
 */
import { act, renderHook, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, apiFetch } from '../lib/api';
import type { User } from '../lib/types';
import { errorResponse, jsonResponse, makeUser, noContentResponse } from '../test/helpers';
import { AuthProvider, useAuth } from './AuthContext';
import { ToastProvider } from './ToastContext';

const fetchMock = vi.fn<typeof fetch>();

const SESSION_EXPIRED = 'Tu sesión ha caducado. Inicia sesión de nuevo.';
const UNAUTHORIZED = () => errorResponse(401, 'UNAUTHORIZED', 'No autenticado.');

function wrapper({ children }: { children: ReactNode }) {
  return (
    <ToastProvider>
      <AuthProvider>{children}</AuthProvider>
    </ToastProvider>
  );
}

/** Promesa controlable a mano para decidir CUÁNDO contesta el servidor. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

/** Llamadas hechas a `url` (con su método, por defecto GET). */
function callsTo(url: string, method = 'GET') {
  return fetchMock.mock.calls.filter(([u, init]) => String(u) === url && (init?.method ?? 'GET') === method);
}

type Handler = () => Response | Promise<Response>;

/**
 * Servidor simulado: cada ruta `"MÉTODO /ruta"` tiene su manejador. Lo que no se
 * declara hace fallar la llamada con un mensaje claro (nada se inventa).
 */
function serve(routes: Record<string, Handler>) {
  fetchMock.mockImplementation(async (input, init) => {
    const key = `${init?.method ?? 'GET'} ${String(input)}`;
    const handler = routes[key];
    if (!handler) throw new Error(`Petición no prevista en el test: ${key}`);
    return handler();
  });
}

/** Monta el proveedor y espera a que el chequeo inicial de la sesión termine. */
async function startApp() {
  const hook = renderHook(() => useAuth(), { wrapper });
  await waitFor(() => expect(hook.result.current.isCheckingSession).toBe(false));
  return hook;
}

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.restoreAllMocks();
});

describe('AuthProvider: arranque (se descubre la sesión con GET /users/me)', () => {
  it('con cookie válida (200): hay sesión, el usuario está cargado y no se envía Authorization', async () => {
    const me = makeUser({ role: 'ADMIN' });
    serve({ 'GET /api/users/me': () => jsonResponse(me) });

    const { result } = renderHook(() => useAuth(), { wrapper });

    // Mientras no contesta: no se sabe. Ni autenticado ni «sin sesión».
    expect(result.current).toMatchObject({
      isAuthenticated: false,
      isCheckingSession: true,
      sessionCheckFailed: false,
      userStatus: 'loading',
    });
    await waitFor(() => expect(result.current.isAuthenticated).toBe(true));
    expect(result.current).toMatchObject({ user: me, isAdmin: true, userStatus: 'ready', isCheckingSession: false });
    expect(callsTo('/api/users/me')).toHaveLength(1);
    const init = callsTo('/api/users/me')[0][1];
    expect(init?.credentials).toBe('same-origin');
    expect(new Headers(init?.headers).has('Authorization')).toBe(false);
    expect(screen.queryByText(SESSION_EXPIRED)).not.toBeInTheDocument();
  });

  it('sin sesión (401): no hay sesión y NO se avisa de «sesión caducada» en este primer chequeo', async () => {
    serve({ 'GET /api/users/me': UNAUTHORIZED });

    const { result } = await startApp();

    expect(result.current).toMatchObject({
      isAuthenticated: false,
      sessionCheckFailed: false,
      user: null,
      isAdmin: false,
      userStatus: 'idle',
    });
    expect(screen.queryByText(SESSION_EXPIRED)).not.toBeInTheDocument();
    // Sin sesión no hay nada que cerrar: no se llama a /auth/logout.
    expect(callsTo('/api/auth/logout', 'POST')).toHaveLength(0);
  });

  it.each([
    ['red caída', () => Promise.reject(new TypeError('Failed to fetch'))],
    ['error 500', () => errorResponse(500, 'INTERNAL_ERROR', 'boom')],
  ] as const)('si el chequeo falla (%s): ni sesión ni «sin sesión», error recuperable con refreshUser', async (_case, failure) => {
    serve({ 'GET /api/users/me': failure as Handler });

    const { result } = renderHook(() => useAuth(), { wrapper });

    await waitFor(() => expect(result.current.sessionCheckFailed).toBe(true));
    expect(result.current).toMatchObject({
      isAuthenticated: false,
      isCheckingSession: false,
      user: null,
      isAdmin: false,
      userStatus: 'error',
    });
    expect(screen.queryByText(SESSION_EXPIRED)).not.toBeInTheDocument();

    const me: User = makeUser({ role: 'ADMIN' });
    serve({ 'GET /api/users/me': () => jsonResponse(me) });
    act(() => result.current.refreshUser());

    // Mientras reintenta vuelve a «comprobando», no se queda en error.
    expect(result.current).toMatchObject({ isCheckingSession: true, userStatus: 'loading' });
    await waitFor(() => expect(result.current.isAuthenticated).toBe(true));
    expect(result.current).toMatchObject({ user: me, isAdmin: true, userStatus: 'ready', sessionCheckFailed: false });
  });

  it('borra el token que versiones anteriores dejaron en localStorage', async () => {
    localStorage.setItem('token', 'jwt-heredado');
    localStorage.setItem('tema', 'oscuro');
    serve({ 'GET /api/users/me': UNAUTHORIZED });

    const { result } = await startApp();

    expect(localStorage.getItem('token')).toBeNull();
    expect(localStorage.getItem('tema')).toBe('oscuro'); // solo la clave del token
    // Y el token heredado NO abre sesión: manda el servidor (aquí, 401).
    expect(result.current.isAuthenticated).toBe(false);
    const init = callsTo('/api/users/me')[0][1];
    expect(new Headers(init?.headers).has('Authorization')).toBe(false);
  });

  it('si localStorage lanza (modo privado), arranca igualmente en lugar de romper', async () => {
    vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
      throw new DOMException('Acceso denegado', 'SecurityError');
    });
    serve({ 'GET /api/users/me': () => jsonResponse(makeUser()) });

    const { result } = await startApp();

    expect(result.current.isAuthenticated).toBe(true);
  });
});

describe('AuthProvider: login', () => {
  it('llama a /auth/login (con cabecera anti-CSRF), carga el usuario y abre la sesión sin pedir /users/me dos veces', async () => {
    let loggedIn = false;
    const me = makeUser({ username: 'nueva' });
    serve({
      'GET /api/users/me': () => (loggedIn ? jsonResponse(me) : UNAUTHORIZED()),
      'POST /api/auth/login': () => {
        loggedIn = true;
        return noContentResponse();
      },
    });
    const { result } = await startApp();
    expect(result.current.isAuthenticated).toBe(false);

    await act(async () => result.current.login('ana@example.com', 'secreta'));

    expect(result.current).toMatchObject({ isAuthenticated: true, user: me, userStatus: 'ready' });
    const [, init] = callsTo('/api/auth/login', 'POST')[0];
    expect(JSON.parse(String(init?.body))).toEqual({ email: 'ana@example.com', password: 'secreta' });
    expect(new Headers(init?.headers).get('X-Requested-With')).toBe('StreamBox');
    expect(new Headers(init?.headers).has('Authorization')).toBe(false);
    // Arranque (401) + carga tras el login = 2; el efecto de carga no repite la petición.
    await waitFor(() => expect(callsTo('/api/users/me')).toHaveLength(2));
    expect(callsTo('/api/users/me')).toHaveLength(2);
    expect(screen.queryByText(SESSION_EXPIRED)).not.toBeInTheDocument();
  });

  it('nunca escribe el token (ni nada) en localStorage al iniciar y cerrar sesión', async () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    let loggedIn = false;
    serve({
      'GET /api/users/me': () => (loggedIn ? jsonResponse(makeUser()) : UNAUTHORIZED()),
      'POST /api/auth/login': () => {
        loggedIn = true;
        return noContentResponse();
      },
      'POST /api/auth/logout': () => noContentResponse(),
    });
    const { result } = await startApp();

    await act(async () => result.current.login('ana@example.com', 'secreta'));
    await act(async () => result.current.logout());

    expect(setItem).not.toHaveBeenCalled();
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it('credenciales incorrectas: login lanza el ApiError (con remainingAttempts), sigue sin sesión y NO avisa de «sesión caducada»', async () => {
    serve({
      'GET /api/users/me': UNAUTHORIZED,
      'POST /api/auth/login': () => errorResponse(401, 'INVALID_CREDENTIALS', 'x', { remainingAttempts: 3 }),
    });
    const { result } = await startApp();

    let error: unknown;
    await act(async () => {
      error = await result.current.login('ana@example.com', 'mal').catch((e: unknown) => e);
    });

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 401, code: 'INVALID_CREDENTIALS', remainingAttempts: 3 });
    expect(result.current.isAuthenticated).toBe(false);
    expect(screen.queryByText(SESSION_EXPIRED)).not.toBeInTheDocument();
    // No llegó a pedir el usuario: solo el chequeo inicial.
    expect(callsTo('/api/users/me')).toHaveLength(1);
  });

  it('un 429 del login se propaga con su Retry-After', async () => {
    serve({
      'GET /api/users/me': UNAUTHORIZED,
      'POST /api/auth/login': () =>
        errorResponse(429, 'ACCOUNT_LOCKED', 'x', {}, { 'Retry-After': '120' }),
    });
    const { result } = await startApp();

    let error: unknown;
    await act(async () => {
      error = await result.current.login('ana@example.com', 'x').catch((e: unknown) => e);
    });

    expect(error).toMatchObject({ status: 429, code: 'ACCOUNT_LOCKED', retryAfterSeconds: 120 });
    expect(result.current.isAuthenticated).toBe(false);
  });

  it('si el login sale bien pero /users/me falla, la sesión se abre igualmente (sin rol: falla cerrado) y refreshUser reintenta', async () => {
    let loggedIn = false;
    let meWorks = false;
    serve({
      'GET /api/users/me': () => {
        if (!loggedIn) return UNAUTHORIZED();
        return meWorks ? jsonResponse(makeUser({ role: 'ADMIN' })) : errorResponse(500, 'INTERNAL_ERROR', 'boom');
      },
      'POST /api/auth/login': () => {
        loggedIn = true;
        return noContentResponse();
      },
    });
    const { result } = await startApp();

    await act(async () => result.current.login('ana@example.com', 'secreta'));
    await waitFor(() => expect(result.current.userStatus).toBe('error'));
    expect(result.current).toMatchObject({ isAuthenticated: true, user: null, isAdmin: false });

    meWorks = true;
    act(() => result.current.refreshUser());
    await waitFor(() => expect(result.current.userStatus).toBe('ready'));
    expect(result.current.isAdmin).toBe(true);
  });

  it('el usuario del chequeo de arranque nunca se queda en la sesión nueva, llegue su respuesta antes o después del login', async () => {
    for (const order of ['antes', 'después'] as const) {
      fetchMock.mockReset();
      const startup = deferred<Response>();
      const afterLogin = deferred<Response>();
      let meCalls = 0;
      serve({
        'GET /api/users/me': () => (++meCalls === 1 ? startup.promise : afterLogin.promise),
        'POST /api/auth/login': () => noContentResponse(),
      });
      const { result, unmount } = renderHook(() => useAuth(), { wrapper });

      // El chequeo inicial sigue en vuelo cuando se inicia sesión con otra cuenta.
      let loginDone!: Promise<void>;
      act(() => {
        loginDone = result.current.login('nueva@example.com', 'x');
      });
      await waitFor(() => expect(meCalls).toBe(2));

      const lateAdmin = () => jsonResponse(makeUser({ username: 'admin-anterior', role: 'ADMIN' }));
      const fresh = () => jsonResponse(makeUser({ username: 'nueva', role: 'USER' }));
      if (order === 'antes') {
        await act(async () => startup.resolve(lateAdmin()));
        await act(async () => {
          afterLogin.resolve(fresh());
          await loginDone;
        });
      } else {
        await act(async () => {
          afterLogin.resolve(fresh());
          await loginDone;
        });
        // Al abrirse la sesión nueva, la comprobación antigua en vuelo se cancela.
        expect(callsTo('/api/users/me')[0][1]?.signal?.aborted).toBe(true);
        await act(async () => startup.resolve(lateAdmin()));
      }

      expect(result.current.user?.username, order).toBe('nueva');
      expect(result.current.isAdmin, order).toBe(false);
      unmount();
    }
  });
});

describe('AuthProvider: logout', () => {
  it('limpia el estado AL INSTANTE y pide borrar la cookie (POST /auth/logout con cabecera anti-CSRF)', async () => {
    const serverLogout = deferred<Response>();
    serve({
      'GET /api/users/me': () => jsonResponse(makeUser({ role: 'ADMIN' })),
      'POST /api/auth/logout': () => serverLogout.promise,
    });
    const { result } = await startApp();
    expect(result.current.isAdmin).toBe(true);

    let done!: Promise<void>;
    act(() => {
      done = result.current.logout();
    });

    // El servidor aún no ha contestado, pero la interfaz ya no tiene sesión.
    expect(result.current).toMatchObject({ isAuthenticated: false, user: null, isAdmin: false, userStatus: 'idle' });
    const [, init] = callsTo('/api/auth/logout', 'POST')[0];
    expect(new Headers(init?.headers).get('X-Requested-With')).toBe('StreamBox');
    await act(async () => {
      serverLogout.resolve(noContentResponse());
      await done;
    });
    // Cerrar sesión por decisión propia no es «sesión caducada».
    expect(screen.queryByText(SESSION_EXPIRED)).not.toBeInTheDocument();
  });

  it('es idempotente: una segunda llamada no vuelve a pedir nada al servidor', async () => {
    serve({
      'GET /api/users/me': () => jsonResponse(makeUser()),
      'POST /api/auth/logout': () => noContentResponse(),
    });
    const { result } = await startApp();

    await act(async () => result.current.logout());
    await act(async () => result.current.logout());

    expect(callsTo('/api/auth/logout', 'POST')).toHaveLength(1);
    expect(result.current.isAuthenticated).toBe(false);
  });

  it('es best-effort: si /auth/logout falla (red o 500), no lanza y la sesión de la interfaz sigue cerrada', async () => {
    serve({
      'GET /api/users/me': () => jsonResponse(makeUser()),
      'POST /api/auth/logout': () => Promise.reject(new TypeError('Failed to fetch')),
    });
    const { result } = await startApp();

    await act(async () => {
      await expect(result.current.logout()).resolves.toBeUndefined();
    });

    expect(result.current.isAuthenticated).toBe(false);
  });
});

describe('AuthProvider: 401 del servidor con la sesión abierta', () => {
  /** Arranca con sesión y deja `/auth/logout` disponible (el cierre por 401 limpia la cookie). */
  async function startSignedIn() {
    serve({
      'GET /api/users/me': () => jsonResponse(makeUser()),
      'POST /api/auth/logout': () => noContentResponse(),
      'GET /api/movies': () => errorResponse(401, 'UNAUTHORIZED', 'x'),
      'GET /api/genres': () => errorResponse(401, 'UNAUTHORIZED', 'x'),
      'GET /api/users/me/favorites': () => errorResponse(401, 'UNAUTHORIZED', 'x'),
    });
    return startApp();
  }

  it('un 401 cierra la sesión, avisa y limpia la cookie caducada', async () => {
    const { result } = await startSignedIn();

    await act(async () => {
      await apiFetch('/movies').catch(() => undefined);
    });

    expect(result.current).toMatchObject({ isAuthenticated: false, user: null, isAdmin: false, userStatus: 'idle' });
    expect(screen.getByText(SESSION_EXPIRED)).toBeInTheDocument();
    await waitFor(() => expect(callsTo('/api/auth/logout', 'POST')).toHaveLength(1));
  });

  it('varios 401 simultáneos cierran la sesión y avisan UNA sola vez', async () => {
    await startSignedIn();

    await act(async () => {
      await Promise.all([
        apiFetch('/movies').catch(() => undefined),
        apiFetch('/genres').catch(() => undefined),
        apiFetch('/users/me/favorites').catch(() => undefined),
      ]);
    });

    expect(screen.getAllByText(SESSION_EXPIRED)).toHaveLength(1);
    expect(callsTo('/api/auth/logout', 'POST')).toHaveLength(1);
  });

  it('un 401 tardío de una petición hecha en una sesión ANTERIOR se ignora', async () => {
    const slowMovies = deferred<Response>();
    let loggedIn = true;
    serve({
      'GET /api/users/me': () => (loggedIn ? jsonResponse(makeUser()) : UNAUTHORIZED()),
      'GET /api/movies': () => slowMovies.promise,
      'POST /api/auth/logout': () => noContentResponse(),
      'POST /api/auth/login': () => noContentResponse(),
    });
    const { result } = await startApp();

    // La petición sale en la sesión vieja...
    const slow = apiFetch('/movies').catch(() => undefined);
    // ...el usuario cierra sesión y vuelve a entrar (otra sesión)...
    await act(async () => result.current.logout());
    loggedIn = true;
    await act(async () => result.current.login('ana@example.com', 'x'));
    // ...y entonces llega el 401 de la petición antigua.
    await act(async () => {
      slowMovies.resolve(errorResponse(401, 'UNAUTHORIZED', 'x'));
      await slow;
    });

    expect(result.current.isAuthenticated).toBe(true);
    expect(screen.queryByText(SESSION_EXPIRED)).not.toBeInTheDocument();
  });

  it('un 403 no cierra la sesión', async () => {
    serve({
      'GET /api/users/me': () => jsonResponse(makeUser()),
      'GET /api/movies': () => errorResponse(403, 'ACCESS_DENIED', 'x'),
    });
    const { result } = await startApp();

    await act(async () => {
      await apiFetch('/movies').catch(() => undefined);
    });

    expect(result.current.isAuthenticated).toBe(true);
    expect(screen.queryByText(SESSION_EXPIRED)).not.toBeInTheDocument();
  });

  it('al desmontar el proveedor se desconecta el puente: un 401 posterior ya no avisa a nadie', async () => {
    serve({
      'GET /api/users/me': () => jsonResponse(makeUser()),
      'GET /api/movies': () => errorResponse(401, 'UNAUTHORIZED', 'x'),
    });
    const { unmount } = await startApp();
    unmount();

    await apiFetch('/movies').catch(() => undefined);

    expect(callsTo('/api/auth/logout', 'POST')).toHaveLength(0);
  });
});

describe('AuthProvider: usuario actual y rol', () => {
  it.each([
    ['USER', false],
    ['ADMIN', true],
  ] as const)('con rol %s, isAdmin es %s', async (role, expected) => {
    serve({ 'GET /api/users/me': () => jsonResponse(makeUser({ role })) });

    const { result } = await startApp();

    expect(result.current.isAdmin).toBe(expected);
  });

  it('useAuth fuera de AuthProvider lanza un error claro', () => {
    // React registra con console.error el error de render; es lo esperado aquí.
    vi.spyOn(console, 'error').mockImplementation(() => {});

    expect(() => renderHook(() => useAuth())).toThrow('useAuth debe usarse dentro de <AuthProvider>.');
  });
});
