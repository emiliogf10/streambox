/**
 * `test` extendido con lo que necesitan casi todas las pruebas:
 *
 *  - `user`: una cuenta recién registrada (email y usuario únicos) con su JWT
 *    (el valor de la cookie de sesión que fijó el login).
 *    Cada test tiene la suya, por eso son independientes y paralelizables.
 *  - `signIn`: función que deja la sesión abierta en el navegador sin pasar por
 *    el formulario (siembra en el contexto del navegador la cookie `streambox_token`,
 *    HttpOnly como la real, que es lo que haría el login).
 *    Los tests que prueban el formulario de login NO la usan.
 *  - `failOnPageErrors` (automático): una excepción JavaScript no capturada en la
 *    página (un fallo de React, un `undefined`...) hace fallar el test aunque la
 *    UI "parezca" funcionar.
 */
import { test as base, expect } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import { SESSION_COOKIE, createUserWithToken } from './api';
import type { TestUser } from './api';

interface Fixtures {
  user: TestUser & { token: string };
  signIn: (user: { token: string }) => Promise<void>;
  failOnPageErrors: void;
}

export const test = base.extend<Fixtures>({
  user: async ({ request }, provide) => {
    await provide(await createUserWithToken(request));
  },

  signIn: async ({ page }, provide) => {
    await provide(async ({ token }) => {
      // La sesión vive en una cookie HttpOnly, inaccesible desde la página: se siembra
      // desde el contexto del navegador con los mismos atributos que fija el backend.
      // Va sin `Secure` porque el backend E2E corre en http (ver playwright.config.ts).
      await page.context().addCookies([
        { name: SESSION_COOKIE, value: token, domain: 'localhost', path: '/api', httpOnly: true, sameSite: 'Strict' },
      ]);
    });
  },

  failOnPageErrors: [
    async ({ page }, provide) => {
      const errors: string[] = [];
      page.on('pageerror', (error) => errors.push(error.message));
      await provide();
      expect(errors, 'Excepciones JavaScript no capturadas en la página').toEqual([]);
    },
    { auto: true },
  ],
});

export { expect };

/**
 * Cookie de sesión del contexto del navegador, o `undefined` si no hay sesión.
 * Se lee con la API de Playwright porque, al ser HttpOnly, la página no puede
 * verla (justo lo que se quiere: el JavaScript de la app no tiene el token).
 */
export async function sessionCookie(page: Page) {
  const cookies = await page.context().cookies();
  return cookies.find((cookie) => cookie.name === SESSION_COOKIE);
}

/** Escapa un texto para usarlo literalmente dentro de una expresión regular. */
export function escapeRegExp(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Tarjeta (botón) de una película dentro de `scope`. Su nombre accesible es
 * «Título 2014 · 2 h 1 min»; se ancla al inicio y exige el año para que
 * «Blade Runner» no case con «Blade Runner 2049».
 */
export function movieCard(scope: Page | Locator, title: string): Locator {
  return scope.getByRole('button', { name: new RegExp(`^${escapeRegExp(title)} \\d{4} ·`) });
}

/**
 * Aviso de error de un formulario (`role="alert"` con texto). Hay que filtrar por
 * texto porque la zona de avisos (`ToastViewport`) mantiene SIEMPRE una región
 * `role="alert"` vacía en el DOM (una región viva debe existir antes de recibir
 * contenido para que los lectores de pantalla lo anuncien).
 */
export function formAlert(page: Page): Locator {
  return page.getByRole('alert').filter({ hasText: /\S/ });
}

/**
 * Espera a que el formulario de película del panel esté COMPLETO, con las
 * casillas de géneros: la señal visible que esperaría una persona antes de
 * pulsar «Crear película».
 *
 * Por qué hace falta: los géneros llegan en una petición aparte
 * (`GET /api/genres`) y, al cambiar la línea «Cargando géneros...» por las
 * casillas, todo lo que hay debajo (la vista previa y los botones) baja de golpe
 * (~130 px a 375 px). Si el clic coincide con ese salto, el botón ya no está bajo
 * el puntero y el formulario no se envía: así fallaba a veces el test responsive
 * a 375 px. Las esperas automáticas de Playwright no lo evitan: comprueban que el
 * botón está quieto ANTES de empezar el clic y miran qué hay bajo el puntero
 * solo en el PRIMER evento (`mousedown`). Si la página se mueve antes del
 * `mouseup`, el `click` va a parar a otro elemento.
 */
export async function waitForMovieForm(page: Page): Promise<void> {
  await expect(page.getByRole('group', { name: 'Géneros' }).getByRole('checkbox').first()).toBeVisible();
}
