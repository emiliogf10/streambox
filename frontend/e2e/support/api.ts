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
  const response = await request.post(`${BACKEND_URL}/api/users`, { data: user });
  await expectOk(response, `Registrar a ${user.email}`);
}

/** Inicia sesión por API y devuelve el JWT. */
export async function loginApi(request: APIRequestContext, email: string, password: string): Promise<string> {
  const response = await request.post(`${BACKEND_URL}/api/auth/login`, { data: { email, password } });
  await expectOk(response, `Login de ${email}`);
  const body = (await response.json()) as { token: string };
  return body.token;
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
    });
    if (response.ok()) return ((await response.json()) as { token: string }).token;
    if (Date.now() > deadline) {
      throw new Error(`No se pudo iniciar sesión como administrador: ${response.status()} ${await response.text()}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 500));
  }
}

/** Crea un usuario nuevo y devuelve sus datos junto con un JWT ya válido. */
export async function createUserWithToken(request: APIRequestContext): Promise<TestUser & { token: string }> {
  const user = newTestUser();
  await registerUser(request, user);
  const token = await loginApi(request, user.email, user.password);
  return { ...user, token };
}

/** Cabecera `Authorization` para las llamadas autenticadas. */
function bearer(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}` };
}

/** Crea un género (administrador) y devuelve su id. */
export async function createGenre(request: APIRequestContext, adminToken: string, name: string): Promise<number> {
  const response = await request.post(`${BACKEND_URL}/api/genres`, {
    headers: bearer(adminToken),
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
  const response = await request.post(`${BACKEND_URL}/api/movies`, { headers: bearer(adminToken), data: movie });
  await expectOk(response, `Crear la película «${movie.title}»`);
}

/** Número total de películas del catálogo (para no sembrar dos veces). */
export async function catalogSize(request: APIRequestContext, token: string): Promise<number> {
  const response = await request.get(`${BACKEND_URL}/api/movies`, { headers: bearer(token), params: { size: 1 } });
  await expectOk(response, 'Consultar el tamaño del catálogo');
  return ((await response.json()) as { totalElements: number }).totalElements;
}

/** Busca una película por título exacto recorriendo el catálogo (para obtener su id). */
async function findMovieId(request: APIRequestContext, token: string, title: string): Promise<number> {
  const response = await request.get(`${BACKEND_URL}/api/movies/search`, {
    headers: bearer(token),
    params: { title, size: 100 },
  });
  await expectOk(response, `Buscar «${title}»`);
  const page = (await response.json()) as ApiPage<ApiMovie>;
  const match = page.content.find((movie) => movie.title === title);
  if (!match) throw new Error(`La película «${title}» no existe en el catálogo sembrado`);
  return match.id;
}

/** Añade películas (por título) a la lista del usuario, directamente por API. */
export async function addFavoritesByTitle(
  request: APIRequestContext,
  token: string,
  titles: readonly string[],
): Promise<void> {
  for (const title of titles) {
    const id = await findMovieId(request, token, title);
    const response = await request.post(`${BACKEND_URL}/api/users/me/favorites/${id}`, { headers: bearer(token) });
    await expectOk(response, `Añadir «${title}» a favoritos`);
  }
}
