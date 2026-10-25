/**
 * E2E de «He olvidado mi contraseña»: login → «¿Has olvidado tu contraseña?» → `/recuperar-contrasena` →
 * «Revisa tu correo» → enlace del buzón → `/restablecer-contrasena#token=…` → contraseña nueva → login con
 * la nueva (y no con la vieja). Además: el enlace ya usado o sustituido por otro no vale; una contraseña
 * que rechaza la política NO gasta el enlace; una cuenta pendiente de confirmar queda confirmada al
 * restablecer (y entra); y las sesiones abiertas antes, en este navegador y en otro «dispositivo», se cierran.
 *
 * El correo no sale de verdad: con el perfil `e2e` el backend lo guarda en una bandeja en memoria que estos
 * tests leen como administrador (`waitForEmail`, ver `support/api.ts`). Los enlaces apuntan al Vite de esta
 * suite (`STREAMBOX_PUBLIC_URL` en `playwright.config.ts`), así que se abren tal cual llegan.
 *
 * Protege lo que los tests unitarios no pueden ver: que el enlace que construye el backend lleva a la
 * página que lo entiende, que el token sale de la barra de direcciones, que la contraseña cambia de verdad
 * en el servidor y que las sesiones anteriores dejan de renovarse. No tocan el catálogo: proyecto principal.
 *
 * Necesita, en el backend de la suite, los límites por IP de las dos rutas subidos y el límite SILENCIOSO por
 * cuenta (60 s entre correos, 3 por hora) quitado: con él, pedir un segundo enlace justo después del primero
 * respondería 202 sin enviar nada y el test esperaría un correo que no llega (ver `playwright.config.ts`).
 */
import { randomUUID } from 'node:crypto';
import { request as playwrightRequest } from '@playwright/test';
import type { APIRequestContext, Page } from '@playwright/test';
import {
  PASSWORD_CHANGED_SUBJECT,
  PASSWORD_RESET_SUBJECT,
  confirmPasswordResetApi,
  newTestUser,
  passwordResetLink,
  passwordResetToken,
  registerUser,
  registerVerifiedUser,
  requestPasswordResetApi,
  waitForEmail,
} from './support/api';
import type { TestUser } from './support/api';
import { HERO_TITLE } from './support/catalog';
import { BACKEND_URL, FRONTEND_URL } from './support/config';
import { expect, formAlert, sessionCookie, test } from './support/fixtures';

/** Título de `/restablecer-contrasena` cuando el token no vale (inválido, caducado, usado o sustituido). */
const INVALID_LINK_TITLE = 'El enlace no es válido o ha caducado';

/** Título del formulario de `/restablecer-contrasena`. */
const RESET_FORM_TITLE = 'Elige una contraseña nueva';

/** Una contraseña nueva válida y única (≥12, sin el usuario ni el correo de la cuenta, no común). */
function newPassword(): string {
  return `Nueva-${randomUUID().slice(0, 8)}-Clave`;
}

/** Intenta iniciar sesión por API y devuelve el estado y el `code` de error (si lo hay). */
async function loginAttempt(
  request: APIRequestContext,
  email: string,
  password: string,
): Promise<{ status: number; code: string | undefined }> {
  const response = await request.post(`${BACKEND_URL}/api/auth/login`, {
    data: { email, password },
    headers: { 'X-Requested-With': 'StreamBox' },
  });
  const body = (await response.json().catch(() => ({}))) as { code?: string };
  return { status: response.status(), code: body.code };
}

/** Inicia sesión con el formulario y espera a la portada. */
async function loginWithForm(page: Page, email: string, password: string): Promise<void> {
  await page.getByLabel('Correo electrónico').fill(email);
  await page.getByLabel('Contraseña', { exact: true }).fill(password);
  await page.getByRole('button', { name: 'Iniciar sesión' }).click();
  await expect(page.getByRole('heading', { level: 2, name: HERO_TITLE })).toBeVisible();
  expect(await sessionCookie(page)).toBeDefined();
}

