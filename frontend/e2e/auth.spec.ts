/**
 * E2E de la sesión: registro, login, cierre de sesión (también cuando el servidor
 * no responde: la sesión sigue abierta y se avisa), rutas protegidas,
 * rol del usuario en el menú, sesión caducada (401), renovación con el refresh token
 * (sin aviso, también al recargar y con dos pestañas), aviso de intentos restantes
 * y bloqueo por demasiados intentos (429 de cuenta bloqueada y de límite por IP).
 *
 * Protege el recorrido que hace TODO usuario nuevo. Si el registro no valida,
 * el login no distingue credenciales buenas de malas, o la sesión caducada deja
 * la app en un bucle, esta suite lo detecta con navegador y backend reales.
 */
import type { Page } from '@playwright/test';
import { REFRESH_COOKIE, SESSION_COOKIE, loginAdmin, newTestUser, registerUser } from './support/api';
import { ADMIN_USERNAME, LOCKOUT_MAX_FAILURES, BACKEND_URL } from './support/config';
import { HERO_TITLE, SERIES_HERO } from './support/catalog';
import { expect, formAlert, sessionCookie, test } from './support/fixtures';

/** Cuerpo de error de la API (solo lo que miran estos tests). */
interface ErrorBody {
  code?: string;
  message?: string;
  remainingAttempts?: number;
}

/** Aviso único que muestra la app cuando la sesión caduca y no se puede renovar. */
const SESSION_EXPIRED_TOAST = 'Tu sesión ha caducado. Inicia sesión de nuevo.';

/**
 * Registra un usuario nuevo e inicia sesión con el FORMULARIO, como una persona. Así el navegador tiene
 * las dos cookies reales del login (`streambox_token` y `streambox_refresh`), que es lo que necesitan los
 * tests de la renovación (`signIn` solo siembra la de acceso).
 */
async function loginWithForm(page: Page, request: Parameters<typeof registerUser>[0]): Promise<void> {
  const user = newTestUser();
  await registerUser(request, user);
  await page.goto('/login');
  await page.getByLabel('Correo electrónico').fill(user.email);
  await page.getByLabel('Contraseña').fill(user.password);
  await page.getByRole('button', { name: 'Iniciar sesión' }).click();
  await expect(page.getByRole('heading', { level: 2, name: HERO_TITLE })).toBeVisible();
  expect(await cookieNamed(page, REFRESH_COOKIE), 'el login debe fijar el refresh token').toBeDefined();
}

/** Cookie del contexto del navegador con ese nombre (las HttpOnly solo se ven desde Playwright). */
async function cookieNamed(page: Page, name: string) {
  return (await page.context().cookies()).find((cookie) => cookie.name === name);
}

/**
 * Empieza a anotar las respuestas de `POST /api/auth/refresh` de esta página: su estado (al momento) y su
 * `code` de error (se lee del cuerpo, por eso `codes` es asíncrono; los 204 no tienen cuerpo).
 */
function countRefreshes(page: Page) {
  const statuses: number[] = [];
  const codes: Promise<string | undefined>[] = [];
  page.on('response', (response) => {
    if (!response.url().endsWith('/api/auth/refresh')) return;
    statuses.push(response.status());
    codes.push(
      response.status() === 204
        ? Promise.resolve(undefined)
        : response
            .json()
            .then((body: ErrorBody) => body.code)
            .catch(() => undefined),
    );
  });
  return { statuses: () => [...statuses], codes: () => Promise.all(codes) };
}

/** Aviso de `lib/logout.ts` cuando el cierre de sesión no llega al servidor (ni en el reintento). */
const LOGOUT_FAILED_NETWORK =
  'No se ha podido cerrar la sesión: no hay conexión con el servidor. Tu sesión sigue abierta; inténtalo de nuevo.';

