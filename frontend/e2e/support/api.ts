/**
 * Cliente mínimo de la API para PREPARAR datos desde los tests (siembra, usuarios,
 * favoritos). Habla directamente con el backend efímero (`BACKEND_URL`), sin pasar
 * por el navegador ni por Vite: lo que se prueba en la UI es la UI, no la preparación.
 *
 * Todas las funciones fallan con un mensaje claro si el servidor no responde lo
 * esperado, para que un fallo de preparación no se confunda con un fallo de la app.
 */
import { randomUUID } from 'node:crypto';
import type { APIRequestContext, APIResponse } from '@playwright/test';
import { ADMIN_EMAIL, ADMIN_PASSWORD, BACKEND_URL, USER_PASSWORD } from './config';

/** Cuenta de usuario creada para un test. */
export interface TestUser {
  username: string;
  email: string;
  password: string;
}

/** Forma mínima de una película devuelta por la API (solo lo que usan los tests). */
export interface ApiMovie {
  id: number;
  title: string;
}

/** Forma de la respuesta paginada de la API (solo lo que usan los tests). */
interface ApiPage<T> {
  content: T[];
  hasNext: boolean;
}

/** Lanza un error legible si la respuesta no es 2xx. */
async function expectOk(response: APIResponse, action: string): Promise<void> {
  if (!response.ok()) {
    throw new Error(`${action} falló: ${response.status()} ${await response.text()}`);
  }
}

/**
 * Genera datos de un usuario nuevo con nombre y email ÚNICOS (UUID). Así cada
 * test es independiente de los demás y pueden ejecutarse en paralelo sin
 * colisiones por correo o usuario duplicado.
 */
export function newTestUser(): TestUser {
  const id = randomUUID().replaceAll('-', '').slice(0, 16);
  return { username: `user_${id}`, email: `e2e-${id}@streambox.local`, password: USER_PASSWORD };
}

/** Registra un usuario por API (`POST /api/users`, público; siempre rol USER). */
export async function registerUser(request: APIRequestContext, user: TestUser): Promise<void> {
  const response = await request.post(`${BACKEND_URL}/api/users`, {
    data: user,
    headers: { 'X-Requested-With': 'StreamBox' }, // el backend la exige a toda petición no segura
  });
  await expectOk(response, `Registrar a ${user.email}`);
}

/** Nombre de la cookie de sesión que fija el backend (HttpOnly, SameSite=Strict, Path=/api). */
export const SESSION_COOKIE = 'streambox_token';

/**
 * Nombre de la cookie del refresh token (HttpOnly, SameSite=Strict, Path=/api/auth: solo viaja al
 * login, al refresh y al logout). La fija el login junto a {@link SESSION_COOKIE}. `signIn` NO la
 * siembra (solo el JWT): los tests que necesitan una sesión renovable inician sesión con el formulario.
 */
export const REFRESH_COOKIE = 'streambox_refresh';

/**
 * Extrae el valor de la cookie de sesión de la cabecera `Set-Cookie` del login.
 * El login ya no devuelve el JWT en el cuerpo (204 sin contenido): este valor es
 * lo que los tests reenvían como cookie (en llamadas directas a la API) o siembran
 * en el navegador (`signIn`).
 */
function sessionCookieValue(response: APIResponse): string {
  for (const header of response.headersArray()) {
    if (header.name.toLowerCase() !== 'set-cookie') continue;
    const match = new RegExp(`^${SESSION_COOKIE}=([^;]+)`).exec(header.value);
    if (match) return match[1];
  }
  throw new Error(`El login no fijó la cookie ${SESSION_COOKIE}`);
}

/**
 * Inicia sesión por API y devuelve el valor de la cookie de sesión (el JWT), que
 * las demás funciones de este archivo reenvían como `Cookie` (ver {@link authHeaders}).
 */
export async function loginApi(request: APIRequestContext, email: string, password: string): Promise<string> {
  const response = await request.post(`${BACKEND_URL}/api/auth/login`, {
    data: { email, password },
    headers: { 'X-Requested-With': 'StreamBox' },
  });
  await expectOk(response, `Login de ${email}`);
  return sessionCookieValue(response);
}

