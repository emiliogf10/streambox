/**
 * Cliente HTTP central del frontend.
 *
 * Toda llamada a la API pasa por {@link apiFetch}. Así el manejo de la sesión
 * (cookie HttpOnly, cabecera anti-CSRF, 401) y de los errores (403, 429, red caída,
 * respuestas que no son JSON...) vive en UN solo sitio y las pantallas solo
 * tienen que capturar un {@link ApiError} con un mensaje ya listo para el usuario.
 */
import type { ApiErrorBody } from './types';

/** Prefijo común de todos los endpoints (el proxy de Vite lo envía al backend). */
const API_URL = '/api';

/** Datos con los que se construye un {@link ApiError}. */
interface ApiErrorInit {
  status: number;
  code: string;
  message: string;
  retryAfterSeconds?: number;
  validationErrors?: Record<string, string>;
  remainingAttempts?: number;
  sessionExpired?: boolean;
}

/**
 * Error uniforme de cualquier llamada a la API.
 *
 * Las pantallas deciden por `status` y `code` (contrato estable del backend),
 * nunca por el texto de `message`, que solo se usa para mostrar.
 * Códigos generados por el propio cliente: `NETWORK_ERROR` (status 0, no hubo
 * respuesta) e `INVALID_RESPONSE` (la respuesta no era JSON válido).
 */
export class ApiError extends Error {
  /** Código HTTP, o 0 si no hubo respuesta (red caída, backend apagado). */
  readonly status: number;
  /** Código funcional estable (`VALIDATION_ERROR`, `USER_ALREADY_EXISTS`...). */
  readonly code: string;
  /** Segundos que indica la cabecera `Retry-After` (típicamente en un 429). */
  readonly retryAfterSeconds?: number;
  /** Errores de validación por campo (`campo → mensaje`), si el servidor los envió. */
  readonly validationErrors?: Record<string, string>;
  /**
   * Intentos de login que quedan antes del bloqueo de la cuenta (solo en el 401
   * de `POST /api/auth/login`). `undefined` si el servidor no lo envió o el
   * valor no es un entero ≥ 1: mejor no avisar que avisar con un número inventado.
   */
  readonly remainingAttempts?: number;
  /**
   * `true` si este error fue un 401 que ya provocó el cierre de sesión. La UI
   * lo ignora en silencio: el aviso "sesión caducada" se muestra una sola vez.
   */
  readonly sessionExpired: boolean;

  constructor(init: ApiErrorInit) {
    super(init.message);
    this.name = 'ApiError';
    this.status = init.status;
    this.code = init.code;
    this.retryAfterSeconds = init.retryAfterSeconds;
    this.validationErrors = init.validationErrors;
    this.remainingAttempts = init.remainingAttempts;
    this.sessionExpired = init.sessionExpired ?? false;
  }
}

/**
 * Puente con el contexto de autenticación.
 *
 * `api.ts` no puede importar `AuthContext` (sería una dependencia circular),
 * así que es el proveedor quien se registra aquí con {@link configureAuth}.
 *
 * El JWT ya NO existe para JavaScript: viaja en una cookie `HttpOnly` que el
 * navegador adjunta solo (por eso un XSS no puede leerlo). Lo que el puente
 * comparte es una "clave de sesión" opaca (un contador) que sirve para saber a
 * QUÉ sesión pertenece una respuesta y descartar los 401 tardíos de una sesión
 * anterior.
 */
interface AuthBridge {
  /** Clave de la sesión actual; cambia en cada inicio o cierre de sesión. */
  getSessionKey: () => number;
  /** Se invoca cuando el servidor responde 401; recibe la clave con la que se hizo la petición. */
  onUnauthorized: (usedKey: number) => void;
}

let authBridge: AuthBridge | null = null;

/**
 * Registra (o elimina, con `null`) el puente con la sesión. Lo llama `AuthProvider`.
 *
 * @param bridge funciones para leer la clave de sesión y avisar de un 401
 */
export function configureAuth(bridge: AuthBridge | null): void {
  authBridge = bridge;
}

/**
 * Cabecera que el backend exige en toda petición no segura (defensa CSRF). Un
 * formulario de otra web no puede añadirla sin pasar por CORS, que el backend no
 * concede; así la cookie sola (que el navegador enviaría) no basta para actuar.
 */
const CSRF_HEADER = 'X-Requested-With';
const CSRF_VALUE = 'StreamBox';