/** Texto del aviso de intentos restantes que muestra la pantalla de login (singular y plural). */
function attemptsMessage(remaining: number): string {
  return remaining === 1
    ? 'Te queda 1 intento antes de que la cuenta se bloquee 15 minutos.'
    : `Te quedan ${remaining} intentos antes de que la cuenta se bloquee 15 minutos.`;
}

/**
 * Pulsa «Iniciar sesión» y devuelve la respuesta REAL del servidor a ese login. Así el test compara la
 * pantalla con lo que dijo el backend (p. ej. `remainingAttempts`) en lugar de suponer un número que
 * depende de su configuración.
 */
async function submitLogin(page: Page): Promise<{ status: number; body: ErrorBody }> {
  const responsePromise = page.waitForResponse(
    (response) => response.url().endsWith('/api/auth/login') && response.request().method() === 'POST',
  );
  await page.getByRole('button', { name: 'Iniciar sesión' }).click();
  const response = await responsePromise;
  const body = (await response.json().catch(() => ({}))) as ErrorBody;
  return { status: response.status(), body };
}

/** Provoca un login fallido por API (rápido) y devuelve su estado y cuerpo. */
async function failLoginByApi(
  request: Parameters<typeof registerUser>[0],
  email: string,
): Promise<{ status: number; body: ErrorBody; retryAfter: string | undefined }> {
  const response = await request.post(`${BACKEND_URL}/api/auth/login`, {
    data: { email, password: 'contrasena-equivocada' },
    headers: { 'X-Requested-With': 'StreamBox' }, // el backend la exige a toda petición no segura
  });
  const body = (await response.json().catch(() => ({}))) as ErrorBody;
  return { status: response.status(), body, retryAfter: response.headers()['retry-after'] };
}

test.describe('Registro', () => {
  test('valida cada campo y, con datos correctos, crea la cuenta y lleva al login con un aviso', async ({ page }) => {
    const user = newTestUser();
    await page.goto('/registro');
    await expect(page.getByRole('heading', { level: 1, name: 'Crea tu cuenta' })).toBeVisible();

    // Envío vacío: un mensaje POR CAMPO y el foco en el primero con error.
    await page.getByRole('button', { name: 'Crear cuenta' }).click();
    await expect(page.getByText('El nombre de usuario debe tener entre 3 y 50 caracteres.')).toBeVisible();
    await expect(page.getByText('Introduce tu correo electrónico.')).toBeVisible();
    await expect(page.getByText('La contraseña debe tener entre 12 y 64 caracteres.')).toBeVisible();
    await expect(page.getByLabel('Nombre de usuario')).toBeFocused();
    await expect(page.getByLabel('Nombre de usuario')).toHaveAttribute('aria-invalid', 'true');
    await expect(page).toHaveURL(/\/registro$/);

    // Corregir un campo borra SU error y solo el suyo.
    await page.getByLabel('Nombre de usuario').fill(user.username);
    await expect(page.getByText('El nombre de usuario debe tener entre 3 y 50 caracteres.')).toBeHidden();
    await expect(page.getByText('Introduce tu correo electrónico.')).toBeVisible();

    // Correo con formato inválido.
    await page.getByLabel('Correo electrónico').fill('esto-no-es-un-correo');
    await page.getByLabel('Contraseña').fill('corta');
    await page.getByRole('button', { name: 'Crear cuenta' }).click();
    await expect(page.getByText('Introduce un correo electrónico válido.')).toBeVisible();
    await expect(page.getByText('La contraseña debe tener entre 12 y 64 caracteres.')).toBeVisible();

    // Datos válidos: éxito, redirección al login y aviso visible.
    await page.getByLabel('Correo electrónico').fill(user.email);
    await page.getByLabel('Contraseña').fill(user.password);
    await page.getByRole('button', { name: 'Crear cuenta' }).click();
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByText('Cuenta creada, ya puedes iniciar sesión.')).toBeVisible();
  });

  test('un correo ya registrado muestra el error del servidor sin salir del formulario', async ({ page, request }) => {
    const existing = newTestUser();
    await registerUser(request, existing);

    await page.goto('/registro');
    await page.getByLabel('Nombre de usuario').fill(`otro_${existing.username}`.slice(0, 50));
    await page.getByLabel('Correo electrónico').fill(existing.email);
    await page.getByLabel('Contraseña').fill(existing.password);
    await page.getByRole('button', { name: 'Crear cuenta' }).click();

    await expect(formAlert(page).filter({ hasText: /\S/ })).toBeVisible();
    await expect(page).toHaveURL(/\/registro$/);
  });
});