/**
 * Login del administrador. Reintenta unos segundos: el servidor ya responde a
 * `/actuator/health` unos instantes ANTES de que `AdminAccountInitializer` haya
 * creado la cuenta, y en ese hueco el login daría 401.
 */
export async function loginAdmin(request: APIRequestContext): Promise<string> {
  const deadline = Date.now() + 30_000;
  for (;;) {
    const response = await request.post(`${BACKEND_URL}/api/auth/login`, {
      data: { email: ADMIN_EMAIL, password: ADMIN_PASSWORD },
      headers: { 'X-Requested-With': 'StreamBox' },
    });
    if (response.ok()) return sessionCookieValue(response);
    if (Date.now() > deadline) {
      throw new Error(`No se pudo iniciar sesión como administrador: ${response.status()} ${await response.text()}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
}

/** Crea un usuario nuevo y devuelve sus datos junto con el valor de su cookie de sesión (el JWT). */
export async function createUserWithToken(request: APIRequestContext): Promise<TestUser & { token: string }> {
  const user = newTestUser();
  await registerUser(request, user);
  const token = await loginApi(request, user.email, user.password);
  return { ...user, token };
}

/**
 * Cabeceras de las llamadas autenticadas: la cookie de sesión y la cabecera
 * anti-CSRF `X-Requested-With`, que el backend exige a las peticiones no seguras
 * autenticadas por cookie. Es la misma autenticación que usa el navegador, así
 * que estas llamadas ejercitan el camino real. (El nombre del parámetro sigue
 * siendo `token`: es el JWT, aunque ahora viaje en una cookie.)
 */
function authHeaders(token: string): Record<string, string> {
  return { Cookie: `${SESSION_COOKIE}=${token}`, 'X-Requested-With': 'StreamBox' };
}

/** Crea un género (administrador) y devuelve su id. */
export async function createGenre(request: APIRequestContext, adminToken: string, name: string): Promise<number> {
  const response = await request.post(`${BACKEND_URL}/api/genres`, {
    headers: authHeaders(adminToken),
    data: { name },
  });
  await expectOk(response, `Crear el género «${name}»`);
  return ((await response.json()) as { id: number }).id;
}

/** Datos para crear una película (los mismos campos que `MovieRequest`). */
export interface NewMovie {
  title: string;
  description: string;
  duration: number;
  releaseYear: number;
  imageUrl: string;
  videoUrl: string;
  genreIds: number[];
}

/** Crea una película (administrador). */
export async function createMovie(request: APIRequestContext, adminToken: string, movie: NewMovie): Promise<void> {
  const response = await request.post(`${BACKEND_URL}/api/movies`, { headers: authHeaders(adminToken), data: movie });
  await expectOk(response, `Crear la película «${movie.title}»`);
}

/** Número total de películas del catálogo (para no sembrar dos veces). */
export async function catalogSize(request: APIRequestContext, token: string): Promise<number> {
  const response = await request.get(`${BACKEND_URL}/api/movies`, { headers: authHeaders(token), params: { size: 1 } });
  await expectOk(response, 'Consultar el tamaño del catálogo');
  return ((await response.json()) as { totalElements: number }).totalElements;
}

/** Busca una película por título exacto recorriendo el catálogo (para obtener su id). */
async function findMovieId(request: APIRequestContext, token: string, title: string): Promise<number> {
  const response = await request.get(`${BACKEND_URL}/api/movies/search`, {
    headers: authHeaders(token),
    params: { title, size: 100 },
  });
  await expectOk(response, `Buscar «${title}»`);
  const page = (await response.json()) as ApiPage<ApiMovie>;
  const match = page.content.find((movie) => movie.title === title);
  if (!match) throw new Error(`La película «${title}» no existe en el catálogo sembrado`);
  return match.id;
}

/**
 * Borra (administrador) todas las películas cuyo título contiene `text`. Es la LIMPIEZA de los tests
 * que crean películas desde la interfaz: si un test falla a mitad, la película no se queda en el
 * catálogo compartido (cambiaría el banner y los totales de otros tests, sobre todo con
 * `E2E_REUSE_SERVERS=1`, que conserva la base de datos entre ejecuciones). Un 404 cuenta como hecho.
 */
export async function deleteMoviesMatching(request: APIRequestContext, adminToken: string, text: string): Promise<void> {
  const response = await request.get(`${BACKEND_URL}/api/movies/search`, {
    headers: authHeaders(adminToken),
    params: { title: text, size: 100 },
  });
  await expectOk(response, `Buscar películas con «${text}»`);
  const page = (await response.json()) as ApiPage<ApiMovie>;
  for (const movie of page.content.filter((item) => item.title.includes(text))) {
    const deleted = await request.delete(`${BACKEND_URL}/api/movies/${movie.id}`, { headers: authHeaders(adminToken) });
    if (deleted.status() !== 404) await expectOk(deleted, `Borrar la película «${movie.title}»`);
  }
}

/** Género tal como lo devuelve la API. */
export interface ApiGenre {
  id: number;
  name: string;
}

/** Lista de géneros (`GET /api/genres`). */
export async function listGenres(request: APIRequestContext, token: string): Promise<ApiGenre[]> {
  const response = await request.get(`${BACKEND_URL}/api/genres`, { headers: authHeaders(token) });
  await expectOk(response, 'Listar los géneros');
  return (await response.json()) as ApiGenre[];
}

/**
 * Id de un género por su nombre (`GET /api/genres`). Lo usan los tests de
 * `/peliculas` para abrir una URL ya filtrada (`?genero=<id>`), como haría un
 * enlace compartido: el id lo asigna la base de datos y no se conoce de antemano.
 */
export async function genreIdByName(request: APIRequestContext, token: string, name: string): Promise<number> {
  const genre = (await listGenres(request, token)).find((item) => item.name === name);
  if (!genre) throw new Error(`No existe el género «${name}»`);
  return genre.id;
}

/**
 * Borra (administrador) los géneros con alguno de estos nombres, si existen. Limpieza de los tests
 * que crean géneros desde la interfaz; un género que ya no está (404) cuenta como hecho.
 */
export async function deleteGenresNamed(
  request: APIRequestContext,
  adminToken: string,
  names: readonly string[],
): Promise<void> {
  for (const genre of (await listGenres(request, adminToken)).filter((item) => names.includes(item.name))) {
    const deleted = await request.delete(`${BACKEND_URL}/api/genres/${genre.id}`, { headers: authHeaders(adminToken) });
    if (deleted.status() !== 404) await expectOk(deleted, `Borrar el género «${genre.name}»`);
  }
}

/** Datos para crear una serie (los mismos campos que `SeriesRequest`). */
export interface NewSeries {
  title: string;
  description: string;
  releaseYear: number;
  endYear: number | null;
  imageUrl: string;
  genreIds: number[];
}

/** Datos para crear un episodio (los mismos campos que `EpisodeRequest`). */
export interface NewEpisode {
  seasonNumber: number;
  episodeNumber: number;
  title: string;
  description: string | null;
  duration: number;
  videoUrl: string;
}

/** Crea una serie (administrador) y devuelve su id. Nace sin episodios. */
export async function createSeries(request: APIRequestContext, adminToken: string, series: NewSeries): Promise<number> {
  const response = await request.post(`${BACKEND_URL}/api/series`, { headers: authHeaders(adminToken), data: series });
  await expectOk(response, `Crear la serie «${series.title}»`);
  return ((await response.json()) as { id: number }).id;
}

/** Añade un episodio a una serie (administrador). */
export async function createEpisode(
  request: APIRequestContext,
  adminToken: string,
  seriesId: number,
  episode: NewEpisode,
): Promise<void> {
  const response = await request.post(`${BACKEND_URL}/api/series/${seriesId}/episodes`, {
    headers: authHeaders(adminToken),
    data: episode,
  });
  await expectOk(response, `Crear el episodio T${episode.seasonNumber}:E${episode.episodeNumber} de la serie ${seriesId}`);
}

/**
 * Número total de series, INCLUIDAS las que no tienen episodios (vista de gestión
 * `GET /api/admin/series`, solo administrador). Sirve para no sembrar dos veces:
 * el listado público no contaría la serie vacía.
 */
export async function seriesCatalogSize(request: APIRequestContext, adminToken: string): Promise<number> {
  const response = await request.get(`${BACKEND_URL}/api/admin/series`, {
    headers: authHeaders(adminToken),
    params: { size: 1 },
  });
  await expectOk(response, 'Consultar el número de series');
  return ((await response.json()) as { totalElements: number }).totalElements;
}

/**
 * Id de una serie por título exacto, mirando la vista de gestión del
 * administrador (así también encuentra la serie SIN episodios, que el listado
 * público oculta). Lo usan los tests que necesitan su URL.
 */
export async function findSeriesIdAsAdmin(request: APIRequestContext, adminToken: string, title: string): Promise<number> {
  const response = await request.get(`${BACKEND_URL}/api/admin/series`, {
    headers: authHeaders(adminToken),
    params: { title, size: 100 },
  });
  await expectOk(response, `Buscar la serie «${title}»`);
  const page = (await response.json()) as ApiPage<ApiMovie>;
  const match = page.content.find((series) => series.title === title);
  if (!match) throw new Error(`La serie «${title}» no existe en el catálogo sembrado`);
  return match.id;
}

/**
 * Borra (administrador) todas las series cuyo título contiene `text`, también las que no tienen
 * episodios (las busca en la vista de gestión `GET /api/admin/series`). Es la LIMPIEZA de los tests
 * que crean series desde el panel: si un test falla a mitad, la serie no se queda en el catálogo
 * compartido. Un 404 cuenta como hecho.
 */
export async function deleteSeriesMatching(request: APIRequestContext, adminToken: string, text: string): Promise<void> {
  const response = await request.get(`${BACKEND_URL}/api/admin/series`, {
    headers: authHeaders(adminToken),
    params: { title: text, size: 100 },
  });
  await expectOk(response, `Buscar series con «${text}»`);
  const page = (await response.json()) as ApiPage<ApiMovie>;
  for (const series of page.content.filter((item) => item.title.includes(text))) {
    const deleted = await request.delete(`${BACKEND_URL}/api/series/${series.id}`, { headers: authHeaders(adminToken) });
    if (deleted.status() !== 404) await expectOk(deleted, `Borrar la serie «${series.title}»`);
  }
}

/** Número de series que ve un USUARIO al buscar `title` (`GET /api/series/search`: solo las que tienen episodios). */
export async function publicSeriesSearchCount(request: APIRequestContext, token: string, title: string): Promise<number> {
  const response = await request.get(`${BACKEND_URL}/api/series/search`, {
    headers: authHeaders(token),
    params: { title, size: 1 },
  });
  await expectOk(response, `Buscar series públicas con «${title}»`);
  return ((await response.json()) as { totalElements: number }).totalElements;
}

/** Añade series (por título) a la lista del usuario, directamente por API. */
export async function addSeriesFavoritesByTitle(
  request: APIRequestContext,
  token: string,
  titles: readonly string[],
): Promise<void> {
  for (const title of titles) {
    const response = await request.get(`${BACKEND_URL}/api/series/search`, {
      headers: authHeaders(token),
      params: { title, size: 100 },
    });
    await expectOk(response, `Buscar la serie «${title}»`);
    const match = ((await response.json()) as ApiPage<ApiMovie>).content.find((series) => series.title === title);
    if (!match) throw new Error(`La serie «${title}» no está en el listado público`);
    const added = await request.post(`${BACKEND_URL}/api/users/me/favorites/series/${match.id}`, { headers: authHeaders(token) });
    await expectOk(added, `Añadir la serie «${title}» a favoritos`);
  }
}

/** Añade películas (por título) a la lista del usuario, directamente por API. */
export async function addFavoritesByTitle(
  request: APIRequestContext,
  token: string,
  titles: readonly string[],
): Promise<void> {
  for (const title of titles) {
    const id = await findMovieId(request, token, title);
    const response = await request.post(`${BACKEND_URL}/api/users/me/favorites/${id}`, { headers: authHeaders(token) });
    await expectOk(response, `Añadir «${title}» a favoritos`);
  }
}
