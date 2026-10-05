/**
 * Utilidades compartidas por los tests: fábricas de datos, respuestas HTTP
 * simuladas y un `render` con los proveedores reales de la aplicación.
 *
 * Se usan los proveedores REALES (`ToastProvider`, `AuthProvider`, router) y no
 * simulacros: así los tests de pantallas comprueban también cómo se integran con
 * la sesión y los avisos, y lo único que se finge es la red (`fetch`).
 */
import { render } from '@testing-library/react';
import type { RenderResult } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter, parsePath } from 'react-router-dom';
import type { Mock } from 'vitest';
import { AuthProvider } from '../context/AuthContext';
import { ToastProvider } from '../context/ToastContext';
import { LocationProbe } from './LocationProbe';
import type { ApiErrorBody, Movie, PageResponse, User } from '../lib/types';

/** Crea un usuario de prueba (`UserResponse`); por defecto, rol `USER`. */
export function makeUser(overrides: Partial<User> = {}): User {
  return {
    id: 1,
    username: 'ana',
    email: 'ana@example.com',
    role: 'USER',
    createdAt: '2026-01-01T10:00:00Z',
    ...overrides,
  };
}

/** Clave de {@link routeFetch} de la petición que `AuthProvider` lanza siempre que hay sesión. */
export const CURRENT_USER = 'GET /api/users/me';

/** Crea una película de prueba; cada test solo especifica lo que le importa. */
export function makeMovie(overrides: Partial<Movie> = {}): Movie {
  const id = overrides.id ?? 1;
  return {
    id,
    title: `Película ${id}`,
    description: `Sinopsis de la película ${id}.`,
    duration: 108,
    releaseYear: 2020,
    imageUrl: `https://img.example/${id}.webp`,
    videoUrl: `https://video.example/${id}`,
    createdAt: '2026-01-01T10:00:00Z',
    genres: [],
    ...overrides,
  };
}

/** Crea una página de resultados con los metadatos de paginación coherentes. */
export function makePage(
  content: Movie[],
  overrides: Partial<PageResponse<Movie>> = {},
): PageResponse<Movie> {
  return {
    content,
    page: 0,
    size: 20,
    totalElements: content.length,
    totalPages: 1,
    hasNext: false,
    hasPrevious: false,
    ...overrides,
  };
}

/** Respuesta JSON simulada (`fetch` real devolvería un `Response` igual). */
export function jsonResponse(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  });
}

/** Respuesta de error con el formato `ErrorResponse` del backend. */
export function errorResponse(
  status: number,
  code: string,
  message: string,
  extra: Partial<ApiErrorBody> = {},
  headers: Record<string, string> = {},
): Response {
  const body: ApiErrorBody = {
    timestamp: '2026-01-01T10:00:00Z',
    status,
    error: 'Error',
    code,
    message,
    path: '/api/test',
    ...extra,
  };
  return jsonResponse(body, status, headers);
}

/** Respuesta sin cuerpo (204 No Content). */
export function noContentResponse(): Response {
  return new Response(null, { status: 204 });
}

/** Respuesta (o promesa de ella) que devuelve un endpoint simulado en {@link routeFetch}. */
type RouteHandler = (request: { url: string; init: RequestInit }) => Response | Promise<Response>;

/**
 * Configura un `fetch` simulado que responde según `"MÉTODO /ruta?query"`
 * (p. ej. `'POST /api/users/me/favorites/1'`). Una ruta sin manejador hace fallar
 * el test con un mensaje claro en lugar de devolver algo inventado.
 *
 * Única excepción: {@link CURRENT_USER} (`GET /api/users/me`) responde por
 * defecto con un usuario normal ({@link makeUser}). `AuthProvider` lo pide en
 * CUANTO hay sesión, así que todas las pantallas con token lo lanzan aunque no
 * tenga nada que ver con lo que prueban; repetirlo en cada test solo añadiría
 * ruido. Quien prueba el rol lo sobrescribe pasando su propio manejador.
 *
 * @param fetchMock el `vi.fn()` instalado como `fetch` global
 * @param routes manejadores por clave `"MÉTODO URL"`; se pueden cambiar entre pasos del test
 */
export function routeFetch(fetchMock: Mock<typeof fetch>, routes: Record<string, RouteHandler>): void {
  const withDefaults: Record<string, RouteHandler> = { [CURRENT_USER]: () => jsonResponse(makeUser()), ...routes };
  fetchMock.mockImplementation(async (input, init = {}) => {
    const key = `${init.method ?? 'GET'} ${String(input)}`;
    const handler = withDefaults[key];
    if (!handler) throw new Error(`Petición no prevista en el test: ${key}`);
    return handler({ url: String(input), init });
  });
}

/** Opciones de {@link renderWithProviders}. */
interface RenderOptions {
  /** Ruta inicial del router en memoria. */
  route?: string;
  /** Token guardado antes de montar (sesión iniciada). Sin él, no hay sesión. */
  token?: string;
  /** Estado de navegación de la entrada inicial (`location.state`), p. ej. la "vuelta al listado". */
  routeState?: unknown;
}

/**
 * Renderiza `ui` dentro de router en memoria + avisos + sesión, igual que `App`.
 * Además de `ui` pinta un texto `ruta:/xxx` con la ruta actual para comprobar
 * redirecciones sin depender de cómo esté montado el router.
 */
export function renderWithProviders(ui: ReactElement, options: RenderOptions = {}): RenderResult {
  if (options.token) localStorage.setItem('token', options.token);
  const route = options.route ?? '/';
  // Con estado, la entrada se pasa como objeto (`parsePath` separa ruta, búsqueda y fragmento).
  const entry = options.routeState === undefined ? route : { ...parsePath(route), state: options.routeState };
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <ToastProvider>
        <AuthProvider>
          {ui}
          <LocationProbe />
        </AuthProvider>
      </ToastProvider>
    </MemoryRouter>,
  );
}