test.describe('Login y cierre de sesión', () => {
  test('credenciales erróneas: mensaje genérico, intentos que quedan y sin sesión', async ({ page, request }) => {
    // Email único: los fallos cuentan por cuenta y no se suman a los de otros tests.
    const user = newTestUser();
    await registerUser(request, user);

    await page.goto('/login');
    await page.getByLabel('Correo electrónico').fill(user.email);
    await page.getByLabel('Contraseña').fill('contrasena-equivocada');
    const { status, body } = await submitLogin(page);

    expect(status).toBe(401);
    expect(body.code).toBe('INVALID_CREDENTIALS');
    const remaining = body.remainingAttempts ?? 0;
    expect(Number.isInteger(remaining) && remaining >= 1, `remainingAttempts debe ser un entero ≥ 1 (llegó ${remaining})`).toBe(
      true,
    );

    // El mensaje no revela si lo que falla es el correo o la contraseña; en el MISMO aviso, cuántos
    // intentos quedan: el número que ha dicho el servidor, no uno supuesto.
    await expect(formAlert(page)).toContainText('Correo o contraseña incorrectos.');
    await expect(formAlert(page)).toContainText(attemptsMessage(remaining));
    await expect(page).toHaveURL(/\/login$/);
    expect(await sessionCookie(page)).toBeUndefined();
  });

  test('con el último intento el aviso pasa a singular («Te queda 1 intento»)', async ({ page, request }) => {
    const user = newTestUser();
    await registerUser(request, user);

    // Se gastan intentos por API hasta que el servidor diga que quedan 2 (sin suponer el límite configurado).
    let remaining = Number.POSITIVE_INFINITY;
    for (let attempt = 0; attempt < LOCKOUT_MAX_FAILURES && remaining > 2; attempt += 1) {
      const failed = await failLoginByApi(request, user.email);
      expect(failed.status).toBe(401);
      remaining = failed.body.remainingAttempts ?? 0;
    }
    expect(remaining, 'el servidor debía llegar a «quedan 2»').toBe(2);

    await page.goto('/login');
    await page.getByLabel('Correo electrónico').fill(user.email);
    await page.getByLabel('Contraseña').fill('contrasena-equivocada');
    const { status, body } = await submitLogin(page);

    expect(status).toBe(401);
    expect(body.remainingAttempts).toBe(1);
    await expect(formAlert(page)).toContainText('Te queda 1 intento antes de que la cuenta se bloquee 15 minutos.');
  });

  test('un campo vacío se avisa sin llamar al servidor', async ({ page }) => {
    await page.goto('/login');
    await page.getByRole('button', { name: 'Iniciar sesión' }).click();
    await expect(formAlert(page)).toHaveText('Introduce tu correo electrónico y tu contraseña.');
  });

  test('login correcto lleva a la portada, la sesión sobrevive a recargar y cerrar sesión vuelve al login', async ({
    page,
    request,
  }) => {
    const user = newTestUser();
    await registerUser(request, user);

    await page.goto('/login');
    await page.getByLabel('Correo electrónico').fill(user.email);
    await page.getByLabel('Contraseña').fill(user.password);
    await page.getByRole('button', { name: 'Iniciar sesión' }).click();

    await expect(page).toHaveURL(/\/$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Catálogo de películas' })).toBeAttached();
    await expect(page.getByRole('heading', { level: 2, name: HERO_TITLE })).toBeVisible();

    // El JWT vive en una cookie HttpOnly: el navegador la tiene, pero JavaScript no la ve
    // (ni en `document.cookie` ni en `localStorage`).
    const cookie = await sessionCookie(page);
    expect(cookie?.httpOnly).toBe(true);
    expect(cookie?.sameSite).toBe('Strict');
    expect(await page.evaluate(() => document.cookie)).toBe('');
    expect(await page.evaluate(() => localStorage.length)).toBe(0);

    // La sesión se conserva al recargar (la cookie la envía el navegador y `/users/me` la confirma).
    await page.reload();
    await expect(page.getByRole('heading', { level: 2, name: HERO_TITLE })).toBeVisible();

    // Cerrar sesión desde el menú de usuario.
    await page.getByRole('button', { name: 'Menú de usuario' }).click();
    await page.getByRole('button', { name: 'Cerrar sesión' }).click();
    await expect(page).toHaveURL(/\/login$/);
    // El cierre NO es optimista: la interfaz solo vuelve al login cuando el servidor ha respondido al
    // POST /api/auth/logout, cuyo Set-Cookie borra la cookie. Al llegar al login ya no debe existir.
    expect(await sessionCookie(page)).toBeUndefined();

    // Y ya no se puede volver a la zona privada.
    await page.goto('/');
    await expect(page).toHaveURL(/\/login$/);
  });

  test('si el servidor no responde al cerrar sesión, la sesión sigue abierta (de verdad) y se avisa; al volver, se cierra', async ({
    page,
    user,
    signIn,
  }) => {
    await signIn(user);
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 2, name: HERO_TITLE })).toBeVisible();

    // Se corta la red SOLO para el logout: no llegan ni el primer intento ni el reintento automático.
    let attempts = 0;
    await page.route('**/api/auth/logout', (route) => {
      attempts += 1;
      return route.abort('connectionrefused');
    });

    await page.getByRole('button', { name: 'Menú de usuario' }).click();
    await page.getByRole('button', { name: 'Cerrar sesión' }).click();
    // Mientras espera el reintento, el botón lo dice y no se puede repetir.
    await expect(page.getByRole('button', { name: 'Cerrando sesión...' })).toHaveAttribute('aria-disabled', 'true');

    await expect(page.getByText(LOGOUT_FAILED_NETWORK)).toBeVisible();
    expect(attempts).toBe(2);
    // No se miente: sigue en la portada con la sesión, la cookie sigue ahí y el botón vuelve a estar disponible.
    await expect(page).toHaveURL(/\/$/);
    expect(await sessionCookie(page)).toBeDefined();
    await expect(page.getByRole('button', { name: 'Cerrar sesión' })).not.toHaveAttribute('aria-disabled');

    // La prueba de que la interfaz decía la verdad: al recargar, la sesión sigue abierta.
    await page.reload();
    await expect(page.getByRole('heading', { level: 2, name: HERO_TITLE })).toBeVisible();

    // Con el servidor alcanzable de nuevo, el mismo botón cierra la sesión.
    await page.unroute('**/api/auth/logout');
    await page.getByRole('button', { name: 'Menú de usuario' }).click();
    await page.getByRole('button', { name: 'Cerrar sesión' }).click();
    await expect(page).toHaveURL(/\/login$/);
    expect(await sessionCookie(page)).toBeUndefined();
  });

  test('una ruta protegida sin sesión redirige al login', async ({ page }) => {
    await page.goto('/favorites');
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Bienvenido de nuevo' })).toBeVisible();
  });

  test('con sesión abierta, /login redirige a la portada', async ({ page, user, signIn }) => {
    await signIn(user);
    await page.goto('/login');
    await expect(page).toHaveURL(/\/$/);
  });
});

