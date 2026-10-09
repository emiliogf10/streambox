/**
 * Tests del cliente HTTP central (`apiFetch`, `ApiError`).
 *
 * Protegen el contrato de sesión y errores del que dependen todas las pantallas:
 * qué cabeceras viajan, cuándo un 401 cierra la sesión y cuándo NO, cómo se
 * interpretan 403/429/204/red caída y que nunca se escape una excepción cruda.
 * `fetch` se sustituye por un doble: no hay red real.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  ApiError,
  SESSION_CHANNEL_NAME,
  SESSION_LOCK_NAME,
  announceSessionChange,
  apiFetch,
  configureAuth,
  currentSessionKey,
  getErrorMessage,
  isAbortError,
  rateLimitMessage,
} from './api';
import {
  REFRESH_SESSION as REFRESH,
  errorResponse,
  jsonResponse,
  noContentResponse,
  sessionExpiredResponse as sessionExpired,
} from '../test/helpers';
import { installFakeLocks } from '../test/webLocks';
import type { FakeLocks } from '../test/webLocks';

const fetchMock = vi.fn<typeof fetch>();
const onUnauthorized = vi.fn<(usedKey: number) => void>();
const onSessionChangedElsewhere = vi.fn<() => void>();

type Handler = (init: RequestInit) => Response | Promise<Response>;

/**
 * Servidor simulado por `"MÉTODO /ruta"`. Cada llamada recibe un `Response` nuevo
 * (un cuerpo solo se puede leer una vez). Lo no declarado falla con un mensaje claro.
 */
function serve(routes: Record<string, Handler>) {
  fetchMock.mockImplementation(async (input, init = {}) => {
    const key = `${init.method ?? 'GET'} ${String(input)}`;
    const handler = routes[key];
    if (!handler) throw new Error(`Petición no prevista en el test: ${key}`);
    return handler(init);
  });
}

/** Llamadas hechas a `url` (con su método, por defecto GET). */
function callsTo(url: string, method = 'GET') {
  return fetchMock.mock.calls.filter(([u, init]) => String(u) === url && (init?.method ?? 'GET') === method);
}

/** Llamadas al refresh. */
function refreshCalls() {
  return callsTo('/api/auth/refresh', 'POST');
}

/** Promesa controlable a mano para decidir CUÁNDO contesta el servidor. */
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((res) => {
    resolve = res;
  });
  return { promise, resolve };
}

/** Deja correr las promesas y temporizadores pendientes (entrega del BroadcastChannel simulado incluida). */
function flush(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}

/** Devuelve `[url, init]` de la última llamada a `fetch`. */
function lastCall(): [string, RequestInit] {
  const call = fetchMock.mock.calls.at(-1);
  if (!call) throw new Error('fetch no se ha llamado');
  return [String(call[0]), call[1] ?? {}];
}

/** Cabeceras enviadas en la última llamada. */
function sentHeaders(): Headers {
  return new Headers(lastCall()[1].headers);
}

/** Captura el error de una promesa que debe fallar (más legible que `try/catch` en cada test). */
async function catchError(promise: Promise<unknown>): Promise<unknown> {
  try {
    await promise;
  } catch (error) {
    return error;
  }
  throw new Error('La promesa debía fallar y se resolvió');
}

const canRefresh = vi.fn<(usedKey: number) => boolean>();
/** Clave de la sesión actual que ve `apiFetch` (los tests la cambian para simular otro login/logout). */
let currentKey = 7;

beforeEach(() => {
  fetchMock.mockReset();
  onUnauthorized.mockReset();
  onSessionChangedElsewhere.mockReset();
  canRefresh.mockReset();
  canRefresh.mockReturnValue(true);
  currentKey = 7;
  vi.stubGlobal('fetch', fetchMock);
  configureAuth({ getSessionKey: () => currentKey, onUnauthorized, canRefresh, onSessionChangedElsewhere });
});

afterEach(() => {
  configureAuth(null);
});

