/**
 * Tests de `AuthProvider` / `useAuth`: la única fuente de verdad de la sesión.
 *
 * Protegen el modelo de sesión por cookie HttpOnly:
 * - No hay token en JavaScript: nunca se escribe en `localStorage`, y el que dejaron
 *   versiones anteriores se borra al arrancar.
 * - La sesión se descubre al arrancar con `GET /api/users/me` (200 = hay sesión,
 *   401 = se intenta renovar con el refresh token y, si tampoco, no hay, SIN aviso
 *   de «sesión caducada»; red/5xx = error recuperable).
 * - Un 401 con la sesión abierta primero intenta renovarla (refresh) en silencio.
 * - `login` llama a `/auth/login` y carga el usuario; `logout` NO es optimista: la
 *   sesión solo se cierra en la interfaz cuando el servidor confirma (2xx o 401);
 *   ante red/5xx reintenta una vez y, si sigue fallando, la mantiene y avisa
 *   (la cookie HttpOnly sigue valiendo: decir «cerrada» sería mentir).
 * - Un 401 en un endpoint autenticado con la sesión abierta la cierra UNA sola vez,
 *   y solo si es de la sesión actual.
 * - `isAdmin` falla cerrado y el usuario de una sesión anterior nunca se cuela en la nueva.
 *
 * Se usa el `apiFetch` real con `fetch` simulado para probar el puente `configureAuth`
 * de punta a punta.
 */
import { QueryClientProvider } from '@tanstack/react-query';
import { act, renderHook, screen, waitFor } from '@testing-library/react';
import type { ReactNode } from 'react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, SESSION_CHANNEL_NAME, apiFetch } from '../lib/api';
import { SESSION_CLOSED_ELSEWHERE, SESSION_SWITCHED_ELSEWHERE } from '../lib/sessionMessages';
import { LOGOUT_FAILED_NETWORK, LOGOUT_FAILED_OTHER, LOGOUT_FAILED_SERVER, LOGOUT_RETRY_DELAY_MS } from '../lib/logout';
import type { User } from '../lib/types';
import { installManualTimers, passTime } from '../test/fakeTimers';
import { setPageVisibility } from '../test/pageVisibility';
import { installFakeLocks } from '../test/webLocks';
import {
  REFRESH_SESSION,
  errorResponse,
  jsonResponse,
  makeUser,
  noContentResponse,
  sessionExpiredResponse,
} from '../test/helpers';
import { AuthProvider, useAuth } from './AuthContext';
import { ToastProvider } from './ToastContext';
import { createTestQueryClient } from '../test/queryClient';

const fetchMock = vi.fn<typeof fetch>();

const SESSION_EXPIRED = 'Tu sesión ha caducado. Inicia sesión de nuevo.';
const UNAUTHORIZED = () => errorResponse(401, 'UNAUTHORIZED', 'No autenticado.');

/** Caché de datos nueva en cada test (`AuthProvider` la vacía al cambiar de sesión). */
let queryClient = createTestQueryClient();