test.describe('Rol en el menú de usuario (GET /api/users/me)', () => {
  test('un usuario normal ve su nombre en el menú, pero NO la etiqueta «Administrador»', async ({
    page,
    user,
    signIn,
  }) => {
    await signIn(user);
    await page.goto('/');

    await page.getByRole('button', { name: 'Menú de usuario' }).click();
    // Primero se espera al nombre: así el usuario ya está cargado y la ausencia de la etiqueta significa algo.
    await expect(page.getByText(user.username, { exact: true })).toBeVisible();
    await expect(page.getByText('Administrador', { exact: true })).toHaveCount(0);
  });

  test('el administrador ve la etiqueta «Administrador», también tras recargar la página', async ({
    page,
    request,
    signIn,
  }) => {
    // El administrador del backend efímero (el mismo con el que se siembra el catálogo).
    await signIn({ token: await loginAdmin(request) });
    await page.goto('/');

    const toggle = page.getByRole('button', { name: 'Menú de usuario' });
    await toggle.click();
    await expect(page.getByText(ADMIN_USERNAME, { exact: true })).toBeVisible();
    await expect(page.getByText('Administrador', { exact: true })).toBeVisible();

    // Con la cookie de sesión, el rol se vuelve a pedir al servidor (no se guarda en el cliente).
    await page.reload();
    await toggle.click();
    await expect(page.getByText('Administrador', { exact: true })).toBeVisible();
  });
});

