/**
 * Tests del cliente HTTP central (`apiFetch`, `ApiError`).
 *
 * Protegen el contrato de sesión y errores del que dependen todas las pantallas:
 * qué cabeceras viajan, cuándo un 401 cierra la sesión y cuándo NO, cómo se
 * interpretan 403/429/204/red caída y que nunca se escape una excepción cruda.
 * `fetch` se sustituye por un doble: no hay red real.
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, apiFetch, configureAuth, getErrorMessage, isAbortError, rateLimitMessage } from './api';
import { errorResponse, jsonResponse, noContentResponse } from '../test/helpers';

const fetchMock = vi.fn<typeof fetch>();
const onUnauthorized = vi.fn<(usedKey: number) => void>();

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

beforeEach(() => {
  fetchMock.mockReset();
  onUnauthorized.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  configureAuth({ getSessionKey: () => 7, onUnauthorized });
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
  it('401 en endpoint autenticado avisa UNA vez con la clave de sesión que se usó y marca sessionExpired', async () => {
    fetchMock.mockResolvedValue(errorResponse(401, 'UNAUTHORIZED', 'Token inválido'));

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
