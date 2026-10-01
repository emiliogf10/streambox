/**
 * E2E de la sesión: registro, login, cierre de sesión, rutas protegidas,
 * sesión caducada (401) y bloqueo por demasiados intentos (429).
 *
 * Protege el recorrido que hace TODO usuario nuevo. Si el registro no valida,
 * el login no distingue credenciales buenas de malas, o la sesión caducada deja
 * la app en un bucle, esta suite lo detecta con navegador y backend reales.
 */
import { newTestUser, registerUser } from './support/api';
import { LOCKOUT_MAX_FAILURES, BACKEND_URL } from './support/config';
import { HERO_TITLE } from './support/catalog';
import { expect, formAlert, test } from './support/fixtures';

test.describe('Registro', () => {
  test('valida cada campo y, con datos correctos, crea la cuenta y lleva al login con un aviso', async ({ page }) => {
    const user = newTestUser();
    await page.goto('/registro');
    await expect(page.getByRole('heading', { level: 1, name: 'Crea tu cuenta' })).toBeVisible();

    // Envío vacío: un mensaje POR CAMPO y el foco en el primero con error.
    await page.getByRole('button', { name: 'Crear cuenta' }).click();
    await expect(page.getByText('El nombre de usuario debe tener entre 3 y 50 caracteres.')).toBeVisible();
    await expect(page.getByText('Introduce tu correo electrónico.')).toBeVisible();
    await expect(page.getByText('La contraseña debe tener entre 8 y 100 caracteres.')).toBeVisible();
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
    await expect(page.getByText('La contraseña debe tener entre 8 y 100 caracteres.')).toBeVisible();

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
  test('credenciales erróneas muestran el mensaje genérico y no abren sesión', async ({ page, request }) => {
    const user = newTestUser();
    await registerUser(request, user);

    await page.goto('/login');
    await page.getByLabel('Correo electrónico').fill(user.email);
    await page.getByLabel('Contraseña').fill('contrasena-equivocada');
    await page.getByRole('button', { name: 'Iniciar sesión' }).click();

    // El mensaje no revela si lo que falla es el correo o la contraseña.
    await expect(formAlert(page)).toHaveText('Correo o contraseña incorrectos.');
    await expect(page).toHaveURL(/\/login$/);
    expect(await page.evaluate(() => localStorage.getItem('token'))).toBeNull();
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
  test('tras los fallos permitidos el servidor bloquea la cuenta y el botón muestra la cuenta atrás', async ({
    page,
    request,
  }) => {
    const user = newTestUser();
    await registerUser(request, user);

    // Los fallos se provocan por API (rápido); el bloqueo posterior se comprueba en la UI.
    for (let attempt = 0; attempt < LOCKOUT_MAX_FAILURES; attempt += 1) {
      const response = await request.post(`${BACKEND_URL}/api/auth/login`, {
        data: { email: user.email, password: 'contrasena-equivocada' },
      });
      expect(response.status()).toBe(401);
    }

    await page.goto('/login');
    await page.getByLabel('Correo electrónico').fill(user.email);
    // Ni siquiera la contraseña CORRECTA entra mientras la cuenta está bloqueada.
    await page.getByLabel('Contraseña').fill(user.password);
    await page.getByRole('button', { name: 'Iniciar sesión' }).click();

    await expect(formAlert(page)).toContainText(/Demasiados intentos/);
    const blocked = page.getByRole('button', { name: /^Reintentar en \d+ s$/ });
    await expect(blocked).toBeDisabled();
    await expect(page).toHaveURL(/\/login$/);
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

    // El mensaje usa los segundos de la cabecera, no un número inventado.
    await expect(formAlert(page)).toContainText('Inténtalo de nuevo en 3 s');
    await expect(page.getByRole('button', { name: /^Reintentar en [123] s$/ })).toBeDisabled();

    // Al acabar la cuenta atrás, el botón vuelve a estar disponible y el aviso desaparece.
    await expect(page.getByRole('button', { name: 'Iniciar sesión' })).toBeEnabled({ timeout: 10_000 });
    await expect(formAlert(page)).toBeHidden();
  });
});