test.describe('Sesión caducada (401)', () => {
  test('una cookie inválida al cargar (y sin refresh que la renueve) lleva al login sin aviso de «sesión caducada»', async ({
    page,
  }) => {
    // Primer chequeo de la app (`GET /users/me`): un 401 se intenta arreglar con el refresh token; como
    // tampoco vale, solo significa «no hay sesión». Las dos cookies, inventadas.
    await page.context().addCookies([
      { name: SESSION_COOKIE, value: 'token.invalido.caducado', domain: 'localhost', path: '/api', httpOnly: true },
      { name: REFRESH_COOKIE, value: 'refresh-invalido', domain: 'localhost', path: '/api/auth', httpOnly: true },
    ]);
    const refresh = page.waitForResponse((response) => response.url().endsWith('/api/auth/refresh'));
    await page.goto('/');
    expect((await refresh).status()).toBe(401);
    await expect(page).toHaveURL(/\/login$/);
    await page.waitForLoadState('networkidle');
    await expect(page.getByText(SESSION_EXPIRED_TOAST)).toHaveCount(0);
  });

  test('una sesión que caduca con la app abierta (y el refresh token ya no vale) lleva al login UNA sola vez con UN solo aviso', async ({
    page,
    user,
    signIn,
  }) => {
    // Registra el recorrido de rutas. En desarrollo React StrictMode ejecuta dos veces el efecto de
    // `<Navigate>`, así que se colapsan los repetidos consecutivos: lo que importa es que la secuencia
    // sea "/series" → "/login" y no un bucle.
    const visited: string[] = [];
    page.on('framenavigated', (frame) => {
      if (frame !== page.mainFrame()) return;
      const pathname = new URL(frame.url()).pathname;
      if (visited[visited.length - 1] !== pathname) visited.push(pathname);
    });

    await signIn(user);
    await page.goto('/');
    await expect(page.getByRole('heading', { level: 2, name: HERO_TITLE })).toBeVisible();

    // La sesión caduca en el servidor mientras la app sigue abierta: se sustituye la cookie de acceso por
    // una inválida y se pone un refresh token que tampoco vale (revocado, caducado...). Sin el segundo, el
    // test dependería de que `signIn` no siembra el refresh token: si algún día lo hiciera, la app
    // renovaría la sesión en silencio y esto ya no probaría la caducidad.
    await page.context().addCookies([
      { name: SESSION_COOKIE, value: 'token.invalido.caducado', domain: 'localhost', path: '/api', httpOnly: true },
      { name: REFRESH_COOKIE, value: 'refresh-revocado', domain: 'localhost', path: '/api/auth', httpOnly: true },
    ]);
    visited.length = 0;
    const refreshes = countRefreshes(page);

    // Navegar DENTRO de la SPA (sin recargar) a una página que pide datos nuevos: darán 401. (Mi lista no sirve: los favoritos ya están en memoria y no hay petición.)
    await page.getByRole('link', { name: 'Series', exact: true }).first().click();
    await expect(page).toHaveURL(/\/login$/);
    await page.waitForLoadState('networkidle');

    await expect(page.getByText(SESSION_EXPIRED_TOAST)).toHaveCount(1);
    expect(visited).toEqual(['/series', '/login']);
    // Varias peticiones de /series reciben el 401 a la vez, pero solo hay UN refresh (el servidor lo rechaza
    // con SESSION_EXPIRED) y nunca se reintenta.
    expect(refreshes.statuses()).toEqual([401]);
    expect(await refreshes.codes()).toEqual(['SESSION_EXPIRED']);
  });
});