describe('apiFetch: petición saliente', () => {
  it('la sesión viaja en la cookie: credentials same-origin y NUNCA cabecera Authorization', async () => {
    fetchMock.mockResolvedValue(jsonResponse({}));

    await apiFetch('/movies');

    expect(lastCall()[1].credentials).toBe('same-origin');
    expect(sentHeaders().has('Authorization')).toBe(false);
    expect(sentHeaders().get('Accept')).toBe('application/json');
  });

  it('añade X-Requested-With: StreamBox a las peticiones no seguras (POST, PUT, PATCH, DELETE), también las públicas', async () => {
    fetchMock.mockImplementation(async () => jsonResponse({}));

    for (const method of ['POST', 'PUT', 'PATCH', 'DELETE', 'post']) {
      await apiFetch('/x', { method });
      expect(sentHeaders().get('X-Requested-With'), method).toBe('StreamBox');
    }
    await apiFetch('/auth/login', { method: 'POST', body: { email: 'a@b.c' }, public: true });
    expect(sentHeaders().get('X-Requested-With')).toBe('StreamBox');
  });

  it('NO añade X-Requested-With a las peticiones seguras (GET por defecto, GET explícito, HEAD)', async () => {
    fetchMock.mockImplementation(async () => jsonResponse({}));

    for (const method of [undefined, 'GET', 'HEAD']) {
      await apiFetch('/movies', { method });
      expect(sentHeaders().has('X-Requested-With'), String(method)).toBe(false);
    }
  });

  it('nunca escribe nada en localStorage ni en sessionStorage', async () => {
    fetchMock.mockResolvedValue(jsonResponse({}));

    await apiFetch('/movies');
    await apiFetch('/auth/login', { method: 'POST', body: {}, public: true }).catch(() => undefined);

    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
  });

  it('serializa el cuerpo a JSON y fija Content-Type solo cuando hay cuerpo', async () => {
    // Un Response solo se puede leer una vez: cada llamada necesita el suyo.
    fetchMock.mockImplementation(async () => jsonResponse({}));

    await apiFetch('/users', { method: 'POST', body: { username: 'ana' } });
    expect(lastCall()[1].body).toBe('{"username":"ana"}');
    expect(sentHeaders().get('Content-Type')).toBe('application/json');

    await apiFetch('/movies');
    expect(lastCall()[1].body).toBeUndefined();
    expect(sentHeaders().has('Content-Type')).toBe(false);
  });

  it('construye la query con URLSearchParams y omite vacíos, null y undefined (pero no 0 ni false)', async () => {
    fetchMock.mockResolvedValue(jsonResponse({}));

    await apiFetch('/movies/search', {
      params: { title: '', genreId: undefined, releaseYear: null, page: 0, hidden: false, sort: 'title' },
    });

    expect(lastCall()[0]).toBe('/api/movies/search?page=0&hidden=false&sort=title');
  });

  it('codifica los caracteres especiales de la query', async () => {
    fetchMock.mockResolvedValue(jsonResponse({}));

    await apiFetch('/movies/search', { params: { title: 'a b&c=d' } });

    expect(lastCall()[0]).toBe('/api/movies/search?title=a+b%26c%3Dd');
  });

  it('sin parámetros no deja un "?" suelto', async () => {
    fetchMock.mockResolvedValue(jsonResponse({}));

    await apiFetch('/genres', { params: { x: undefined } });

    expect(lastCall()[0]).toBe('/api/genres');
  });

  it('un path nunca puede cambiar de origen: siempre cuelga de /api', async () => {
    // Un Response solo se puede leer una vez: cada llamada necesita el suyo.
    fetchMock.mockImplementation(async () => jsonResponse({}));

    for (const path of ['//evil.example/x', 'https://evil.example/x', '@evil.example']) {
      await apiFetch(path);
      const [url] = lastCall();
      expect(url.startsWith('/api')).toBe(true);
      // Resuelta contra el origen de la app, la URL final sigue siendo del mismo origen.
      expect(new URL(url, 'https://app.example').origin).toBe('https://app.example');
    }
  });
});

describe('apiFetch: respuestas correctas', () => {
  it('devuelve el cuerpo JSON', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ id: 7 }));

    await expect(apiFetch<{ id: number }>('/movies/7')).resolves.toEqual({ id: 7 });
  });

  it('204 devuelve undefined sin intentar parsear JSON', async () => {
    fetchMock.mockResolvedValue(noContentResponse());

    await expect(apiFetch('/users/me/favorites/1', { method: 'DELETE' })).resolves.toBeUndefined();
  });

  it('un éxito con cuerpo que no es JSON se convierte en INVALID_RESPONSE', async () => {
    fetchMock.mockResolvedValue(new Response('<html>proxy</html>', { status: 200 }));

    const error = await catchError(apiFetch('/movies'));

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 200, code: 'INVALID_RESPONSE' });
  });
});

describe('apiFetch: 401 y sesión', () => {
  it('401 en endpoint autenticado (y el refresh tampoco renueva) avisa UNA vez con la clave de sesión que se usó y marca sessionExpired', async () => {
    serve({
      'GET /api/movies': () => errorResponse(401, 'INVALID_CREDENTIALS', 'Token inválido'),
      [REFRESH]: sessionExpired,
    });

    const error = await catchError(apiFetch('/movies'));

    expect(onUnauthorized).toHaveBeenCalledTimes(1);
    expect(onUnauthorized).toHaveBeenCalledWith(7);
    expect(error).toMatchObject({
      status: 401,
      sessionExpired: true,
      message: 'Tu sesión ha caducado. Inicia sesión de nuevo.',
    });
  });

  it('401 en endpoint público (login) NO cierra la sesión y habla de credenciales', async () => {
    fetchMock.mockResolvedValue(new Response('', { status: 401 }));

    const error = await catchError(apiFetch('/auth/login', { method: 'POST', body: {}, public: true }));

    expect(onUnauthorized).not.toHaveBeenCalled();
    expect(error).toMatchObject({ status: 401, sessionExpired: false, message: 'Credenciales incorrectas.' });
  });

  it('401 público usa el mensaje del servidor si lo trae', async () => {
    fetchMock.mockResolvedValue(errorResponse(401, 'BAD_CREDENTIALS', 'Correo o contraseña incorrectos.'));

    const error = await catchError(apiFetch('/auth/login', { method: 'POST', body: {}, public: true }));

    expect(error).toMatchObject({ code: 'BAD_CREDENTIALS', message: 'Correo o contraseña incorrectos.' });
  });

  it('401 de login con remainingAttempts: lo expone tipado en el ApiError', async () => {
    fetchMock.mockResolvedValue(
      errorResponse(401, 'INVALID_CREDENTIALS', 'Email o contraseña incorrectos', { remainingAttempts: 4 }),
    );

    const error = (await catchError(apiFetch('/auth/login', { method: 'POST', body: {}, public: true }))) as ApiError;

    expect(error.code).toBe('INVALID_CREDENTIALS');
    expect(error.remainingAttempts).toBe(4);
  });

  it.each([
    ['ausente', undefined],
    ['0', 0],
    ['negativo', -1],
    ['decimal', 2.5],
    ['texto', '3'],
  ])('remainingAttempts %s se descarta (solo vale un entero ≥ 1)', async (_caso, value) => {
    fetchMock.mockResolvedValue(
      errorResponse(401, 'INVALID_CREDENTIALS', 'x', { remainingAttempts: value as number | undefined }),
    );

    const error = (await catchError(apiFetch('/auth/login', { method: 'POST', body: {}, public: true }))) as ApiError;

    expect(error.remainingAttempts).toBeUndefined();
  });

  it('429 ACCOUNT_LOCKED conserva el code (la pantalla lo distingue del límite por IP) y Retry-After', async () => {
    fetchMock.mockResolvedValue(errorResponse(429, 'ACCOUNT_LOCKED', 'Cuenta bloqueada', {}, { 'Retry-After': '900' }));

    const error = (await catchError(apiFetch('/auth/login', { method: 'POST', body: {}, public: true }))) as ApiError;

    expect(error).toMatchObject({ status: 429, code: 'ACCOUNT_LOCKED', retryAfterSeconds: 900 });
    expect(error.remainingAttempts).toBeUndefined();
  });

  it('403 no cierra la sesión: el usuario sigue autenticado, solo faltan permisos', async () => {
    fetchMock.mockResolvedValue(errorResponse(403, 'ACCESS_DENIED', 'Acceso denegado'));

    const error = await catchError(apiFetch('/movies', { method: 'POST', body: {} }));

    expect(onUnauthorized).not.toHaveBeenCalled();
    expect(error).toMatchObject({
      status: 403,
      sessionExpired: false,
      message: 'No tienes permisos para realizar esta acción.',
    });
  });
});