/** Escribe la contraseña nueva (dos veces) en `/restablecer-contrasena` y la envía. */
async function submitNewPassword(page: Page, password: string): Promise<void> {
  await page.getByLabel('Contraseña nueva', { exact: true }).fill(password);
  await page.getByLabel('Repite la contraseña nueva').fill(password);
  await page.getByRole('button', { name: 'Cambiar la contraseña' }).click();
}

/** Pide el enlace por API y devuelve el correo que llega (el `n`-ésimo con ese asunto, empezando en 1). */
async function requestResetEmail(request: APIRequestContext, user: TestUser, alreadySeen = 0) {
  await requestPasswordResetApi(request, user.email);
  return waitForEmail(request, user.email, PASSWORD_RESET_SUBJECT, alreadySeen);
}

test.describe('He olvidado mi contraseña', () => {
  test('flujo completo: desde el login (con el correo ya escrito), enlace del buzón, contraseña nueva; la nueva entra y la vieja no', async ({
    page,
    request,
  }) => {
    const user = newTestUser();
    await registerVerifiedUser(request, user);
    const password = newPassword();

    // Desde el login, con el correo escrito: la página siguiente lo trae ya puesto y la URL no lo lleva.
    await page.goto('/login');
    await page.getByLabel('Correo electrónico').fill(user.email);
    await page.getByRole('link', { name: '¿Has olvidado tu contraseña?' }).click();
    await expect(page.getByRole('heading', { level: 1, name: '¿Has olvidado tu contraseña?' })).toBeVisible();
    await expect(page).toHaveURL(`${FRONTEND_URL}/recuperar-contrasena`);
    await expect(page.getByLabel('Correo electrónico')).toHaveValue(user.email);
    await page.getByRole('button', { name: 'Enviar el enlace' }).click();

    // «Revisa tu correo»: no afirma que la cuenta exista; el reenvío espera un minuto.
    await expect(page.getByRole('heading', { level: 1, name: 'Revisa tu correo' })).toBeFocused();
    await expect(page.getByRole('main')).toContainText(`Si hay una cuenta con ${user.email}, en unos minutos`);
    await expect(page.getByRole('button', { name: /^Reenviar en / })).toBeDisabled();

    // El enlace apunta a la web y lleva el token en el fragmento.
    const email = await waitForEmail(request, user.email, PASSWORD_RESET_SUBJECT);
    const link = passwordResetLink(email);
    expect(link.startsWith(`${FRONTEND_URL}/restablecer-contrasena#token=`), link).toBe(true);

    // Abrir el enlace: formulario, y el token fuera de la barra de direcciones.
    await page.goto(link);
    await expect(page.getByRole('heading', { level: 1, name: RESET_FORM_TITLE })).toBeVisible();
    await expect(page).toHaveURL(`${FRONTEND_URL}/restablecer-contrasena`);
    expect(await page.evaluate(() => window.location.hash)).toBe('');

    await submitNewPassword(page, password);
    await expect(page.getByRole('heading', { level: 1, name: 'Contraseña cambiada' })).toBeFocused();
    expect(await sessionCookie(page), 'restablecer no inicia sesión').toBeUndefined();

    // La vieja ya no vale (el mismo 401 de siempre) y llega el aviso del cambio por si no fue el titular.
    expect(await loginAttempt(request, user.email, user.password)).toMatchObject({
      status: 401,
      code: 'INVALID_CREDENTIALS',
    });
    await waitForEmail(request, user.email, PASSWORD_CHANGED_SUBJECT);

    // Y la nueva entra, desde el botón de la propia página.
    await page.getByRole('link', { name: 'Iniciar sesión' }).click();
    await expect(page).toHaveURL(/\/login$/);
    await loginWithForm(page, user.email, password);
  });

  test('el enlace ya usado no vale: «enlace no válido», con la forma de pedir otro', async ({ page, request }) => {
    const user = newTestUser();
    await registerVerifiedUser(request, user);
    const email = await requestResetEmail(request, user);
    const first = await confirmPasswordResetApi(request, passwordResetToken(email), newPassword());
    expect(first.status()).toBe(204);

    // Se vuelve a abrir el mismo enlace (el correo sigue en el buzón): es de un solo uso.
    await page.goto(passwordResetLink(email));
    await submitNewPassword(page, newPassword());

    await expect(page.getByRole('heading', { level: 1, name: INVALID_LINK_TITLE })).toBeFocused();
    await expect(page.getByText(/Si ya cambiaste la contraseña con este enlace/)).toBeVisible();
    await expect(page.getByRole('button', { name: 'Enviar un enlace nuevo' })).toBeEnabled();
  });

  test('un enlace sustituido por otro no vale; desde esa página se pide uno nuevo y ese sí sirve', async ({
    page,
    request,
  }) => {
    const user = newTestUser();
    await registerVerifiedUser(request, user);
    const firstLink = passwordResetLink(await requestResetEmail(request, user));
    // Pedir otro invalida el anterior.
    await requestResetEmail(request, user, 1);

    await page.goto(firstLink);
    await submitNewPassword(page, newPassword());
    await expect(page.getByRole('heading', { level: 1, name: INVALID_LINK_TITLE })).toBeVisible();

    // El enlace solo traía el token: el correo hay que escribirlo.
    await page.getByLabel('Correo electrónico').fill(user.email);
    await page.getByRole('button', { name: 'Enviar un enlace nuevo' }).click();
    await expect(
      page.getByRole('status').filter({ hasText: `Si hay una cuenta con ${user.email}, te llegará un enlace nuevo` }),
    ).toBeVisible();
    await expect(page.getByRole('button', { name: /^Pedir otro en / })).toBeDisabled();

    const third = await waitForEmail(request, user.email, PASSWORD_RESET_SUBJECT, 2);
    const password = newPassword();
    await page.goto(passwordResetLink(third));
    await expect(page.getByRole('heading', { level: 1, name: RESET_FORM_TITLE })).toBeFocused();
    await submitNewPassword(page, password);
    await expect(page.getByRole('heading', { level: 1, name: 'Contraseña cambiada' })).toBeVisible();
    expect((await loginAttempt(request, user.email, password)).status).toBe(204);
  });

  test('una contraseña que rechaza la política no gasta el enlace: se corrige y se reintenta', async ({
    page,
    request,
  }) => {
    const user = newTestUser();
    await registerVerifiedUser(request, user);
    const email = await requestResetEmail(request, user);
    await page.goto(passwordResetLink(email));

    // Con el nombre de usuario dentro: la longitud vale (pasa el cliente) pero el servidor la rechaza.
    const withUsername = `${user.username}-clave`;
    await submitNewPassword(page, withUsername);
    const field = page.getByLabel('Contraseña nueva', { exact: true });
    await expect(field).toHaveAttribute('aria-invalid', 'true');
    await expect(field).toBeFocused();
    await expect(page.getByRole('heading', { level: 1, name: RESET_FORM_TITLE })).toBeVisible();
    expect((await loginAttempt(request, user.email, withUsername)).status).toBe(401);

    // El mismo enlace (el token sigue en memoria) sirve con una contraseña válida.
    const password = newPassword();
    await submitNewPassword(page, password);
    await expect(page.getByRole('heading', { level: 1, name: 'Contraseña cambiada' })).toBeVisible();
    expect((await loginAttempt(request, user.email, password)).status).toBe(204);
  });

  test('una cuenta pendiente de confirmar queda confirmada al restablecer y puede entrar', async ({ page, request }) => {
    const user = newTestUser();
    await registerUser(request, user); // sin confirmar el correo
    expect((await loginAttempt(request, user.email, user.password)).status).toBe(401);

    // Desde la propia pantalla de «He olvidado mi contraseña».
    await page.goto('/recuperar-contrasena');
    await page.getByLabel('Correo electrónico').fill(user.email);
    await page.getByRole('button', { name: 'Enviar el enlace' }).click();
    await expect(page.getByRole('main')).toContainText(
      'Si aún no habías confirmado tu correo, al elegir la contraseña nueva quedará confirmado y podrás entrar.',
    );

    const password = newPassword();
    await page.goto(passwordResetLink(await waitForEmail(request, user.email, PASSWORD_RESET_SUBJECT)));
    await submitNewPassword(page, password);
    await expect(page.getByRole('heading', { level: 1, name: 'Contraseña cambiada' })).toBeVisible();

    await page.getByRole('link', { name: 'Iniciar sesión' }).click();
    await loginWithForm(page, user.email, password);
  });

  test('se cierran las sesiones abiertas antes: la de este navegador (sin «sesión caducada») y la de otro dispositivo', async ({
    page,
    request,
  }) => {
    const user = newTestUser();
    await registerVerifiedUser(request, user);

    // Otro «dispositivo»: un cliente HTTP aparte con su propia sesión (cookies de acceso y de renovación).
    const otherDevice = await playwrightRequest.newContext();
    try {
      const otherLogin = await otherDevice.post(`${BACKEND_URL}/api/auth/login`, {
        data: { email: user.email, password: user.password },
        headers: { 'X-Requested-With': 'StreamBox' },
      });
      expect(otherLogin.status()).toBe(204);

      // Este navegador, con la sesión abierta desde el formulario.
      await page.goto('/login');
      await loginWithForm(page, user.email, user.password);

      const email = await requestResetEmail(request, user);
      await page.goto(passwordResetLink(email));
      await expect(page.getByRole('heading', { level: 1, name: RESET_FORM_TITLE })).toBeVisible();
      const password = newPassword();
      await submitNewPassword(page, password);

      await expect(page.getByRole('heading', { level: 1, name: 'Contraseña cambiada' })).toBeVisible();
      await expect(
        page.getByText('También se ha cerrado la sesión que había abierta en este navegador.'),
      ).toBeVisible();
      expect(await sessionCookie(page)).toBeUndefined();
      await expect(page.getByText('Tu sesión ha caducado. Inicia sesión de nuevo.')).toHaveCount(0);
      await expect(formAlert(page)).toHaveCount(0);

      // El otro dispositivo ya no puede renovar su sesión.
      const refresh = await otherDevice.post(`${BACKEND_URL}/api/auth/refresh`, {
        headers: { 'X-Requested-With': 'StreamBox' },
      });
      expect(refresh.status()).toBe(401);

      // Y en este navegador se entra con la nueva.
      await page.getByRole('link', { name: 'Iniciar sesión' }).click();
      await loginWithForm(page, user.email, password);
    } finally {
      await otherDevice.dispose();
    }
  });

  test('pedir el enlace para un correo sin cuenta responde lo mismo que para uno con cuenta', async ({
    page,
    request,
  }) => {
    const user = newTestUser();
    await registerVerifiedUser(request, user);
    const nobody = newTestUser();

    // La API: mismo estado y mismo cuerpo exista o no la cuenta (no revela quién está dado de alta).
    const ask = (email: string) =>
      request.post(`${BACKEND_URL}/api/auth/password-reset`, {
        data: { email },
        headers: { 'X-Requested-With': 'StreamBox' },
      });
    const [existing, missing] = await Promise.all([ask(user.email), ask(nobody.email)]);
    expect(existing.status()).toBe(202);
    expect(missing.status()).toBe(202);
    expect(await missing.json()).toEqual(await existing.json());

    // La interfaz: la misma pantalla, que no afirma que la cuenta exista.
    await page.goto('/recuperar-contrasena');
    await page.getByLabel('Correo electrónico').fill(nobody.email);
    await page.getByRole('button', { name: 'Enviar el enlace' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Revisa tu correo' })).toBeVisible();
    await expect(page.getByRole('main')).toContainText(`Si hay una cuenta con ${nobody.email}`);
    await expect(page.getByRole('link', { name: 'crear una cuenta' })).toHaveAttribute('href', '/registro');
  });
});
