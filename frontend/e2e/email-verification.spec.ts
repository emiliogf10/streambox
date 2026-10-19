/**
 * E2E de la verificación del correo: registro → «Revisa tu correo» → enlace del buzón →
 * `/verificar-email#token=…` → «Confirmar mi correo» → login; el enlace ya usado o sustituido por otro;
 * y el login de una cuenta pendiente, que recibe el mismo «credenciales incorrectas» que una contraseña
 * equivocada (para no revelar qué correos tienen cuenta) con la pista de confirmar el correo y reenviarlo.
 *
 * El correo no sale de verdad: con el perfil `e2e` el backend lo guarda en una bandeja en memoria que
 * estos tests leen como administrador (`waitForEmail`, ver `support/api.ts`). Los enlaces apuntan al Vite
 * de esta suite (`STREAMBOX_PUBLIC_URL` en `playwright.config.ts`), así que se abren tal cual llegan.
 *
 * Protege lo que los tests unitarios no pueden ver: que el enlace que construye el backend lleva a la
 * página que lo entiende, que abrirlo NO confirma nada (los antivirus del correo abren los enlaces solos y
 * gastarían el token), que el token sale de la barra de direcciones y que, tras confirmar, la cuenta entra
 * de verdad. No tocan el catálogo: van en el proyecto principal.
 */
import type { APIRequestContext, Page } from '@playwright/test';
import {
  VERIFICATION_SUBJECT,
  confirmEmailApi,
  newTestUser,
  registerUser,
  verificationLink,
  verificationToken,
  waitForEmail,
} from './support/api';
import type { TestUser } from './support/api';
import { BACKEND_URL, FRONTEND_URL } from './support/config';
import { HERO_TITLE } from './support/catalog';
import { expect, formAlert, sessionCookie, test } from './support/fixtures';

/** Título de `/verificar-email` cuando el token no vale (inválido, caducado o ya usado: el mismo caso). */
const INVALID_LINK_TITLE = 'El enlace no es válido o ha caducado';

/**
 * Pista del login «¿aún no has confirmado tu correo?» (`UnconfirmedEmailHint` de `LoginPage.tsx`). Sale en
 * TODO 401 de credenciales: el backend responde a una cuenta pendiente con la contraseña correcta el mismo
 * 401 `INVALID_CREDENTIALS` que a una contraseña incorrecta.
 */
const UNCONFIRMED_HINT_TITLE = '¿Te acabas de registrar o aún no has confirmado tu correo?';

/** Botón de la pista del login para pedir el correo de confirmación. */
const RESEND_FROM_LOGIN = 'Reenviar el correo de confirmación';

/** Aviso tras pedir un enlace nuevo (`RESEND_DONE_MESSAGE` de `lib/emailVerification.ts`). */
function resendDoneMessage(email: string): string {
  return (
    `Si hay una cuenta pendiente de confirmar con ${email}, te llegará un enlace nuevo en unos minutos, ` +
    'salvo que ya hayas pedido varios hace poco. Usa siempre el del correo más reciente.'
  );
}

/** Intenta iniciar sesión por API y devuelve el estado, el `code` de error y el mensaje (si los hay). */
async function loginAttempt(
  request: APIRequestContext,
  user: TestUser,
  password = user.password,
): Promise<{ status: number; code: string | undefined; message: string | undefined }> {
  const response = await request.post(`${BACKEND_URL}/api/auth/login`, {
    data: { email: user.email, password },
    headers: { 'X-Requested-With': 'StreamBox' },
  });
  const body = (await response.json().catch(() => ({}))) as { code?: string; message?: string };
  return { status: response.status(), code: body.code, message: body.message };
}

/**
 * Empieza a anotar las peticiones de la página a `POST /api/auth/email-verification` (confirmar). Sirve
 * para demostrar que abrir el enlace no confirma nada por sí solo.
 */
function recordConfirmations(page: Page): () => number {
  let count = 0;
  page.on('request', (request) => {
    if (request.method() === 'POST' && request.url().endsWith('/api/auth/email-verification')) count += 1;
  });
  return () => count;
}

/** Inicia sesión con el formulario y espera a la portada. */
async function loginWithForm(page: Page, user: TestUser): Promise<void> {
  await page.getByLabel('Correo electrónico').fill(user.email);
  await page.getByLabel('Contraseña').fill(user.password);
  await page.getByRole('button', { name: 'Iniciar sesión' }).click();
  await expect(page.getByRole('heading', { level: 2, name: HERO_TITLE })).toBeVisible();
  expect(await sessionCookie(page)).toBeDefined();
}

