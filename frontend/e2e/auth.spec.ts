/**
 * E2E de la sesión: registro, login, cierre de sesión, rutas protegidas,
 * rol del usuario en el menú, sesión caducada (401), aviso de intentos restantes
 * y bloqueo por demasiados intentos (429 de cuenta bloqueada y de límite por IP).
 *
 * Protege el recorrido que hace TODO usuario nuevo. Si el registro no valida,
 * el login no distingue credenciales buenas de malas, o la sesión caducada deja
 * la app en un bucle, esta suite lo detecta con navegador y backend reales.
 */
import type { Page } from '@playwright/test';
import { loginAdmin, newTestUser, registerUser } from './support/api';
import { ADMIN_USERNAME, LOCKOUT_MAX_FAILURES, BACKEND_URL } from './support/config';
import { HERO_TITLE } from './support/catalog';
import { expect, formAlert, test } from './support/fixtures';

/** Cuerpo de error de la API (solo lo que miran estos tests). */
interface ErrorBody {
  code?: string;
  message?: string;
  remainingAttempts?: number;
}

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
    expect(await page.evaluate(() => localStorage.getItem('token'))).toBeNull();
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

    // La sesión se conserva al recargar (el token está en localStorage).
    await page.reload();
    await expect(page.getByRole('heading', { level: 2, name: HERO_TITLE })).toBeVisible();

    // Cerrar sesión desde el menú de usuario.
    await page.getByRole('button', { name: 'Menú de usuario' }).click();
    await page.getByRole('button', { name: 'Cerrar sesión' }).click();
    await expect(page).toHaveURL(/\/login$/);
    expect(await page.evaluate(() => localStorage.getItem('token'))).toBeNull();

    // Y ya no se puede volver a la zona privada.
    await page.goto('/');
    await expect(page).toHaveURL(/\/login$/);
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

    // Con el token guardado, el rol se vuelve a pedir al servidor (no se guarda en el cliente).
    await page.reload();
    await toggle.click();
    await expect(page.getByText('Administrador', { exact: true })).toBeVisible();
  });
});

test.describe('Sesión caducada (401)', () => {
  test('un token inválido lleva al login UNA sola vez con UN solo aviso', async ({ page }) => {
    // Registra el recorrido de rutas. En desarrollo React StrictMode ejecuta dos veces el efecto de
    // `<Navigate>`, así que se colapsan los repetidos consecutivos: lo que importa es que la secuencia
    // sea "/" → "/login" y no un bucle ("/" → "/login" → "/" → "/login"...).
    const visited: string[] = [];
    page.on('framenavigated', (frame) => {
      if (frame !== page.mainFrame()) return;
      const pathname = new URL(frame.url()).pathname;
      if (visited[visited.length - 1] !== pathname) visited.push(pathname);
    });

    await page.goto('/login');
    await page.evaluate(() => localStorage.setItem('token', 'token.invalido.caducado'));
    visited.length = 0;

    // La portada lanza varias peticiones a la vez (catálogo, favoritos...): todas darán 401.
    await page.goto('/');
    await expect(page).toHaveURL(/\/login$/);
    await page.waitForLoadState('networkidle');

    await expect(page.getByText('Tu sesión ha caducado. Inicia sesión de nuevo.')).toHaveCount(1);
    expect(await page.evaluate(() => localStorage.getItem('token'))).toBeNull();
    expect(visited).toEqual(['/', '/login']);
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
    expect(await page.evaluate(() => localStorage.getItem('token'))).toBeNull();
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