describe('apiFetch: renovación de la sesión (refresh token)', () => {
  const MOVIES = 'GET /api/movies';

  it('401 → refresh (POST sin cuerpo, con cookie y cabecera anti-CSRF) → repite la petición UNA vez y devuelve su resultado', async () => {
    let renewed = false;
    serve({
      [MOVIES]: () => (renewed ? jsonResponse({ id: 1 }) : errorResponse(401, 'INVALID_CREDENTIALS', 'x')),
      [REFRESH]: () => {
        renewed = true;
        return noContentResponse();
      },
    });

    await expect(apiFetch('/movies')).resolves.toEqual({ id: 1 });

    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual(['/api/movies', '/api/auth/refresh', '/api/movies']);
    const [, init] = refreshCalls()[0];
    expect(init?.credentials).toBe('same-origin');
    expect(init?.body).toBeUndefined();
    expect(new Headers(init?.headers).get('X-Requested-With')).toBe('StreamBox');
    expect(new Headers(init?.headers).get('Accept')).toBe('application/json');
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('una petición que modifica datos (POST con cuerpo) se repite igual: mismo cuerpo y cabeceras', async () => {
    let renewed = false;
    serve({
      'POST /api/users/me/favorites/3': () => (renewed ? noContentResponse() : errorResponse(401, 'INVALID_CREDENTIALS', 'x')),
      [REFRESH]: () => {
        renewed = true;
        return noContentResponse();
      },
    });

    await apiFetch('/users/me/favorites/3', { method: 'POST', body: { nota: 'a' } });

    const calls = callsTo('/api/users/me/favorites/3', 'POST');
    expect(calls).toHaveLength(2);
    expect(calls[1][1]?.body).toBe('{"nota":"a"}');
    expect(new Headers(calls[1][1]?.headers).get('X-Requested-With')).toBe('StreamBox');
  });

  it('si el refresh da 401 SESSION_EXPIRED: sesión caducada (aviso UNA vez), sin repetir la petición ni reintentar el refresh', async () => {
    serve({ [MOVIES]: () => errorResponse(401, 'INVALID_CREDENTIALS', 'x'), [REFRESH]: sessionExpired });

    const error = await catchError(apiFetch('/movies'));

    expect(error).toMatchObject({ status: 401, sessionExpired: true, message: 'Tu sesión ha caducado. Inicia sesión de nuevo.' });
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
    expect(onUnauthorized).toHaveBeenCalledWith(7);
    expect(refreshCalls()).toHaveLength(1);
    expect(callsTo('/api/movies')).toHaveLength(1);
  });

  it('si la repetición vuelve a dar 401: sesión caducada, sin bucle (un refresh y una sola repetición)', async () => {
    serve({ [MOVIES]: () => errorResponse(401, 'INVALID_CREDENTIALS', 'x'), [REFRESH]: () => noContentResponse() });

    const error = await catchError(apiFetch('/movies'));

    expect(error).toMatchObject({ status: 401, sessionExpired: true });
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
    expect(refreshCalls()).toHaveLength(1);
    expect(callsTo('/api/movies')).toHaveLength(2);
  });

  it.each([
    ['red caída', () => Promise.reject(new TypeError('Failed to fetch')), 0, 'NETWORK_ERROR', /No se pudo conectar con el servidor/],
    ['500 (BD caída)', () => errorResponse(500, 'INTERNAL_ERROR', 'x'), 500, 'INTERNAL_ERROR', /El servidor ha tenido un problema/],
    ['503 del proxy (HTML, sin code)', () => new Response('<html>caído</html>', { status: 503 }), 503, 'HTTP_503', /No se pudo conectar con el servidor/],
    ['503 de la API (con code)', () => errorResponse(503, 'SERVICE_UNAVAILABLE', 'x'), 503, 'SERVICE_UNAVAILABLE', /El servidor ha tenido un problema/],
    [
      '429 con Retry-After',
      () => errorResponse(429, 'RATE_LIMIT_EXCEEDED', 'x', {}, { 'Retry-After': '20' }),
      429,
      'RATE_LIMIT_EXCEEDED',
      /Inténtalo de nuevo en 20 s/,
    ],
    ['403 CSRF_REJECTED', () => errorResponse(403, 'CSRF_REJECTED', 'x'), 403, 'CSRF_REJECTED', /No tienes permisos/],
  ] as const)(
    'si el refresh falla (%s): devuelve ESE error, que la pantalla enseña con «Reintentar», sin cerrar la sesión',
    async (_caso, refresh, status, code, message) => {
      serve({ [MOVIES]: () => errorResponse(401, 'INVALID_CREDENTIALS', 'x'), [REFRESH]: refresh as Handler });

      const error = (await catchError(apiFetch('/movies'))) as ApiError;

      expect(error).toBeInstanceOf(ApiError);
      expect(error).toMatchObject({ status, code, sessionExpired: false });
      expect(error.message).toMatch(message);
      expect(onUnauthorized).not.toHaveBeenCalled();
      expect(callsTo('/api/movies')).toHaveLength(1);
      expect(refreshCalls()).toHaveLength(1);
    },
  );

  it('varias peticiones con 401 a la vez comparten UN solo refresh y se repiten todas', async () => {
    const refresh = deferred<Response>();
    let renewed = false;
    serve({
      [MOVIES]: () => (renewed ? jsonResponse(['m']) : errorResponse(401, 'INVALID_CREDENTIALS', 'x')),
      'GET /api/genres': () => (renewed ? jsonResponse(['g']) : errorResponse(401, 'INVALID_CREDENTIALS', 'x')),
      'GET /api/users/me/favorites': () => (renewed ? jsonResponse(['f']) : errorResponse(401, 'INVALID_CREDENTIALS', 'x')),
      [REFRESH]: () => refresh.promise,
    });

    const all = Promise.all([apiFetch('/movies'), apiFetch('/genres'), apiFetch('/users/me/favorites')]);
    await vi.waitFor(() => expect(refreshCalls()).toHaveLength(1));
    await flush(); // las tres ya tienen su 401 y esperan la misma renovación
    renewed = true;
    refresh.resolve(noContentResponse());

    await expect(all).resolves.toEqual([['m'], ['g'], ['f']]);
    expect(refreshCalls()).toHaveLength(1);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('varias peticiones con 401 y el refresh caducado: un refresh y un solo aviso por petición (AuthProvider deja uno)', async () => {
    serve({
      [MOVIES]: () => errorResponse(401, 'INVALID_CREDENTIALS', 'x'),
      'GET /api/genres': () => errorResponse(401, 'INVALID_CREDENTIALS', 'x'),
      [REFRESH]: sessionExpired,
    });

    const errors = await Promise.all([catchError(apiFetch('/movies')), catchError(apiFetch('/genres'))]);

    expect(errors).toEqual([expect.objectContaining({ sessionExpired: true }), expect.objectContaining({ sessionExpired: true })]);
    expect(refreshCalls()).toHaveLength(1);
  });

  it('un 401 que llega DESPUÉS de una renovación hecha mientras la petición viajaba no pide otro refresh: solo se repite', async () => {
    const slow = deferred<Response>();
    let renewed = false;
    let slowCalls = 0;
    serve({
      [MOVIES]: () => (renewed ? jsonResponse('m') : errorResponse(401, 'INVALID_CREDENTIALS', 'x')),
      // La primera salió con la cookie vieja y su 401 llega tarde; la repetición ya va con la nueva.
      'GET /api/genres': () => (++slowCalls === 1 ? slow.promise : jsonResponse('g')),
      [REFRESH]: () => {
        renewed = true;
        return noContentResponse();
      },
    });

    const late = apiFetch('/genres');
    await expect(apiFetch('/movies')).resolves.toBe('m');
    expect(refreshCalls()).toHaveLength(1);
    slow.resolve(errorResponse(401, 'INVALID_CREDENTIALS', 'x'));

    await expect(late).resolves.toBe('g');
    expect(refreshCalls()).toHaveLength(1);
  });

  it('una renovación posterior a la caducada sí se pide (cada caducidad, su refresh)', async () => {
    let expiredAgain = true;
    serve({
      [MOVIES]: () => {
        if (expiredAgain) {
          expiredAgain = false;
          return errorResponse(401, 'INVALID_CREDENTIALS', 'x');
        }
        return jsonResponse('m');
      },
      [REFRESH]: () => noContentResponse(),
    });

    await apiFetch('/movies');
    expiredAgain = true; // pasan otros 15 minutos
    await apiFetch('/movies');

    expect(refreshCalls()).toHaveLength(2);
  });

  it.each([
    ['login', '/auth/login', 'POST'],
    ['logout', '/auth/logout', 'POST'],
    ['refresh', '/auth/refresh', 'POST'],
    ['registro', '/users', 'POST'],
  ])('un 401 de %s (ruta pública o de sesión) nunca dispara un refresh ni cierra la sesión', async (_caso, path, method) => {
    serve({ [`${method} /api${path}`]: () => errorResponse(401, 'INVALID_CREDENTIALS', 'x') });

    await catchError(apiFetch(path, { method, public: true }));

    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('las rutas de sesión tampoco renuevan aunque se llamen sin `public`', async () => {
    serve({ 'POST /api/auth/logout': () => errorResponse(401, 'INVALID_CREDENTIALS', 'x') });

    await catchError(apiFetch('/auth/logout', { method: 'POST' }));

    expect(refreshCalls()).toHaveLength(0);
  });

  it('sin sesión o con una petición de una sesión anterior (canRefresh = false): no renueva y el 401 sigue su curso', async () => {
    canRefresh.mockReturnValue(false);
    serve({ [MOVIES]: () => errorResponse(401, 'INVALID_CREDENTIALS', 'x') });

    const error = await catchError(apiFetch('/movies'));

    expect(canRefresh).toHaveBeenCalledWith(7);
    expect(refreshCalls()).toHaveLength(0);
    expect(error).toMatchObject({ status: 401, sessionExpired: true });
    expect(onUnauthorized).toHaveBeenCalledWith(7); // AuthProvider lo ignorará: es de otra sesión
  });

  it('sin AuthProvider (sin puente) no hay sesión que renovar', async () => {
    configureAuth(null);
    serve({ [MOVIES]: () => errorResponse(401, 'INVALID_CREDENTIALS', 'x') });

    await catchError(apiFetch('/movies'));

    expect(refreshCalls()).toHaveLength(0);
  });

  it('si la sesión cambia mientras se renueva (logout u otro login), NO se repite la petición en la sesión nueva', async () => {
    const refresh = deferred<Response>();
    serve({ [MOVIES]: () => errorResponse(401, 'INVALID_CREDENTIALS', 'x'), [REFRESH]: () => refresh.promise });

    const pending = catchError(apiFetch('/movies'));
    await vi.waitFor(() => expect(refreshCalls()).toHaveLength(1));
    currentKey = 8; // otra sesión
    refresh.resolve(noContentResponse());

    expect(await pending).toMatchObject({ status: 401 });
    expect(callsTo('/api/movies')).toHaveLength(1);
    expect(onUnauthorized).toHaveBeenCalledWith(7); // clave vieja: AuthProvider lo ignora
  });

  it('una petición cancelada antes de leer su 401 no dispara el refresh: lanza AbortError', async () => {
    const controller = new AbortController();
    const response = deferred<Response>();
    serve({ [MOVIES]: () => response.promise });

    const pending = catchError(apiFetch('/movies', { signal: controller.signal }));
    await vi.waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(1));
    controller.abort();
    response.resolve(errorResponse(401, 'INVALID_CREDENTIALS', 'x'));

    expect(isAbortError(await pending)).toBe(true);
    expect(refreshCalls()).toHaveLength(0);
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('una petición cancelada mientras se renueva no se repite (el refresh, compartido, sigue)', async () => {
    const controller = new AbortController();
    const refresh = deferred<Response>();
    serve({ [MOVIES]: () => errorResponse(401, 'INVALID_CREDENTIALS', 'x'), [REFRESH]: () => refresh.promise });

    const pending = catchError(apiFetch('/movies', { signal: controller.signal }));
    await vi.waitFor(() => expect(refreshCalls()).toHaveLength(1));
    controller.abort();
    refresh.resolve(noContentResponse());

    expect(isAbortError(await pending)).toBe(true);
    expect(callsTo('/api/movies')).toHaveLength(1);
  });

  it('si la petición se cancela mientras un refresh acaba en SESSION_EXPIRED, la sesión se cierra igual (un aviso) y lanza AbortError', async () => {
    // Pasa de verdad al cambiar de pantalla: la petición que pidió el refresh se cancela. Si nadie cerrara
    // la sesión, la siguiente petición con 401 pediría otro refresh condenado a la misma respuesta.
    const controller = new AbortController();
    const refresh = deferred<Response>();
    serve({ [MOVIES]: () => errorResponse(401, 'INVALID_CREDENTIALS', 'x'), [REFRESH]: () => refresh.promise });

    const pending = catchError(apiFetch('/movies', { signal: controller.signal }));
    await vi.waitFor(() => expect(refreshCalls()).toHaveLength(1));
    controller.abort();
    refresh.resolve(sessionExpired());

    expect(isAbortError(await pending)).toBe(true);
    expect(onUnauthorized).toHaveBeenCalledTimes(1);
    expect(onUnauthorized).toHaveBeenCalledWith(7);
  });

  it('sin Web Locks, login y logout esperan a que termine el refresh en curso (no se cruzan sus cookies)', async () => {
    const refresh = deferred<Response>();
    serve({
      [MOVIES]: () => errorResponse(401, 'INVALID_CREDENTIALS', 'x'),
      [REFRESH]: () => refresh.promise,
      'POST /api/auth/logout': () => noContentResponse(),
    });

    const movies = catchError(apiFetch('/movies'));
    await vi.waitFor(() => expect(refreshCalls()).toHaveLength(1));
    const logout = apiFetch('/auth/logout', { method: 'POST', public: true });
    await flush();
    expect(callsTo('/api/auth/logout', 'POST')).toHaveLength(0);

    refresh.resolve(sessionExpired());
    await logout;
    await movies;
    expect(callsTo('/api/auth/logout', 'POST')).toHaveLength(1);
  });

  it('avisa a las demás pestañas de cada renovación correcta (y no de las fallidas)', async () => {
    const otherTab = new BroadcastChannel(SESSION_CHANNEL_NAME);
    const received: unknown[] = [];
    otherTab.onmessage = (event: MessageEvent) => received.push(event.data);
    let refreshOk = true;
    serve({
      [MOVIES]: () => errorResponse(401, 'INVALID_CREDENTIALS', 'x'),
      [REFRESH]: () => (refreshOk ? noContentResponse() : sessionExpired()),
    });

    await catchError(apiFetch('/movies'));
    await flush();
    expect(received).toEqual([{ type: 'session-renewed' }]);

    refreshOk = false;
    await catchError(apiFetch('/movies'));
    await flush();
    expect(received).toHaveLength(1);
    otherTab.close();
  });

  describe('cambios de sesión entre pestañas (session-changed)', () => {
    it('announceSessionChange publica SOLO el tipo, sin identidad ni datos de la cuenta', async () => {
      const otherTab = new BroadcastChannel(SESSION_CHANNEL_NAME);
      const received: unknown[] = [];
      otherTab.onmessage = (event: MessageEvent) => received.push(event.data);

      announceSessionChange();
      await flush();

      expect(received).toEqual([{ type: 'session-changed' }]);
      otherTab.close();
    });

    it('el aviso de otra pestaña llega a AuthProvider (onSessionChangedElsewhere) y no cuenta como renovación', async () => {
      const otherTab = new BroadcastChannel(SESSION_CHANNEL_NAME);
      serve({
        [MOVIES]: () => errorResponse(401, 'INVALID_CREDENTIALS', 'x'),
        [REFRESH]: () => sessionExpired(),
      });

      otherTab.postMessage({ type: 'session-changed' });
      await flush();
      expect(onSessionChangedElsewhere).toHaveBeenCalledTimes(1);

      // No es una renovación: un 401 posterior sí pide su propio refresh.
      await catchError(apiFetch('/movies'));
      expect(refreshCalls()).toHaveLength(1);
      otherTab.close();
    });

    it('mensajes desconocidos o sin forma de objeto se ignoran', async () => {
      const otherTab = new BroadcastChannel(SESSION_CHANNEL_NAME);
      otherTab.postMessage('session-changed');
      otherTab.postMessage({ type: 'otra-cosa' });
      await flush();
      expect(onSessionChangedElsewhere).not.toHaveBeenCalled();
      otherTab.close();
    });

    it('currentSessionKey expone la clave del puente (0 sin AuthProvider)', () => {
      expect(currentSessionKey()).toBe(7);
      configureAuth(null);
      expect(currentSessionKey()).toBe(0);
    });
  });

  describe('con Web Locks (navigator.locks)', () => {
    let locks: FakeLocks;

    beforeEach(() => {
      locks = installFakeLocks();
    });

    afterEach(() => {
      locks.uninstall();
    });

    it('el refresh, el login y el logout piden el lock común a todas las pestañas', async () => {
      let renewed = false;
      serve({
        [MOVIES]: () => (renewed ? jsonResponse('m') : errorResponse(401, 'INVALID_CREDENTIALS', 'x')),
        [REFRESH]: () => {
          renewed = true;
          return noContentResponse();
        },
        'POST /api/auth/login': () => noContentResponse(),
        'POST /api/auth/logout': () => noContentResponse(),
      });

      await apiFetch('/movies');
      await apiFetch('/auth/login', { method: 'POST', body: {}, public: true });
      await apiFetch('/auth/logout', { method: 'POST', public: true });

      expect(locks.requested).toEqual([SESSION_LOCK_NAME, SESSION_LOCK_NAME, SESSION_LOCK_NAME]);
    });

    it('el cambio de contraseña y el logout global también van en el lock (cambian las cookies)', async () => {
      serve({
        'PUT /api/users/me/password': () => noContentResponse(),
        'POST /api/auth/logout-all': () => noContentResponse(),
      });

      await apiFetch('/users/me/password', { method: 'PUT', body: { currentPassword: 'a', newPassword: 'b' } });
      await apiFetch('/auth/logout-all', { method: 'POST' });

      expect(locks.requested).toEqual([SESSION_LOCK_NAME, SESSION_LOCK_NAME]);
    });

    it('a diferencia del login, un 401 del cambio de contraseña SÍ se renueva: suelta el lock, renueva y repite dentro de él', async () => {
      let renewed = false;
      serve({
        'PUT /api/users/me/password': () => (renewed ? noContentResponse() : errorResponse(401, 'INVALID_CREDENTIALS', 'x')),
        [REFRESH]: () => {
          renewed = true;
          return noContentResponse();
        },
      });

      await apiFetch('/users/me/password', { method: 'PUT', body: { currentPassword: 'a', newPassword: 'b' } });

      expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
        '/api/users/me/password',
        '/api/auth/refresh',
        '/api/users/me/password',
      ]);
      // Envío, refresh y repetición: cada uno coge el lock por separado (no hay bloqueo mutuo).
      expect(locks.requested).toEqual([SESSION_LOCK_NAME, SESSION_LOCK_NAME, SESSION_LOCK_NAME]);
      expect(onUnauthorized).not.toHaveBeenCalled();
    });

    it('si otra pestaña está renovando, el cambio de contraseña espera a que suelte el lock', async () => {
      const held = locks.hold(SESSION_LOCK_NAME);
      await held.acquired;
      serve({ 'PUT /api/users/me/password': () => noContentResponse() });

      const pending = apiFetch('/users/me/password', { method: 'PUT', body: {} });
      await flush();
      expect(fetchMock).not.toHaveBeenCalled();
      held.release();

      await expect(pending).resolves.toBeUndefined();
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    it('las peticiones normales no pasan por el lock', async () => {
      serve({ [MOVIES]: () => jsonResponse('m') });

      await apiFetch('/movies');

      expect(locks.requested).toEqual([]);
    });

    it('si otra pestaña está renovando, se espera a que suelte el lock y, si avisó de su renovación, solo se repite la petición', async () => {
      const otherTab = new BroadcastChannel(SESSION_CHANNEL_NAME);
      const held = locks.hold(SESSION_LOCK_NAME);
      await held.acquired;
      let renewed = false;
      serve({
        [MOVIES]: () => (renewed ? jsonResponse('m') : errorResponse(401, 'INVALID_CREDENTIALS', 'x')),
        [REFRESH]: () => noContentResponse(),
      });

      const pending = apiFetch('/movies');
      await vi.waitFor(() => expect(locks.requested).toHaveLength(1));
      // La otra pestaña termina su refresh: cookies nuevas (comunes), aviso y suelta el lock.
      renewed = true;
      otherTab.postMessage({ type: 'session-renewed' });
      await flush();
      held.release();

      await expect(pending).resolves.toBe('m');
      expect(refreshCalls()).toHaveLength(0);
      otherTab.close();
    });

    it('si la otra pestaña soltó el lock sin renovar, esta pide su propio refresh', async () => {
      const held = locks.hold(SESSION_LOCK_NAME);
      await held.acquired;
      let renewed = false;
      serve({
        [MOVIES]: () => (renewed ? jsonResponse('m') : errorResponse(401, 'INVALID_CREDENTIALS', 'x')),
        [REFRESH]: () => {
          renewed = true;
          return noContentResponse();
        },
      });

      const pending = apiFetch('/movies');
      await vi.waitFor(() => expect(locks.requested).toHaveLength(1));
      expect(refreshCalls()).toHaveLength(0); // espera al lock
      held.release();

      await expect(pending).resolves.toBe('m');
      expect(refreshCalls()).toHaveLength(1);
    });

    it('un aviso de otra pestaña recibido ANTES del 401 hace que no se pida refresh', async () => {
      const otherTab = new BroadcastChannel(SESSION_CHANNEL_NAME);
      const response = deferred<Response>();
      let movieCalls = 0;
      serve({ [MOVIES]: () => (++movieCalls === 1 ? response.promise : jsonResponse('m')) });

      const pending = apiFetch('/movies');
      await vi.waitFor(() => expect(movieCalls).toBe(1));
      otherTab.postMessage({ type: 'session-renewed' });
      await flush();
      response.resolve(errorResponse(401, 'INVALID_CREDENTIALS', 'x'));

      await expect(pending).resolves.toBe('m');
      expect(refreshCalls()).toHaveLength(0);
      expect(locks.requested).toEqual([]);
      otherTab.close();
    });
  });
});

describe('apiFetch: 429 y otros errores del servidor', () => {
  it('429 lee Retry-After y lo muestra en segundos', async () => {
    fetchMock.mockResolvedValue(
      errorResponse(429, 'RATE_LIMIT_EXCEEDED', 'Demasiadas peticiones', {}, { 'Retry-After': '42' }),
    );

    const error = await catchError(apiFetch('/auth/login', { method: 'POST', body: {}, public: true }));

    expect(error).toMatchObject({
      status: 429,
      code: 'RATE_LIMIT_EXCEEDED',
      retryAfterSeconds: 42,
      message: 'Demasiados intentos. Inténtalo de nuevo en 42 s.',
    });
  });

  it.each([
    ['sin cabecera', {}],
    ['con fecha HTTP (no soportada)', { 'Retry-After': 'Wed, 21 Oct 2026 07:28:00 GMT' }],
    ['negativo', { 'Retry-After': '-5' }],
  ])('429 %s: no inventa segundos', async (_caso, headers) => {
    fetchMock.mockResolvedValue(errorResponse(429, 'RATE_LIMIT_EXCEEDED', 'x', {}, headers));

    const error = (await catchError(apiFetch('/auth/login', { public: true }))) as ApiError;

    expect(error.retryAfterSeconds).toBeUndefined();
    expect(error.message).toBe('Demasiados intentos. Inténtalo de nuevo en unos instantes.');
  });

  it('conserva los validationErrors y el message del servidor en un 400', async () => {
    fetchMock.mockResolvedValue(
      errorResponse(400, 'VALIDATION_ERROR', 'Datos no válidos', {
        validationErrors: { email: 'Correo no válido', password: 'Demasiado corta' },
      }),
    );

    const error = (await catchError(apiFetch('/users', { method: 'POST', body: {}, public: true }))) as ApiError;

    expect(error.code).toBe('VALIDATION_ERROR');
    expect(error.message).toBe('Datos no válidos');
    expect(error.validationErrors).toEqual({ email: 'Correo no válido', password: 'Demasiado corta' });
  });

  it('409 conserva el code y el mensaje del servidor (las pantallas deciden por status/code)', async () => {
    fetchMock.mockResolvedValue(errorResponse(409, 'MOVIE_ALREADY_IN_FAVORITES', 'Ya está en tu lista'));

    const error = await catchError(apiFetch('/users/me/favorites/1', { method: 'POST' }));

    expect(error).toMatchObject({ status: 409, code: 'MOVIE_ALREADY_IN_FAVORITES', message: 'Ya está en tu lista' });
  });

  it('un 5xx usa un mensaje fijo y no filtra el texto del servidor', async () => {
    fetchMock.mockResolvedValue(errorResponse(500, 'INTERNAL_ERROR', 'java.lang.NullPointerException en Foo.java:12'));

    const error = (await catchError(apiFetch('/movies'))) as ApiError;

    expect(error.message).toBe('El servidor ha tenido un problema. Inténtalo de nuevo en unos minutos.');
  });

  it('un error con cuerpo no JSON (p. ej. HTML de un proxy 502) usa HTTP_<status>', async () => {
    fetchMock.mockResolvedValue(new Response('<html>Bad gateway</html>', { status: 502 }));

    const error = await catchError(apiFetch('/movies'));

    expect(error).toMatchObject({ status: 502, code: 'HTTP_502' });
  });

  it.each([502, 503, 504])(
    'un %i del proxy (sin JSON con code) dice que no se llegó al servidor, no que este fallara',
    async (status) => {
      fetchMock.mockResolvedValue(new Response('<html>Bad gateway</html>', { status }));

      const error = (await catchError(apiFetch('/movies'))) as ApiError;

      expect(error).toMatchObject({ status, code: `HTTP_${status}` });
      expect(error.message).toBe('No se pudo conectar con el servidor. Inténtalo de nuevo en unos instantes.');
    },
  );

  it('un 502 con cuerpo JSON pero sin code (p. ej. otro proxy) también es «no se pudo conectar»', async () => {
    fetchMock.mockResolvedValue(jsonResponse({ message: 'upstream error' }, 502));

    const error = (await catchError(apiFetch('/movies'))) as ApiError;

    expect(error.message).toBe('No se pudo conectar con el servidor. Inténtalo de nuevo en unos instantes.');
  });

  it.each([502, 503, 504])('un %i CON code lo envió la API: mensaje de fallo del servidor (sin filtrar su texto)', async (status) => {
    fetchMock.mockResolvedValue(errorResponse(status, 'SERVICE_UNAVAILABLE', 'detalle interno'));

    const error = (await catchError(apiFetch('/movies'))) as ApiError;

    expect(error).toMatchObject({ status, code: 'SERVICE_UNAVAILABLE' });
    expect(error.message).toBe('El servidor ha tenido un problema. Inténtalo de nuevo en unos minutos.');
  });

  it('un 500 sin cuerpo sigue siendo un fallo del servidor (solo 502/503/504 son del proxy)', async () => {
    fetchMock.mockResolvedValue(new Response('', { status: 500 }));

    const error = (await catchError(apiFetch('/movies'))) as ApiError;

    expect(error.message).toBe('El servidor ha tenido un problema. Inténtalo de nuevo en unos minutos.');
  });

  it('un 4xx sin mensaje usa un texto genérico con el estado', async () => {
    fetchMock.mockResolvedValue(new Response('', { status: 418 }));

    const error = await catchError(apiFetch('/movies'));

    expect(error).toMatchObject({ status: 418, message: 'No se pudo completar la operación (error 418).' });
  });

  it('ignora un validationErrors que no sea un objeto', async () => {
    fetchMock.mockResolvedValue(errorResponse(400, 'VALIDATION_ERROR', 'x', { validationErrors: ['a'] as never }));

    const error = (await catchError(apiFetch('/users', { public: true }))) as ApiError;

    expect(error.validationErrors).toBeUndefined();
  });
});

describe('apiFetch: red caída y cancelaciones', () => {
  it('un fallo de red se convierte en ApiError status 0 / NETWORK_ERROR', async () => {
    fetchMock.mockRejectedValue(new TypeError('Failed to fetch'));

    const error = await catchError(apiFetch('/movies'));

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 0, code: 'NETWORK_ERROR' });
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it('un AbortError se propaga tal cual (no es un error que enseñar) y isAbortError lo reconoce', async () => {
    const abort = new DOMException('The operation was aborted.', 'AbortError');
    fetchMock.mockRejectedValue(abort);

    const error = await catchError(apiFetch('/movies'));

    expect(error).toBe(abort);
    expect(isAbortError(error)).toBe(true);
  });

  it('isAbortError no confunde otros errores con una cancelación', () => {
    expect(isAbortError(new TypeError('Failed to fetch'))).toBe(false);
    expect(isAbortError(new DOMException('x', 'NotAllowedError'))).toBe(false);
    expect(isAbortError(new ApiError({ status: 0, code: 'NETWORK_ERROR', message: 'x' }))).toBe(false);
    expect(isAbortError(undefined)).toBe(false);
  });
});

describe('mensajes de ayuda', () => {
  it('getErrorMessage devuelve el mensaje del ApiError y el de reserva para cualquier otra cosa', () => {
    expect(getErrorMessage(new ApiError({ status: 409, code: 'X', message: 'Duplicado' }))).toBe('Duplicado');
    expect(getErrorMessage(new Error('interno'), 'Reserva')).toBe('Reserva');
    expect(getErrorMessage('texto')).toBe('Ha ocurrido un error inesperado. Inténtalo de nuevo.');
  });

  it('rateLimitMessage incluye los segundos cuando se conocen (incluido 0)', () => {
    expect(rateLimitMessage(0)).toBe('Demasiados intentos. Inténtalo de nuevo en 0 s.');
    expect(rateLimitMessage()).toContain('unos instantes');
  });
});
