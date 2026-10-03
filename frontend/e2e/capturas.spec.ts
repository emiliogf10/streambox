/**
 * Capturas de pantalla para la REVISIÓN VISUAL manual (no son un test de regresión).
 *
 * Solo se ejecuta si se define `E2E_SCREENSHOTS_DIR` con una carpeta de destino
 * (conviene que esté fuera del repositorio). Sin esa variable se omite, así que
 * no forma parte de la suite habitual ni deja archivos en el proyecto:
 *
 *   E2E_SCREENSHOTS_DIR=C:\temp\screens npx playwright test capturas
 *
 * Genera las pantallas principales a 375, 768 y 1280 px para que una persona las
 * mire: lo que un test automático no ve (legibilidad sobre las portadas,
 * alineación, huecos, espaciados).
 */
import path from 'node:path';
import type { Page } from '@playwright/test';
import { addFavoritesByTitle } from './support/api';
import { HERO_TITLE } from './support/catalog';
import { expect, movieCard, test } from './support/fixtures';

const OUT_DIR = process.env.E2E_SCREENSHOTS_DIR;

const VIEWPORTS = [
  { width: 375, height: 812 },
  { width: 768, height: 1024 },
  { width: 1280, height: 800 },
] as const;

test.skip(!OUT_DIR, 'Define E2E_SCREENSHOTS_DIR para generar las capturas de revisión visual');

/**
 * Guarda una captura como `<carpeta>/<ancho>-<nombre>.png`.
 *
 * `animations: 'disabled'` adelanta al final las transiciones en curso: los
 * diálogos y los avisos entran con un fundido de ~200 ms y, sin esto, la foto
 * podía salir a mitad (el diálogo medio transparente, con la página de detrás
 * asomando). Para revisar el diseño interesa el estado ya asentado.
 */
async function shot(page: Page, name: string, fullPage = false): Promise<void> {
  const width = page.viewportSize()?.width ?? 0;
  await page.screenshot({ path: path.join(OUT_DIR ?? '', `${width}-${name}.png`), fullPage, animations: 'disabled' });
}

for (const viewport of VIEWPORTS) {
  test.describe(`Capturas ${viewport.width}px`, () => {
    test.use({ viewport });

    test('pantallas públicas', async ({ page }) => {
      await page.goto('/login');
      await expect(page.getByRole('button', { name: 'Iniciar sesión' })).toBeVisible();
      await shot(page, '01-login');

      await page.goto('/registro');
      await page.getByRole('button', { name: 'Crear cuenta' }).click();
      await expect(page.getByText('Introduce tu correo electrónico.')).toBeVisible();
      await shot(page, '02-registro-errores', true);
    });

    test('portada, detalle, toast y menú', async ({ page, user, signIn }) => {
      await signIn(user);
      await page.goto('/');
      const banner = page.getByRole('region', { name: HERO_TITLE });
      await expect(banner).toBeVisible();
      await expect.poll(() => banner.locator('img').first().evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0)).toBe(true);
      await expect(page.getByRole('region', { name: 'Novedades' })).toBeVisible();
      // Que terminen de descargarse las portadas visibles: si no, alguna sale como hueco "cargando".
      await page.waitForLoadState('networkidle');
      await shot(page, '03-portada-arriba');
      // La captura de página completa desplaza la vista: las portadas diferidas que entran entonces
      // pueden no haber llegado y salir como hueco liso "cargando" (no es el respaldo).
      await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
      await page.waitForLoadState('networkidle');
      await page.evaluate(() => window.scrollTo(0, 0));
      await shot(page, '04-portada-completa', true);

      await page.getByRole('button', { name: 'Cargar más películas' }).click();
      await expect(page.getByText(/Mostrando 25 de 25/)).toBeVisible();
      await shot(page, '05-portada-tras-cargar-mas', true);

      await page.getByRole('button', { name: 'Menú de usuario' }).click();
      await expect(page.getByRole('button', { name: 'Cerrar sesión' })).toBeVisible();
      await shot(page, '06-menu-usuario');
      await page.keyboard.press('Escape');

      await page.evaluate(() => window.scrollTo(0, 0));
      await movieCard(page.getByRole('region', { name: 'Novedades' }), 'Interstellar').click();
      const dialog = page.getByRole('dialog', { name: 'Interstellar' });
      await expect(dialog).toBeVisible();
      await expect.poll(() => dialog.locator('img').last().evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0)).toBe(true);
      await shot(page, '07-modal-detalle');

      await dialog.getByRole('button', { name: /^Mi lista/ }).click();
      await expect(dialog.getByText('«Interstellar» se ha añadido a tu lista.')).toBeVisible();
      await shot(page, '08-toast-en-modal');
    });

    test('banner sin portada (respaldo)', async ({ page, user, signIn }) => {
      // La portada del banner da 404: se ve el respaldo de MoviePoster en el fondo y en la tarjeta.
      await page.route('**/covers/dune-parte-dos.webp', (route) => route.fulfill({ status: 404, body: '' }));
      await signIn(user);
      await page.goto('/');
      const banner = page.getByRole('region', { name: HERO_TITLE });
      await expect(banner).toBeVisible();
      await expect(banner.locator('img')).toHaveCount(0);
      await page.waitForLoadState('networkidle');
      await shot(page, '12-banner-sin-portada');
    });

    test('Mi lista: vacía, con películas y confirmación', async ({ page, request, user, signIn }) => {
      await signIn(user);
      await page.goto('/favorites');
      await expect(page.getByRole('heading', { level: 2, name: 'Tu lista está vacía' })).toBeVisible();
      await shot(page, '09-mi-lista-vacia');

      await addFavoritesByTitle(request, user.token, [
        'Dredd',
        'Mad Max',
        'Interstellar',
        'El increíble viaje de la nave perdida más allá de las estrellas olvidadas',
        'La Casa del Lago',
        'Ex Machina',
      ]);
      await page.reload();
      await expect(page.getByRole('main').getByRole('button', { name: /\d{4} ·/ })).toHaveCount(6);
      await page.waitForLoadState('networkidle');
      await shot(page, '10-mi-lista-con-peliculas');

      await page.getByRole('button', { name: 'Vaciar lista' }).click();
      await expect(page.getByRole('alertdialog')).toBeVisible();
      await shot(page, '11-confirmacion-vaciar');
    });
  });
}
