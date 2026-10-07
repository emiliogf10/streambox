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
import type { ApiErrorBody, Episode, Movie, PageResponse, Series, SeriesDetail, User } from '../lib/types';

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

/** Clave de {@link routeFetch} de la lista de series favoritas, que `FavoritesProvider` pide siempre al montarse. */
export const FAVORITE_SERIES = 'GET /api/users/me/favorites/series';

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

/** Crea una serie de prueba (`SeriesResponse`): por defecto, terminada, con 2 temporadas y 5 episodios. */
export function makeSeries(overrides: Partial<Series> = {}): Series {
  const id = overrides.id ?? 1;
  return {
    id,
    title: `Serie ${id}`,
    description: `Sinopsis de la serie ${id}.`,
    releaseYear: 2019,
    endYear: 2022,
    imageUrl: `https://img.example/series-${id}.webp`,
    createdAt: '2026-01-01T10:00:00Z',
    genres: [],
    seasonCount: 2,
    episodeCount: 5,
    ...overrides,
  };
}

/** Crea un episodio de prueba; el id por defecto deriva de temporada y número para que sea único. */
export function makeEpisode(overrides: Partial<Episode> = {}): Episode {
  const seasonNumber = overrides.seasonNumber ?? 1;
  const episodeNumber = overrides.episodeNumber ?? 1;
  return {
    id: seasonNumber * 100 + episodeNumber,
    seasonNumber,
    episodeNumber,
    title: `Episodio ${episodeNumber}`,
    description: `Sinopsis del episodio ${episodeNumber}.`,
    duration: 45,
    videoUrl: `https://video.example/s${seasonNumber}e${episodeNumber}`,
    ...overrides,
  };
}

/**
 * Crea el detalle de una serie (`SeriesDetailResponse`). Si no se pasan
 * temporadas, genera dos (con 3 y 2 episodios); `seasonCount` y `episodeCount`
 * se calculan a partir de ellas para que sean coherentes.
 */
export function makeSeriesDetail(overrides: Partial<SeriesDetail> = {}): SeriesDetail {
  const seasons = overrides.seasons ?? [
    { seasonNumber: 1, episodes: [1, 2, 3].map((n) => makeEpisode({ seasonNumber: 1, episodeNumber: n })) },
    { seasonNumber: 2, episodes: [1, 2].map((n) => makeEpisode({ seasonNumber: 2, episodeNumber: n })) },
  ];
  return {
    ...makeSeries(overrides),
    seasonCount: seasons.length,
    episodeCount: seasons.reduce((sum, season) => sum + season.episodes.length, 0),
    ...overrides,
    seasons,
  };
}

/** Crea una página de resultados (de películas o series) con los metadatos de paginación coherentes. */
export function makePage<T = Movie>(content: T[], overrides: Partial<PageResponse<T>> = {}): PageResponse<T> {
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
 * ¿Simula el servidor que el navegador trae una cookie de sesión válida? Lo fija
 * {@link renderWithProviders} (`session: true`); {@link routeFetch} lo consulta al
 * responder `GET /api/users/me`. Se lee al atender la petición, no al configurar
 * `routeFetch`, así que el orden entre ambas llamadas da igual.
 */
let sessionActive = false;

/**
 * Manejador de `GET /api/users/me` para un usuario concreto que respeta la sesión
 * simulada: con `session: true` responde con `user` y sin ella con 401. Úsalo en
 * lugar de `() => jsonResponse(user)` cuando el test también comprueba el caso
 * «sin sesión».
 */
export function currentUserAs(user: User): () => Response {
  return () => (sessionActive ? jsonResponse(user) : errorResponse(401, 'UNAUTHORIZED', 'No autenticado.'));
}

/**
 * Configura un `fetch` simulado que responde según `"MÉTODO /ruta?query"`
 * (p. ej. `'POST /api/users/me/favorites/1'`). Una ruta sin manejador hace fallar
 * el test con un mensaje claro en lugar de devolver algo inventado.
 *
 * Única excepción: {@link CURRENT_USER} (`GET /api/users/me`) responde por
 * defecto con un usuario normal ({@link makeUser}) si `renderWithProviders` se
 * llamó con `session: true`, y con 401 si no. `AuthProvider` lo pide SIEMPRE al
 * arrancar (así descubre la sesión), así que todas las pantallas lo lanzan aunque no
 * tenga nada que ver con lo que prueban; repetirlo en cada test solo añadiría
 * ruido. Quien prueba el rol lo sobrescribe pasando su propio manejador.
 *
 * Por el mismo motivo, {@link FAVORITE_SERIES} responde por defecto con una
 * lista vacía: `FavoritesProvider` la pide junto a la de películas en TODAS las
 * pantallas con la lista (portada, buscador, barra...), y los tests anteriores a
 * las series no tienen nada que decir de ella. Los tests de series favoritas la
 * sobrescriben. (La de películas no tiene valor por defecto: sus tests ya la declaran.)
 *
 * @param fetchMock el `vi.fn()` instalado como `fetch` global
 * @param routes manejadores por clave `"MÉTODO URL"`; se pueden cambiar entre pasos del test
 */
export function routeFetch(fetchMock: Mock<typeof fetch>, routes: Record<string, RouteHandler>): void {
  const withDefaults: Record<string, RouteHandler> = {
    [CURRENT_USER]: () =>
      sessionActive ? jsonResponse(makeUser()) : errorResponse(401, 'UNAUTHORIZED', 'No autenticado.'),
    [FAVORITE_SERIES]: () => jsonResponse([]),
    ...routes,
  };
  fetchMock.mockImplementation(async (input, init = {}) => {
    const key = `${init.method ?? 'GET'} ${String(input)}`;
    const handler = withDefaults[key];
    if (!handler) throw new Error(`Petición no prevista en el test: ${key}`);
    return handler({ url: String(input), init });
  });
}

/**
 * Llamadas hechas al `fetch` simulado SIN contar el chequeo de sesión
 * (`GET /api/users/me`) que `AuthProvider` hace siempre al montarse. Sirve para
 * afirmar «este formulario no ha llamado al servidor» sin que el arranque cuente.
 */
export function apiCalls(fetchMock: Mock<typeof fetch>): Parameters<typeof fetch>[] {
  return fetchMock.mock.calls.filter(([url]) => String(url) !== '/api/users/me');
}

/** Opciones de {@link renderWithProviders}. */
interface RenderOptions {
  /** Ruta inicial del router en memoria. */
  route?: string;
  /**
   * `true` = hay una sesión abierta (la cookie HttpOnly existe): `GET /users/me`
   * responde con el usuario de {@link routeFetch}. Sin ella responde 401 («no hay
   * sesión»), que es lo que averigua `AuthProvider` al arrancar.
   */
  session?: boolean;
  /** Estado de navegación de la entrada inicial (`location.state`), p. ej. la "vuelta al listado". */
  routeState?: unknown;
}

/**
 * Renderiza `ui` dentro de router en memoria + avisos + sesión, igual que `App`.
 * Además de `ui` pinta un texto `ruta:/xxx` con la ruta actual para comprobar
 * redirecciones sin depender de cómo esté montado el router.
 */
export function renderWithProviders(ui: ReactElement, options: RenderOptions = {}): RenderResult {
  sessionActive = options.session === true;
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