test.describe('Verificación del correo', () => {
  test('flujo completo: registro, enlace del buzón, la página NO confirma al abrirse, el botón sí, y después se entra', async ({
    page,
    request,
  }) => {
    const user = newTestUser();
    await page.goto('/registro');
    await page.getByLabel('Nombre de usuario').fill(user.username);
    await page.getByLabel('Correo electrónico').fill(user.email);
    await page.getByLabel('Contraseña').fill(user.password);
    await page.getByRole('button', { name: 'Crear cuenta' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Revisa tu correo' })).toBeVisible();

    // El enlace del correo apunta a la web (no al backend) y lleva el token en el fragmento.
    const email = await waitForEmail(request, user.email, VERIFICATION_SUBJECT);
    const link = verificationLink(email);
    expect(link.startsWith(`${FRONTEND_URL}/verificar-email#token=`), link).toBe(true);

    // Abrir el enlace: la página pide confirmar, quita el token de la barra de direcciones y NO confirma sola.
    const confirmations = recordConfirmations(page);
    await page.goto(link);
    await expect(page.getByRole('heading', { level: 1, name: 'Confirma tu correo' })).toBeVisible();
    await expect(page).toHaveURL(`${FRONTEND_URL}/verificar-email`);
    expect(await page.evaluate(() => window.location.hash)).toBe('');
    await page.waitForLoadState('networkidle');
    expect(confirmations(), 'abrir el enlace no debe llamar a la confirmación').toBe(0);
    // La prueba en el servidor: la cuenta sigue pendiente. Con la contraseña CORRECTA no entra (401 y no
    // 204) y la respuesta es la misma que con una incorrecta: el login no revela que la cuenta existe.
    const pending = await loginAttempt(request, user);
    expect(pending).toMatchObject({ status: 401, code: 'INVALID_CREDENTIALS' });
    const wrongPassword = await loginAttempt(request, user, 'contrasena-equivocada');
    expect(wrongPassword).toEqual(pending);

    // Pulsar el botón sí confirma. El foco va al título nuevo, que anuncia el resultado.
    await page.getByRole('button', { name: 'Confirmar mi correo' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Correo confirmado' })).toBeFocused();
    expect(confirmations()).toBe(1);
    await expect(page.getByText('Ya puedes iniciar sesión con tu correo y tu contraseña.')).toBeVisible();

    // Y la cuenta ya entra, desde el botón de la propia página.
    await page.getByRole('link', { name: 'Iniciar sesión' }).click();
    await expect(page).toHaveURL(/\/login$/);
    await loginWithForm(page, user);
  });

  test('el enlace ya usado dice que no vale y recuerda que, si ya se confirmó, basta con iniciar sesión', async ({
    page,
    request,
  }) => {
    const user = newTestUser();
    await registerUser(request, user);
    const email = await waitForEmail(request, user.email, VERIFICATION_SUBJECT);
    const link = verificationLink(email);
    await confirmEmailApi(request, verificationToken(email)); // se usa una vez...

    // ...y se vuelve a abrir (el correo sigue en el buzón): el token es de un solo uso.
    await page.goto(link);
    await page.getByRole('button', { name: 'Confirmar mi correo' }).click();

    await expect(page.getByRole('heading', { level: 1, name: INVALID_LINK_TITLE })).toBeFocused();
    await expect(page).toHaveURL(`${FRONTEND_URL}/verificar-email`);
    await expect(page.getByText(/Si ya habías confirmado tu correo, no tienes que hacer nada más/)).toBeVisible();
    await page.getByRole('link', { name: 'inicia sesión' }).click();
    await expect(page).toHaveURL(/\/login$/);
    await loginWithForm(page, user);
  });

  test('un enlace sustituido por otro no vale; desde esa misma página se pide uno nuevo y ese sí confirma', async ({
    page,
    request,
  }) => {
    const user = newTestUser();
    await registerUser(request, user);
    const firstLink = verificationLink(await waitForEmail(request, user.email, VERIFICATION_SUBJECT));

    // Un reenvío (p. ej. desde otra pestaña) invalida el primer enlace.
    const resend = await request.post(`${BACKEND_URL}/api/auth/email-verification/resend`, {
      data: { email: user.email },
      headers: { 'X-Requested-With': 'StreamBox' },
    });
    expect(resend.status()).toBe(202);
    await waitForEmail(request, user.email, VERIFICATION_SUBJECT, 1);

    await page.goto(firstLink);
    await page.getByRole('button', { name: 'Confirmar mi correo' }).click();
    await expect(page.getByRole('heading', { level: 1, name: INVALID_LINK_TITLE })).toBeVisible();

    // El enlace solo traía el token: el correo hay que escribirlo. Primero, uno mal escrito no se envía.
    const emailField = page.getByLabel('Correo electrónico');
    await emailField.fill('esto-no-es-un-correo');
    await page.getByRole('button', { name: 'Enviar un enlace nuevo' }).click();
    await expect(emailField).toHaveAttribute('aria-invalid', 'true');
    await expect(emailField).toBeFocused();

    await emailField.fill(user.email);
    await page.getByRole('button', { name: 'Enviar un enlace nuevo' }).click();
    await expect(page.getByRole('status').filter({ hasText: resendDoneMessage(user.email) })).toBeVisible();
    // Recién pedido, el botón espera (otro reenvío invalidaría el que va de camino).
    await expect(page.getByRole('button', { name: /^Pedir otro en / })).toBeDisabled();

    const third = await waitForEmail(request, user.email, VERIFICATION_SUBJECT, 2);
    await page.goto(verificationLink(third));
    await page.getByRole('button', { name: 'Confirmar mi correo' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Correo confirmado' })).toBeVisible();
    expect((await loginAttempt(request, user)).status).toBe(204);
  });

  test('login de una cuenta pendiente: el mismo «credenciales incorrectas» con la pista de confirmar, «Reenviar el correo de confirmación» y el enlace anterior deja de valer', async ({
    page,
    request,
  }) => {
    const user = newTestUser();
    await registerUser(request, user);
    const firstToken = verificationToken(await waitForEmail(request, user.email, VERIFICATION_SUBJECT));

    await page.goto('/login');
    await page.getByLabel('Correo electrónico').fill(user.email);
    await page.getByLabel('Contraseña').fill(user.password);
    const loginResponse = page.waitForResponse(
      (response) => response.url().endsWith('/api/auth/login') && response.request().method() === 'POST',
    );
    await page.getByRole('button', { name: 'Iniciar sesión' }).click();
    // Con la contraseña correcta, el mismo 401 que una incorrecta (sin revelar que la cuenta existe).
    const response = await loginResponse;
    expect(response.status()).toBe(401);
    expect(((await response.json()) as { code?: string }).code).toBe('INVALID_CREDENTIALS');

    // El error de siempre (con los intentos que quedan) y, debajo, la pista con el correo del intento.
    await expect(formAlert(page)).toContainText('Correo o contraseña incorrectos.');
    const hint = page.getByRole('status').filter({ hasText: UNCONFIRMED_HINT_TITLE });
    await expect(hint).toBeVisible();
    await expect(hint).toContainText('Hasta que lo confirmes no podrás entrar, aunque la contraseña sea correcta.');
    await expect(hint).toContainText(`pide uno nuevo para ${user.email}.`);
    await expect(page).toHaveURL(/\/login$/);
    expect(await sessionCookie(page)).toBeUndefined();

    await page.getByRole('button', { name: RESEND_FROM_LOGIN }).click();
    await expect(page.getByRole('status').filter({ hasText: resendDoneMessage(user.email) })).toBeVisible();
    await expect(page.getByRole('button', { name: /^Reenviar en / })).toBeDisabled();

    // Llega un correo nuevo con OTRO token, y el anterior ya no sirve.
    const second = await waitForEmail(request, user.email, VERIFICATION_SUBJECT, 1);
    const secondToken = verificationToken(second);
    expect(secondToken).not.toBe(firstToken);
    const stale = await request.post(`${BACKEND_URL}/api/auth/email-verification`, {
      data: { token: firstToken },
      headers: { 'X-Requested-With': 'StreamBox' },
    });
    expect(stale.status()).toBe(400);
    expect(((await stale.json()) as { code?: string }).code).toBe('INVALID_VERIFICATION_TOKEN');

    // El nuevo confirma y, de vuelta en el login, la cuenta entra.
    await page.goto(verificationLink(second));
    await page.getByRole('button', { name: 'Confirmar mi correo' }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Correo confirmado' })).toBeVisible();
    await page.getByRole('link', { name: 'Iniciar sesión' }).click();
    await loginWithForm(page, user);
  });
});
