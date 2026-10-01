/**
 * `test` extendido con lo que necesitan casi todas las pruebas:
 *
 *  - `user`: una cuenta recién registrada (email y usuario únicos) con su JWT.
 *    Cada test tiene la suya, por eso son independientes y paralelizables.
 *  - `signIn`: función que deja la sesión abierta en el navegador sin pasar por
 *    el formulario (siembra el token en `localStorage`, como haría el login real).
 *    Los tests que prueban el formulario de login NO la usan.
 *  - `failOnPageErrors` (automático): una excepción JavaScript no capturada en la
 *    página (un fallo de React, un `undefined`...) hace fallar el test aunque la
 *    UI "parezca" funcionar.
 */
import { test as base, expect } from '@playwright/test';
import type { Locator, Page } from '@playwright/test';
import { createUserWithToken } from './api';
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
      // `/login` no necesita sesión ni hace peticiones autenticadas: sirve para tener
      // un origen donde escribir en `localStorage` antes de cargar la aplicación "ya logueada".
      await page.goto('/login');
      await page.evaluate((jwt) => localStorage.setItem('token', jwt), token);
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