test.describe('Sesión renovable (refresh token)', () => {
  test('al caducar el token de acceso, la app lo renueva sola: sin aviso, sin salir de la página y con UN solo refresh', async ({
    page,
    request,
  }) => {
    await loginWithForm(page, request);
    // A los 15 minutos el navegador descarta la cookie de acceso (Max-Age=900); el refresh token sigue.
    await page.context().clearCookies({ name: SESSION_COOKIE });
    expect(await sessionCookie(page)).toBeUndefined();
    expect(await cookieNamed(page, REFRESH_COOKIE)).toBeDefined();
    const refreshes = countRefreshes(page);

    // Navegar dentro de la SPA: /series lanza varias peticiones a la vez y todas reciben 401.
    const nav = page.getByRole('navigation', { name: 'Principal' });
    await nav.getByRole('link', { name: 'Series' }).click();

    await expect(page).toHaveURL(/\/series$/);
    await expect(page.getByRole('region', { name: SERIES_HERO.title })).toBeVisible();
    await page.waitForLoadState('networkidle');
    await expect(page.getByText(SESSION_EXPIRED_TOAST)).toHaveCount(0);
    expect(refreshes.statuses()).toEqual([204]);
    // El refresh dejó una cookie de acceso nueva.
    expect(await sessionCookie(page)).toBeDefined();
  });

  test('«vuelves al día siguiente»: sin cookie de acceso pero con el refresh token, recargar entra sin pasar por el login', async ({
    page,
    request,
  }) => {
    await loginWithForm(page, request);
    await page.context().clearCookies({ name: SESSION_COOKIE });
    const refreshes = countRefreshes(page);
    const visited: string[] = [];
    page.on('framenavigated', (frame) => {
      if (frame === page.mainFrame()) visited.push(new URL(frame.url()).pathname);
    });

    await page.reload();

    await expect(page.getByRole('heading', { level: 2, name: HERO_TITLE })).toBeVisible();
    await expect(page).toHaveURL(/\/$/);
    expect(visited).not.toContain('/login');
    await expect(page.getByText(SESSION_EXPIRED_TOAST)).toHaveCount(0);
    expect(refreshes.statuses()).toEqual([204]);
  });

  test('sin ninguna de las dos cookies, un aviso único de sesión caducada y al login', async ({ page, request }) => {
    await loginWithForm(page, request);
    await page.context().clearCookies();
    const refreshes = countRefreshes(page);

    await page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Series' }).click();

    await expect(page).toHaveURL(/\/login$/);
    await page.waitForLoadState('networkidle');
    await expect(page.getByText(SESSION_EXPIRED_TOAST)).toHaveCount(1);
    expect(refreshes.statuses()).toEqual([401]);
  });

  test('tras renovar, cerrar sesión borra las DOS cookies y la sesión ya no se puede renovar', async ({ page, request }) => {
    await loginWithForm(page, request);
    await page.context().clearCookies({ name: SESSION_COOKIE });
    await page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Series' }).click();
    await expect(page.getByRole('region', { name: SERIES_HERO.title })).toBeVisible();

    await page.getByRole('button', { name: 'Menú de usuario' }).click();
    await page.getByRole('button', { name: 'Cerrar sesión' }).click();
    await expect(page).toHaveURL(/\/login$/);
    expect(await sessionCookie(page)).toBeUndefined();
    expect(await cookieNamed(page, REFRESH_COOKIE)).toBeUndefined();

    // Ni recargando la zona privada se vuelve a entrar.
    await page.goto('/');
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByText(SESSION_EXPIRED_TOAST)).toHaveCount(0);
  });

  test('dos pestañas a las que les caduca el token a la vez renuevan sin pisarse: ninguna pierde la sesión', async ({
    page,
    request,
  }) => {
    await loginWithForm(page, request);
    const second = await page.context().newPage();
    await second.goto('/');
    await expect(second.getByRole('heading', { level: 2, name: HERO_TITLE })).toBeVisible();

    await page.context().clearCookies({ name: SESSION_COOKIE });
    const firstRefreshes = countRefreshes(page);
    const secondRefreshes = countRefreshes(second);

    // Las dos navegan a la vez: las dos reciben 401 casi al mismo tiempo.
    await Promise.all([
      page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Series' }).click(),
      second.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Series' }).click(),
    ]);

    for (const tab of [page, second]) {
      await expect(tab.getByRole('region', { name: SERIES_HERO.title })).toBeVisible();
      await tab.waitForLoadState('networkidle');
      await expect(tab.getByText(SESSION_EXPIRED_TOAST)).toHaveCount(0);
    }
    // Como mucho un refresh por pestaña (el Web Lock las pone en fila y la segunda, si se entera a tiempo
    // del aviso de la primera, ni lo pide) y ninguno rechazado: reutilizar un refresh token ya rotado
    // habría revocado la sesión entera.
    const statuses = [...firstRefreshes.statuses(), ...secondRefreshes.statuses()];
    expect(statuses.length).toBeGreaterThanOrEqual(1);
    expect(statuses.length).toBeLessThanOrEqual(2);
    expect(statuses.every((status) => status === 204)).toBe(true);

    // La prueba de que el servidor no revocó nada: al recargar (/series), las dos siguen dentro.
    for (const tab of [page, second]) {
      await tab.reload();
      await expect(tab.getByRole('region', { name: SERIES_HERO.title })).toBeVisible();
      await expect(tab).toHaveURL(/\/series$/);
    }
    await second.close();
  });
});