function wrapper({ children }: { children: ReactNode }) {
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>
        <AuthProvider>{children}</AuthProvider>
      </ToastProvider>
    </QueryClientProvider>
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
 *
 * Única ruta por defecto: `POST /api/auth/refresh` responde 401 `SESSION_EXPIRED`
 * (no hay sesión que renovar), así un 401 simulado sigue significando «sesión
 * caducada». Los tests de la renovación la sobrescriben.
 */
function serve(routes: Record<string, Handler>) {
  const withDefaults: Record<string, Handler> = { [REFRESH_SESSION]: sessionExpiredResponse, ...routes };
  fetchMock.mockImplementation(async (input, init) => {
    const key = `${init?.method ?? 'GET'} ${String(input)}`;
    const handler = withDefaults[key];
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
  queryClient = createTestQueryClient();
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
    // Antes de rendirse intentó renovar UNA vez (el servidor dijo SESSION_EXPIRED) y no repitió /users/me.
    expect(callsTo('/api/auth/refresh', 'POST')).toHaveLength(1);
    expect(callsTo('/api/users/me')).toHaveLength(1);
    // Sin sesión no hay nada que cerrar: no se llama a /auth/logout.
    expect(callsTo('/api/auth/logout', 'POST')).toHaveLength(0);
  });

  it('«vuelves al día siguiente»: /users/me da 401 pero el refresh token sigue valiendo → se renueva y se entra sin login ni aviso', async () => {
    const me = makeUser({ role: 'ADMIN' });
    let renewed = false;
    serve({
      'GET /api/users/me': () => (renewed ? jsonResponse(me) : UNAUTHORIZED()),
      'POST /api/auth/refresh': () => {
        renewed = true;
        return noContentResponse();
      },
    });

    const { result } = renderHook(() => useAuth(), { wrapper });

    // Todo ocurre dentro de «Comprobando tu sesión...»: ni se manda al login ni se pinta la app.
    expect(result.current).toMatchObject({ isCheckingSession: true, isAuthenticated: false });
    await waitFor(() => expect(result.current.isAuthenticated).toBe(true));
    expect(result.current).toMatchObject({ user: me, isAdmin: true, userStatus: 'ready', sessionCheckFailed: false });
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(['/api/users/me', '/api/auth/refresh', '/api/users/me']);
    expect(screen.queryByText(SESSION_EXPIRED)).not.toBeInTheDocument();
  });

  it.each([
    ['red caída', () => Promise.reject(new TypeError('Failed to fetch'))],
    ['error 500', () => errorResponse(500, 'INTERNAL_ERROR', 'boom')],
    ['429', () => errorResponse(429, 'RATE_LIMIT_EXCEEDED', 'x', {}, { 'Retry-After': '30' })],
  ] as const)(
    'si en el arranque el refresh falla (%s): no se sabe si hay sesión → error recuperable, ni login ni aviso',
    async (_case, failure) => {
      serve({ 'GET /api/users/me': UNAUTHORIZED, 'POST /api/auth/refresh': failure as Handler });

      const { result } = renderHook(() => useAuth(), { wrapper });

      await waitFor(() => expect(result.current.sessionCheckFailed).toBe(true));
      expect(result.current).toMatchObject({ isAuthenticated: false, isCheckingSession: false, userStatus: 'error' });
      expect(screen.queryByText(SESSION_EXPIRED)).not.toBeInTheDocument();
    },
  );

  it('con Web Locks (navigator.locks) el arranque también renueva la sesión', async () => {
    const locks = installFakeLocks();
    let renewed = false;
    serve({
      'GET /api/users/me': () => (renewed ? jsonResponse(makeUser()) : UNAUTHORIZED()),
      'POST /api/auth/refresh': () => {
        renewed = true;
        return noContentResponse();
      },
    });

    try {
      const { result } = renderHook(() => useAuth(), { wrapper });

      await waitFor(() => expect(result.current.isAuthenticated).toBe(true));
      expect(locks.requested).toEqual(['streambox-session']);
    } finally {
      locks.uninstall();
    }
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

describe('AuthProvider: logout (lo confirma el servidor)', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('NO es optimista: la sesión sigue abierta hasta que el servidor confirma (204) y entonces se cierra', async () => {
    const serverLogout = deferred<Response>();
    serve({
      'GET /api/users/me': () => jsonResponse(makeUser({ role: 'ADMIN' })),
      'POST /api/auth/logout': () => serverLogout.promise,
    });
    const { result } = await startApp();
    expect(result.current).toMatchObject({ isAdmin: true, isLoggingOut: false });

    let done!: Promise<boolean>;
    act(() => {
      done = result.current.logout();
    });

    // El servidor aún no ha contestado: la cookie sigue valiendo, así que la interfaz no dice lo contrario.
    expect(result.current).toMatchObject({ isAuthenticated: true, isAdmin: true, isLoggingOut: true });
    const [, init] = callsTo('/api/auth/logout', 'POST')[0];
    expect(new Headers(init?.headers).get('X-Requested-With')).toBe('StreamBox');

    let closed: boolean | undefined;
    await act(async () => {
      serverLogout.resolve(noContentResponse());
      closed = await done;
    });

    expect(closed).toBe(true);
    expect(result.current).toMatchObject({
      isAuthenticated: false,
      user: null,
      isAdmin: false,
      userStatus: 'idle',
      isLoggingOut: false,
    });
    // Cerrar sesión por decisión propia no es «sesión caducada».
    expect(screen.queryByText(SESSION_EXPIRED)).not.toBeInTheDocument();
  });

  it('un doble clic mientras el cierre está en curso NO lanza una segunda petición', async () => {
    const serverLogout = deferred<Response>();
    serve({
      'GET /api/users/me': () => jsonResponse(makeUser()),
      'POST /api/auth/logout': () => serverLogout.promise,
    });
    const { result } = await startApp();

    let first!: Promise<boolean>;
    let second!: Promise<boolean>;
    act(() => {
      // Las dos en el mismo tic: el estado de React aún no se ha actualizado entre ellas.
      first = result.current.logout();
      second = result.current.logout();
    });

    await expect(second).resolves.toBe(false);
    await act(async () => {
      serverLogout.resolve(noContentResponse());
      await first;
    });
    expect(callsTo('/api/auth/logout', 'POST')).toHaveLength(1);
    expect(result.current.isAuthenticated).toBe(false);
  });

  it.each([
    ['red caída', () => Promise.reject(new TypeError('Failed to fetch')), LOGOUT_FAILED_NETWORK],
    ['error 500 del backend', () => errorResponse(500, 'INTERNAL_ERROR', 'x'), LOGOUT_FAILED_SERVER],
    // Con el backend parado, el proxy (Vite o nginx) responde 502/503/504: tampoco se llegó a él.
    ['502 del proxy', () => new Response('Bad Gateway', { status: 502 }), LOGOUT_FAILED_NETWORK],
    ['503 del proxy', () => errorResponse(503, 'SERVICE_UNAVAILABLE', 'x'), LOGOUT_FAILED_NETWORK],
    ['504 del proxy', () => new Response('', { status: 504 }), LOGOUT_FAILED_NETWORK],
  ] as const)(
    'si falla (%s): reintenta UNA vez tras la espera y, si vuelve a fallar, mantiene la sesión y avisa',
    async (_case, failure, message) => {
      serve({
        'GET /api/users/me': () => jsonResponse(makeUser({ role: 'ADMIN' })),
        'POST /api/auth/logout': failure as Handler,
      });
      const { result } = await startApp();
      installManualTimers();

      let done!: Promise<boolean>;
      act(() => {
        done = result.current.logout();
      });
      await act(() => vi.advanceTimersByTimeAsync(0));
      expect(callsTo('/api/auth/logout', 'POST')).toHaveLength(1);
      // Esperando el reintento: sigue «cerrando», con la sesión abierta.
      expect(result.current).toMatchObject({ isAuthenticated: true, isLoggingOut: true });

      await act(() => vi.advanceTimersByTimeAsync(LOGOUT_RETRY_DELAY_MS - 1));
      expect(callsTo('/api/auth/logout', 'POST')).toHaveLength(1);
      await act(() => vi.advanceTimersByTimeAsync(1));
      expect(callsTo('/api/auth/logout', 'POST')).toHaveLength(2);

      let closed: boolean | undefined;
      await act(async () => {
        closed = await done;
      });
      expect(closed).toBe(false);
      // No se miente: la cookie sigue valiendo, así que la sesión (y el rol) siguen en pantalla.
      expect(result.current).toMatchObject({ isAuthenticated: true, isAdmin: true, userStatus: 'ready', isLoggingOut: false });
      expect(screen.getByText(message)).toBeInTheDocument();
      expect(screen.queryByText(SESSION_EXPIRED)).not.toBeInTheDocument();
      // Y no hay más reintentos automáticos.
      await act(() => vi.advanceTimersByTimeAsync(LOGOUT_RETRY_DELAY_MS * 5));
      expect(callsTo('/api/auth/logout', 'POST')).toHaveLength(2);
    },
  );

  it('si el reintento sale bien, la sesión se cierra sin ningún aviso de error', async () => {
    let calls = 0;
    serve({
      'GET /api/users/me': () => jsonResponse(makeUser()),
      'POST /api/auth/logout': () =>
        ++calls === 1 ? Promise.reject(new TypeError('Failed to fetch')) : noContentResponse(),
    });
    const { result } = await startApp();
    installManualTimers();

    let done!: Promise<boolean>;
    act(() => {
      done = result.current.logout();
    });
    await act(() => vi.advanceTimersByTimeAsync(LOGOUT_RETRY_DELAY_MS));
    let closed: boolean | undefined;
    await act(async () => {
      closed = await done;
    });

    expect(closed).toBe(true);
    expect(calls).toBe(2);
    expect(result.current.isAuthenticated).toBe(false);
    expect(screen.queryByText(LOGOUT_FAILED_NETWORK)).not.toBeInTheDocument();
  });

  it('tras un fallo, el usuario puede volver a intentarlo y entonces se cierra', async () => {
    let serverUp = false;
    serve({
      'GET /api/users/me': () => jsonResponse(makeUser()),
      'POST /api/auth/logout': () => (serverUp ? noContentResponse() : errorResponse(502, 'HTTP_502', 'x')),
    });
    const { result } = await startApp();
    installManualTimers();

    let first!: Promise<boolean>;
    act(() => {
      first = result.current.logout();
    });
    await act(() => vi.advanceTimersByTimeAsync(LOGOUT_RETRY_DELAY_MS));
    await act(async () => {
      await first;
    });
    expect(result.current.isAuthenticated).toBe(true);

    serverUp = true;
    let closed: boolean | undefined;
    await act(async () => {
      closed = await result.current.logout();
    });

    expect(closed).toBe(true);
    expect(callsTo('/api/auth/logout', 'POST')).toHaveLength(3);
    expect(result.current.isAuthenticated).toBe(false);
  });

  it('un 401 al cerrar significa que la sesión ya no era válida: se trata como cerrada (sin reintento ni aviso)', async () => {
    serve({
      'GET /api/users/me': () => jsonResponse(makeUser()),
      'POST /api/auth/logout': UNAUTHORIZED,
    });
    const { result } = await startApp();

    let closed: boolean | undefined;
    await act(async () => {
      closed = await result.current.logout();
    });

    expect(closed).toBe(true);
    expect(result.current).toMatchObject({ isAuthenticated: false, userStatus: 'idle', isLoggingOut: false });
    expect(callsTo('/api/auth/logout', 'POST')).toHaveLength(1);
    expect(screen.queryByText(SESSION_EXPIRED)).not.toBeInTheDocument();
  });

  it('un rechazo que no es pasajero (403) no se reintenta: mantiene la sesión y avisa sin inventar el motivo', async () => {
    serve({
      'GET /api/users/me': () => jsonResponse(makeUser()),
      'POST /api/auth/logout': () => errorResponse(403, 'CSRF_REJECTED', 'x'),
    });
    const { result } = await startApp();

    let closed: boolean | undefined;
    await act(async () => {
      closed = await result.current.logout();
    });

    expect(closed).toBe(false);
    expect(result.current.isAuthenticated).toBe(true);
    expect(callsTo('/api/auth/logout', 'POST')).toHaveLength(1);
    expect(screen.getByText(LOGOUT_FAILED_OTHER)).toBeInTheDocument();
  });

  it('si otra petición recibe un 401 mientras se cierra, manda ese cierre (un solo aviso) y la respuesta del logout ya no cambia nada', async () => {
    const serverLogout = deferred<Response>();
    let logoutCalls = 0;
    serve({
      'GET /api/users/me': () => jsonResponse(makeUser()),
      'GET /api/movies': UNAUTHORIZED,
      // La primera es la del usuario; la segunda, la limpieza best-effort del cierre por 401.
      'POST /api/auth/logout': () => (++logoutCalls === 1 ? serverLogout.promise : noContentResponse()),
    });
    const { result } = await startApp();
    installManualTimers();

    let done!: Promise<boolean>;
    act(() => {
      done = result.current.logout();
    });
    let movies!: Promise<unknown>;
    act(() => {
      movies = apiFetch('/movies').catch(() => undefined);
    });
    await act(() => vi.advanceTimersByTimeAsync(0));
    // El refresh de ese 401 espera en la fila de sesión a que el logout conteste:
    // así su respuesta nunca puede llegar DESPUÉS de la del logout y resucitar la sesión.
    expect(callsTo('/api/auth/refresh', 'POST')).toHaveLength(0);
    expect(result.current.isAuthenticated).toBe(true);

    await act(async () => {
      serverLogout.resolve(errorResponse(500, 'INTERNAL_ERROR', 'x'));
      await movies;
    });
    // El logout falló, pero el refresh posterior confirma que no hay sesión que renovar: caducada.
    expect(callsTo('/api/auth/refresh', 'POST')).toHaveLength(1);
    expect(result.current.isAuthenticated).toBe(false);

    let closed: boolean | undefined;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(LOGOUT_RETRY_DELAY_MS);
      closed = await done;
    });

    // La sesión ya estaba cerrada por el 401: ni reintento, ni aviso de fallo, ni dos avisos.
    expect(closed).toBe(true);
    expect(result.current).toMatchObject({ isAuthenticated: false, isLoggingOut: false });
    expect(screen.getAllByText(SESSION_EXPIRED)).toHaveLength(1);
    expect(screen.queryByText(LOGOUT_FAILED_SERVER)).not.toBeInTheDocument();
    // El del usuario y la limpieza del cierre por 401; ningún reintento.
    expect(callsTo('/api/auth/logout', 'POST')).toHaveLength(2);
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

  it('un 401 que el refresh no arregla (SESSION_EXPIRED) cierra la sesión, avisa y limpia la cookie caducada', async () => {
    const { result } = await startSignedIn();

    await act(async () => {
      await apiFetch('/movies').catch(() => undefined);
    });

    expect(callsTo('/api/auth/refresh', 'POST')).toHaveLength(1);
    expect(result.current).toMatchObject({ isAuthenticated: false, user: null, isAdmin: false, userStatus: 'idle' });
    expect(screen.getByText(SESSION_EXPIRED)).toBeInTheDocument();
    await waitFor(() => expect(callsTo('/api/auth/logout', 'POST')).toHaveLength(1));
  });

  it('a los 15 minutos (401) con el refresh token válido: se renueva en silencio y la sesión sigue igual', async () => {
    const me = makeUser({ role: 'ADMIN' });
    let renewed = false;
    serve({
      'GET /api/users/me': () => jsonResponse(me),
      'GET /api/movies': () => (renewed ? jsonResponse([]) : UNAUTHORIZED()),
      'POST /api/auth/refresh': () => {
        renewed = true;
        return noContentResponse();
      },
    });
    const { result } = await startApp();

    let movies: unknown;
    await act(async () => {
      movies = await apiFetch('/movies');
    });

    expect(movies).toEqual([]);
    expect(result.current).toMatchObject({ isAuthenticated: true, user: me, isAdmin: true, userStatus: 'ready' });
    expect(screen.queryByText(SESSION_EXPIRED)).not.toBeInTheDocument();
    expect(callsTo('/api/auth/logout', 'POST')).toHaveLength(0);
  });

  it('si el refresh falla por el servidor (500) la sesión NO se cierra: la petición falla con un error que se puede reintentar', async () => {
    serve({
      'GET /api/users/me': () => jsonResponse(makeUser()),
      'GET /api/movies': UNAUTHORIZED,
      'POST /api/auth/refresh': () => errorResponse(500, 'INTERNAL_ERROR', 'x'),
    });
    const { result } = await startApp();

    let error: unknown;
    await act(async () => {
      error = await apiFetch('/movies').catch((e: unknown) => e);
    });

    expect(error).toMatchObject({ status: 500, sessionExpired: false });
    expect(result.current.isAuthenticated).toBe(true);
    expect(screen.queryByText(SESSION_EXPIRED)).not.toBeInTheDocument();
  });

  it('tras cerrar sesión, un 401 de una petición de la sesión cerrada no pide refresh (no la resucita)', async () => {
    const slowMovies = deferred<Response>();
    serve({
      'GET /api/users/me': () => jsonResponse(makeUser()),
      'GET /api/movies': () => slowMovies.promise,
      'POST /api/auth/logout': () => noContentResponse(),
      'POST /api/auth/refresh': () => noContentResponse(),
    });
    const { result } = await startApp();

    const slow = apiFetch('/movies').catch(() => undefined);
    await act(async () => result.current.logout());
    await act(async () => {
      slowMovies.resolve(UNAUTHORIZED());
      await slow;
    });

    expect(callsTo('/api/auth/refresh', 'POST')).toHaveLength(0);
    expect(result.current.isAuthenticated).toBe(false);
    expect(screen.queryByText(SESSION_EXPIRED)).not.toBeInTheDocument();
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

describe('AuthProvider: cambios de sesión en OTRA pestaña (session-changed)', () => {
  /** Canal de «otra pestaña»: lo que recibe y una forma de enviarle mensajes. */
  let otherTab: BroadcastChannel;
  let received: unknown[];

  beforeEach(() => {
    otherTab = new BroadcastChannel(SESSION_CHANNEL_NAME);
    received = [];
    otherTab.onmessage = (event: MessageEvent) => received.push(event.data);
  });

  afterEach(() => {
    otherTab.close();
    // Por si un test con reloj falso falla antes de devolverlo: que no contagie al siguiente.
    vi.useRealTimers();
  });

  /** Espera a que el canal (asíncrono, como en el navegador) entregue lo pendiente. */
  async function flushChannel() {
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
  }

  /** «La otra pestaña» anuncia un cambio de sesión. */
  async function announceFromOtherTab() {
    await act(async () => {
      otherTab.postMessage({ type: 'session-changed' });
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
  }

  it('al iniciar sesión se anuncia SOLO el tipo de mensaje, sin identidad ni datos', async () => {
    let loggedIn = false;
    serve({
      'GET /api/users/me': () => (loggedIn ? jsonResponse(makeUser({ username: 'carlos', id: 2 })) : UNAUTHORIZED()),
      'POST /api/auth/login': () => {
        loggedIn = true;
        return noContentResponse();
      },
    });
    const { result } = await startApp();
    // El arranque sin sesión (401) no es un cambio: no se anuncia nada.
    await flushChannel();
    expect(received).toEqual([]);

    await act(async () => result.current.login('carlos@example.com', 'secreta'));
    await flushChannel();

    expect(received).toEqual([{ type: 'session-changed' }]);
  });

  it('al cerrar sesión (confirmado) se anuncia; si el servidor no lo confirma, no', async () => {
    let serverUp = false;
    serve({
      'GET /api/users/me': () => jsonResponse(makeUser()),
      'POST /api/auth/logout': () => (serverUp ? noContentResponse() : errorResponse(403, 'CSRF_REJECTED', 'x')),
    });
    const { result } = await startApp();

    await act(async () => {
      await result.current.logout();
    });
    await flushChannel();
    // La sesión sigue abierta (403): no ha cambiado nada para las demás pestañas.
    expect(received).toEqual([]);

    serverUp = true;
    await act(async () => {
      await result.current.logout();
    });
    await flushChannel();
    expect(received).toEqual([{ type: 'session-changed' }]);
  });

  it('al descubrir que la sesión caducó (401 + refresh 401) se anuncia una vez', async () => {
    serve({
      'GET /api/users/me': () => jsonResponse(makeUser()),
      'POST /api/auth/logout': () => noContentResponse(),
      'GET /api/movies': () => UNAUTHORIZED(),
    });
    await startApp();

    await act(async () => {
      await Promise.all([apiFetch('/movies').catch(() => undefined), apiFetch('/movies').catch(() => undefined)]);
    });
    await flushChannel();

    expect(received).toEqual([{ type: 'session-changed' }]);
  });

  it('si en otra pestaña entra OTRA cuenta: vacía la caché, vuelve a preguntar y avisa de quién está dentro (sin «sesión caducada» ni reenviar el aviso)', async () => {
    let me = makeUser({ id: 1, username: 'ana' });
    serve({ 'GET /api/users/me': () => jsonResponse(me) });
    const { result } = await startApp();
    queryClient.setQueryData(['favorites'], { movie: [{ id: 99 }], series: [] });

    me = makeUser({ id: 2, username: 'carlos', email: 'carlos@example.com' });
    await announceFromOtherTab();

    // Nada de la sesión anterior queda en la caché.
    expect(queryClient.getQueryData(['favorites'])).toBeUndefined();
    await waitFor(() => expect(result.current.user).toEqual(me));
    expect(result.current.isAuthenticated).toBe(true);
    expect(callsTo('/api/users/me')).toHaveLength(2);
    expect(await screen.findByText(SESSION_SWITCHED_ELSEWHERE('carlos'))).toBeInTheDocument();
    expect(screen.queryByText(SESSION_EXPIRED)).not.toBeInTheDocument();
    // Quien recibe el aviso no lo reenvía: sin bucles entre pestañas.
    await flushChannel();
    expect(received).toEqual([]);
  });

  it('si es la MISMA cuenta (p. ej. salió y volvió a entrar allí), no avisa de nada', async () => {
    serve({ 'GET /api/users/me': () => jsonResponse(makeUser({ id: 1 })) });
    const { result } = await startApp();

    await announceFromOtherTab();

    await waitFor(() => expect(callsTo('/api/users/me')).toHaveLength(2));
    await waitFor(() => expect(result.current.userStatus).toBe('ready'));
    expect(result.current.isAuthenticated).toBe(true);
    expect(screen.queryByText(/otra pestaña/)).not.toBeInTheDocument();
  });

  it('si en otra pestaña se cerró la sesión: queda sin sesión y dice que se cerró allí (no que caducó), sin pedir otro logout', async () => {
    let loggedIn = true;
    serve({ 'GET /api/users/me': () => (loggedIn ? jsonResponse(makeUser()) : UNAUTHORIZED()) });
    const { result } = await startApp();
    queryClient.setQueryData(['favorites'], { movie: [{ id: 99 }], series: [] });

    loggedIn = false;
    await announceFromOtherTab();

    await waitFor(() => expect(result.current.isAuthenticated).toBe(false));
    expect(result.current).toMatchObject({ user: null, userStatus: 'idle', isCheckingSession: false });
    expect(queryClient.getQueryData(['favorites'])).toBeUndefined();
    expect(await screen.findByText(SESSION_CLOSED_ELSEWHERE)).toBeInTheDocument();
    expect(screen.queryByText(SESSION_EXPIRED)).not.toBeInTheDocument();
    // Lo cerró la otra pestaña; esta no repite el logout ni reenvía el aviso.
    expect(callsTo('/api/auth/logout', 'POST')).toHaveLength(0);
    await flushChannel();
    expect(received).toEqual([]);
  });

  it('con esta pestaña OCULTA, el aviso «se cerró en otra pestaña» no caduca: al volver está y dura sus 5 s', async () => {
    // Reloj falso desde el principio: el aviso no debe caducar aunque «pase» mucho más que su duración.
    installManualTimers({ withDate: true });
    let loggedIn = true;
    serve({ 'GET /api/users/me': () => (loggedIn ? jsonResponse(makeUser()) : UNAUTHORIZED()) });
    const { result } = await startApp();

    setPageVisibility('hidden');
    loggedIn = false;
    await act(async () => {
      otherTab.postMessage({ type: 'session-changed' });
      // El canal entrega en otra vuelta del bucle de eventos; `setImmediate` no lo falsea el reloj manual.
      await new Promise((resolve) => setImmediate(resolve));
    });
    await waitFor(() => expect(result.current.isAuthenticated).toBe(false));

    // El usuario sigue en la otra pestaña bastante más que los 5 s del aviso.
    passTime(60_000);
    setPageVisibility('visible');

    expect(screen.getByRole('status')).toHaveTextContent(SESSION_CLOSED_ELSEWHERE);
    passTime(4999);
    expect(screen.getByText(SESSION_CLOSED_ELSEWHERE)).toBeInTheDocument();
    passTime(1);
    expect(screen.queryByText(SESSION_CLOSED_ELSEWHERE)).not.toBeInTheDocument();
  });

  it('una pestaña sin sesión (en el login) descubre la sesión iniciada en otra y avisa de quién ha entrado', async () => {
    let loggedIn = false;
    serve({
      'GET /api/users/me': () => (loggedIn ? jsonResponse(makeUser({ username: 'carlos' })) : UNAUTHORIZED()),
    });
    const { result } = await startApp();
    expect(result.current.isAuthenticated).toBe(false);

    loggedIn = true;
    await announceFromOtherTab();

    await waitFor(() => expect(result.current.isAuthenticated).toBe(true));
    expect(await screen.findByText(SESSION_SWITCHED_ELSEWHERE('carlos'))).toBeInTheDocument();
  });

  it('sin sesión en ninguna parte (otra pestaña sin sesión anuncia algo): no avisa de nada', async () => {
    serve({ 'GET /api/users/me': () => UNAUTHORIZED() });
    const { result } = await startApp();

    await announceFromOtherTab();

    await waitFor(() => expect(callsTo('/api/users/me')).toHaveLength(2));
    await waitFor(() => expect(result.current.isCheckingSession).toBe(false));
    expect(result.current.isAuthenticated).toBe(false);
    expect(screen.queryByText(/otra pestaña/)).not.toBeInTheDocument();
    expect(screen.queryByText(SESSION_EXPIRED)).not.toBeInTheDocument();
  });

  it('dos avisos seguidos (salir y entrar con otra cuenta allí): un solo aviso, el de la cuenta nueva', async () => {
    let me = makeUser({ id: 1, username: 'ana' });
    serve({ 'GET /api/users/me': () => jsonResponse(me) });
    const { result } = await startApp();

    me = makeUser({ id: 2, username: 'carlos' });
    await act(async () => {
      otherTab.postMessage({ type: 'session-changed' });
      otherTab.postMessage({ type: 'session-changed' });
      await new Promise((resolve) => setTimeout(resolve, 0));
    });

    await waitFor(() => expect(result.current.user?.id).toBe(2));
    expect(await screen.findAllByText(SESSION_SWITCHED_ELSEWHERE('carlos'))).toHaveLength(1);
  });
});