/** Métodos que no modifican datos y por tanto no llevan la cabecera anti-CSRF. */
const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

/** Valores admitidos como parámetros de consulta; `undefined`, `null` y `''` se omiten. */
export type QueryParams = Record<string, string | number | boolean | null | undefined>;

/** Opciones de {@link apiFetch}: las de `fetch`, con `body` ya como objeto. */
export interface ApiFetchOptions extends Omit<RequestInit, 'body'> {
  /** Cuerpo de la petición; se serializa a JSON automáticamente. */
  body?: unknown;
  /** Parámetros de la URL (`?page=0&size=20`), ya codificados. */
  params?: QueryParams;
  /**
   * Endpoint público (login, registro, logout): un 401 NO cierra la sesión,
   * porque ahí significa "credenciales incorrectas", no "sesión caducada".
   * (La cookie, al ser del navegador, se envía igualmente; el servidor la ignora.)
   */
  public?: boolean;
}

/** Construye la cadena `?a=1&b=2` omitiendo valores vacíos. */
function buildQuery(params?: QueryParams): string {
  if (!params) return '';
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') search.set(key, String(value));
  }
  const text = search.toString();
  return text ? `?${text}` : '';
}

/**
 * Mensaje de "demasiados intentos" (429).
 *
 * @param seconds segundos de espera indicados por `Retry-After`, si se conocen
 */
export function rateLimitMessage(seconds?: number): string {
  return seconds !== undefined
    ? `Demasiados intentos. Inténtalo de nuevo en ${seconds} s.`
    : 'Demasiados intentos. Inténtalo de nuevo en unos instantes.';
}

/** Interpreta `Retry-After` en su forma de segundos; ignora la forma de fecha HTTP. */
function parseRetryAfter(value: string | null): number | undefined {
  if (!value) return undefined;
  const seconds = Number.parseInt(value, 10);
  return Number.isFinite(seconds) && seconds >= 0 ? seconds : undefined;
}

/**
 * Interpreta `remainingAttempts` del cuerpo de error. Solo vale un entero ≥ 1
 * (el contrato del backend); cualquier otra cosa (texto, decimal, 0, negativo)
 * se descarta para no mostrar un aviso con un número sin sentido.
 */
function parseRemainingAttempts(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isInteger(value) && value >= 1 ? value : undefined;
}

/** Intenta interpretar un texto como JSON; devuelve `undefined` si no lo es. */
function tryParseJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return undefined;
  }
}

/** Indica si un valor desconocido tiene forma de objeto (y no es `null` ni un array). */
function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * Indica si el error procede de cancelar la petición con un `AbortController`.
 * Las cancelaciones son intencionadas (p. ej. un componente que se desmonta) y
 * no deben mostrarse al usuario.
 */
export function isAbortError(error: unknown): boolean {
  return error instanceof DOMException && error.name === 'AbortError';
}

/**
 * Convierte una respuesta de error en un {@link ApiError} con mensaje en español.
 *
 * Para 401, 403, 429 y 5xx el mensaje lo fija el cliente (es lo que el usuario
 * debe entender); para el resto se usa el `message` del servidor, que ya está en
 * español y es específico (validaciones, duplicados...).
 */
function buildApiError(res: Response, body: unknown, isPublic: boolean): ApiError {
  const errorBody: Partial<ApiErrorBody> = isRecord(body) ? (body as Partial<ApiErrorBody>) : {};
  const status = res.status;
  const retryAfterSeconds = parseRetryAfter(res.headers.get('Retry-After'));
  const serverMessage =
    typeof errorBody.message === 'string' && errorBody.message.trim() ? errorBody.message : null;

  let message: string;
  if (status === 401) {
    message = isPublic
      ? (serverMessage ?? 'Credenciales incorrectas.')
      : 'Tu sesión ha caducado. Inicia sesión de nuevo.';
  } else if (status === 403) {
    message = 'No tienes permisos para realizar esta acción.';
  } else if (status === 429) {
    message = rateLimitMessage(retryAfterSeconds);
  } else if (status >= 500) {
    message = 'El servidor ha tenido un problema. Inténtalo de nuevo en unos minutos.';
  } else {
    message = serverMessage ?? `No se pudo completar la operación (error ${status}).`;
  }

  return new ApiError({
    status,
    code: typeof errorBody.code === 'string' ? errorBody.code : `HTTP_${status}`,
    message,
    retryAfterSeconds,
    validationErrors: isRecord(errorBody.validationErrors)
      ? (errorBody.validationErrors as Record<string, string>)
      : undefined,
    remainingAttempts: parseRemainingAttempts(errorBody.remainingAttempts),
    sessionExpired: status === 401 && !isPublic,
  });
}