test.describe('Demasiados intentos (429)', () => {
  test('el fallo que alcanza el límite bloquea la cuenta: mensaje propio y cuenta atrás en minutos', async ({
    page,
    request,
  }) => {
    const user = newTestUser();
    await registerUser(request, user);

    // Los fallos se provocan por API (rápido); el bloqueo se comprueba después en la UI. Los anteriores al
    // límite son 401 con los intentos que quedan (cada vez uno menos); el que lo alcanza ya es un 429.
    let previous: number | undefined;
    for (let attempt = 1; attempt < LOCKOUT_MAX_FAILURES; attempt += 1) {
      const failed = await failLoginByApi(request, user.email);
      expect(failed.status, `fallo ${attempt}`).toBe(401);
      const remaining = failed.body.remainingAttempts ?? 0;
      expect(remaining, `fallo ${attempt}: remainingAttempts`).toBeGreaterThanOrEqual(1);
      if (previous !== undefined) expect(remaining, `fallo ${attempt}: debe quedar uno menos`).toBe(previous - 1);
      previous = remaining;
    }
    const locking = await failLoginByApi(request, user.email);
    expect(locking.status, 'el fallo que alcanza el límite').toBe(429);
    expect(locking.body.code).toBe('ACCOUNT_LOCKED');
    expect(Number(locking.retryAfter)).toBeGreaterThan(0);

    await page.goto('/login');
    await page.getByLabel('Correo electrónico').fill(user.email);
    // Ni siquiera la contraseña CORRECTA entra mientras la cuenta está bloqueada.
    await page.getByLabel('Contraseña').fill(user.password);
    const { status, body } = await submitLogin(page);

    expect(status).toBe(429);
    expect(body.code).toBe('ACCOUNT_LOCKED');
    // Mensaje de CUENTA bloqueada (no el de "demasiados intentos" del límite por IP), con la espera en minutos.
    await expect(formAlert(page)).toContainText('Tu cuenta está bloqueada temporalmente por demasiados intentos fallidos.');
    await expect(formAlert(page)).toContainText(/Podrás volver a intentarlo en \d+ min( \d+ s)?\./);
    await expect(formAlert(page)).not.toContainText('Demasiados intentos.');
    await expect(page.getByRole('button', { name: /^Reintentar en \d+ min( \d+ s)?$/ })).toBeDisabled();
    await expect(page).toHaveURL(/\/login$/);
    expect(await sessionCookie(page)).toBeUndefined();
  });

  test('cuenta bloqueada con Retry-After de 2 min 5 s: la espera se lee en minutos y segundos', async ({ page }) => {
    // Respuesta simulada para fijar la espera exacta (el bloqueo real dura 15 min y el segundo exacto varía).
    await page.route('**/api/auth/login', (route) =>
      route.fulfill({
        status: 429,
        headers: { 'Retry-After': '125', 'Content-Type': 'application/json' },
        body: JSON.stringify({
          timestamp: new Date().toISOString(),
          status: 429,
          error: 'Too Many Requests',
          code: 'ACCOUNT_LOCKED',
          message: 'Cuenta bloqueada temporalmente.',
          path: '/api/auth/login',
        }),
      }),
    );

    await page.goto('/login');
    await page.getByLabel('Correo electrónico').fill('cualquiera@streambox.local');
    await page.getByLabel('Contraseña').fill('cualquier-contrasena');
    await page.getByRole('button', { name: 'Iniciar sesión' }).click();

    await expect(formAlert(page)).toContainText('Podrás volver a intentarlo en 2 min 5 s.');
    await expect(page.getByRole('button', { name: /^Reintentar en 2 min [0-5] s$/ })).toBeDisabled();
  });

  test('con Retry-After en la respuesta el botón se bloquea, cuenta atrás y se reactiva al terminar', async ({ page }) => {
    // Respuesta simulada con espera corta (3 s): el bloqueo real dura 15 min y no permitiría ver cómo termina.
    await page.route('**/api/auth/login', (route) =>
      route.fulfill({
        status: 429,
        headers: { 'Retry-After': '3', 'Content-Type': 'application/json' },
        body: JSON.stringify({
          timestamp: new Date().toISOString(),
          status: 429,
          error: 'Too Many Requests',
          code: 'RATE_LIMIT_EXCEEDED',
          message: 'Demasiados intentos fallidos. Inténtalo de nuevo más tarde.',
          path: '/api/auth/login',
        }),
      }),
    );

    await page.goto('/login');
    await page.getByLabel('Correo electrónico').fill('cualquiera@streambox.local');
    await page.getByLabel('Contraseña').fill('cualquier-contrasena');
    await page.getByRole('button', { name: 'Iniciar sesión' }).click();

    // El mensaje usa los segundos de la cabecera, no un número inventado, y es el del límite por IP:
    // no habla de cuenta bloqueada (eso es `ACCOUNT_LOCKED`).
    await expect(formAlert(page)).toContainText('Inténtalo de nuevo en 3 s');
    await expect(formAlert(page)).not.toContainText('bloqueada');
    await expect(page.getByRole('button', { name: /^Reintentar en [123] s$/ })).toBeDisabled();

    // Al acabar la cuenta atrás, el botón vuelve a estar disponible y el aviso desaparece.
    await expect(page.getByRole('button', { name: 'Iniciar sesión' })).toBeEnabled({ timeout: 10_000 });
    await expect(formAlert(page)).toBeHidden();
  });
});