/**
 * Hace una petición a la API y devuelve el cuerpo JSON ya tipado.
 *
 * Responsabilidades centralizadas aquí:
 * - La sesión va en una cookie HttpOnly: `credentials: 'same-origin'` hace que el
 *   navegador la envíe (mismo origen gracias al proxy de Vite/nginx). No hay
 *   cabecera `Authorization` ni token en JavaScript.
 * - Añade `X-Requested-With: StreamBox` a toda petición que no sea GET/HEAD/OPTIONS
 *   (el backend responde 403 `CSRF_REJECTED` sin ella).
 * - Serializa `body` a JSON y construye la query a partir de `params`.
 * - Comprueba SIEMPRE `res.ok`: nada se da por bueno sin mirar el estado.
 * - 204 / cuerpo vacío: devuelve `undefined` sin llamar a `res.json()`.
 * - 401 en endpoint autenticado: avisa a `AuthProvider` para cerrar la sesión.
 *   No navega: al quedar sin sesión, la ruta protegida redirige a `/login` una
 *   sola vez aunque fallen varias peticiones a la vez.
 * - 403 NO cierra la sesión (el usuario está autenticado, solo le faltan permisos).
 * - 429 lee `Retry-After` y lo expone en `retryAfterSeconds`.
 * - Red caída, backend apagado o respuesta que no es JSON: `ApiError` con
 *   status 0 (`NETWORK_ERROR`) o `INVALID_RESPONSE`, nunca una excepción cruda.
 * - Las cancelaciones (`AbortController`) se relanzan tal cual; ver {@link isAbortError}.
 *
 * @typeParam T tipo del cuerpo de la respuesta (`void` si no hay cuerpo)
 * @param path ruta relativa a `/api`, p. ej. `/movies`
 * @param options método, cuerpo, parámetros, señal de cancelación...
 * @returns el cuerpo JSON de la respuesta
 * @throws {ApiError} si la petición falla por cualquier motivo
 */
export async function apiFetch<T = void>(path: string, options: ApiFetchOptions = {}): Promise<T> {
  const { body, params, public: isPublic = false, headers: extraHeaders, ...init } = options;

  const sessionKey = authBridge?.getSessionKey() ?? 0;
  const headers = new Headers(extraHeaders);
  headers.set('Accept', 'application/json');
  if (body !== undefined) headers.set('Content-Type', 'application/json');
  if (!SAFE_METHODS.has((init.method ?? 'GET').toUpperCase())) headers.set(CSRF_HEADER, CSRF_VALUE);

  let res: Response;
  let text: string;
  try {
    res = await fetch(`${API_URL}${path}${buildQuery(params)}`, {
      ...init,
      headers,
      credentials: 'same-origin',
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    text = await res.text();
  } catch (error) {
    if (isAbortError(error)) throw error;
    throw new ApiError({
      status: 0,
      code: 'NETWORK_ERROR',
      message: 'No se pudo conectar con el servidor. Comprueba tu conexión e inténtalo de nuevo.',
    });
  }

  const parsed = text.trim() === '' ? undefined : tryParseJson(text);

  if (res.ok) {
    if (text.trim() === '') return undefined as T; // 204 u otro éxito sin cuerpo
    if (parsed === undefined) {
      throw new ApiError({
        status: res.status,
        code: 'INVALID_RESPONSE',
        message: 'El servidor ha enviado una respuesta que no se puede interpretar.',
      });
    }
    return parsed as T;
  }

  const error = buildApiError(res, parsed, isPublic);
  if (error.sessionExpired) authBridge?.onUnauthorized(sessionKey);
  throw error;
}

/**
 * Mensaje legible para mostrar al usuario a partir de cualquier error capturado.
 *
 * @param error valor capturado en un `catch`
 * @param fallback texto si el error no es un {@link ApiError}
 */
export function getErrorMessage(
  error: unknown,
  fallback = 'Ha ocurrido un error inesperado. Inténtalo de nuevo.',
): string {
  return error instanceof ApiError ? error.message : fallback;
}
