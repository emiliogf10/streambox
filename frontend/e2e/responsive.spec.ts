/**
 * E2E responsive: en móvil, tablet y escritorio ninguna pantalla debe tener
 * scroll horizontal de PÁGINA y los elementos clave deben verse enteros.
 *
 * Protege contra el fallo más común al tocar estilos: una tarjeta, una fila o la
 * barra de navegación que se sale por la derecha en 375 px. En la barra se
 * comprueba además que ningún elemento se solape con otro (a 768 px el cálculo
 * de anchos es muy justo).
 *
 * Nota: las filas de películas SÍ tienen scroll horizontal INTERNO (carrusel);
 * lo que no puede desbordarse es la página (`documentElement`).
 */
import type { Locator, Page } from '@playwright/test';
import { addFavoritesByTitle } from './support/api';
import { HERO_TITLE } from './support/catalog';
import { expect, test } from './support/fixtures';
import { expectCovers, expectWholePoster } from './support/images';

const VIEWPORTS = [
  { name: 'móvil 375x812', width: 375, height: 812 },
  { name: 'tablet 768x1024', width: 768, height: 1024 },
  { name: 'escritorio 1280x800', width: 1280, height: 800 },
] as const;

/** Comprueba que la página no se desplaza horizontalmente. */
async function expectNoHorizontalScroll(page: Page): Promise<void> {
  const { scrollWidth, clientWidth } = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }));
  expect(scrollWidth, `scrollWidth (${scrollWidth}) debe ser ≤ clientWidth (${clientWidth})`).toBeLessThanOrEqual(clientWidth);
}

/** Comprueba que el elemento es visible y cabe entero, en horizontal, dentro de la ventana. */
async function expectFullyInsideViewportWidth(locator: Locator, page: Page, label: string): Promise<void> {
  await expect(locator, `${label} debe ser visible`).toBeVisible();
  const box = await locator.boundingBox();
  const width = page.viewportSize()?.width ?? 0;
  expect(box, `${label} sin caja`).not.toBeNull();
  expect(box!.x, `${label} se sale por la izquierda`).toBeGreaterThanOrEqual(-0.5);
  expect(box!.x + box!.width, `${label} se sale por la derecha`).toBeLessThanOrEqual(width + 0.5);
}

for (const viewport of VIEWPORTS) {
  test.describe(`Responsive ${viewport.name}`, () => {
    test.use({ viewport: { width: viewport.width, height: viewport.height } });

    test('/login', async ({ page }) => {
      await page.goto('/login');
      await expectFullyInsideViewportWidth(page.getByRole('heading', { level: 1, name: 'Bienvenido de nuevo' }), page, 'título');
      await expectFullyInsideViewportWidth(page.getByLabel('Correo electrónico'), page, 'campo de correo');
      await expectFullyInsideViewportWidth(page.getByLabel('Contraseña'), page, 'campo de contraseña');
      await expectFullyInsideViewportWidth(page.getByRole('button', { name: 'Iniciar sesión' }), page, 'botón de entrar');
      await expectFullyInsideViewportWidth(page.getByRole('link', { name: 'Regístrate' }), page, 'enlace a registro');
      await expectNoHorizontalScroll(page);
    });

    test('/registro (también con todos los errores a la vista)', async ({ page }) => {
      await page.goto('/registro');
      await expectFullyInsideViewportWidth(page.getByLabel('Nombre de usuario'), page, 'campo de usuario');
      await expectFullyInsideViewportWidth(page.getByRole('button', { name: 'Crear cuenta' }), page, 'botón de crear cuenta');
      await expectNoHorizontalScroll(page);

      // Con los mensajes de error los textos son más largos: tampoco debe desbordar.
      await page.getByRole('button', { name: 'Crear cuenta' }).click();
      await expect(page.getByText('La contraseña debe tener entre 8 y 100 caracteres.')).toBeVisible();
      await expectNoHorizontalScroll(page);
    });

    test('/ (portada): barra, banner y botones', async ({ page, user, signIn }) => {
      await signIn(user);
      await page.goto('/');
      await expect(page.getByRole('region', { name: 'Novedades' })).toBeVisible();

      const nav = page.getByRole('navigation', { name: 'Principal' });
      const items: [Locator, string][] = [
        [page.getByRole('link', { name: 'streambox' }), 'logo'],
        [nav.getByRole('link', { name: 'Inicio' }), 'Inicio'],
        [nav.getByText('Películas'), 'Películas'],
        [nav.getByText('Series'), 'Series'],
        [nav.getByRole('link', { name: 'Mi lista' }), 'Mi lista'],
        [page.getByRole('combobox', { name: 'Buscar películas por título' }), 'buscador'],
        [page.getByRole('button', { name: 'Menú de usuario' }), 'menú de usuario'],
      ];

      const boxes: { label: string; x: number; y: number; width: number; height: number }[] = [];
      for (const [locator, label] of items) {
        await expectFullyInsideViewportWidth(locator, page, label);
        const box = await locator.boundingBox();
        boxes.push({ label, ...box! });
      }
      // Ningún elemento de la barra pisa a otro (a 768 px el margen es mínimo).
      for (let i = 0; i < boxes.length; i += 1) {
        for (let j = i + 1; j < boxes.length; j += 1) {
          const a = boxes[i];
          const b = boxes[j];
          const overlap = a.x < b.x + b.width - 1 && b.x < a.x + a.width - 1 && a.y < b.y + b.height - 1 && b.y < a.y + a.height - 1;
          expect(overlap, `«${a.label}» y «${b.label}» se solapan en la barra`).toBe(false);
        }
      }

      const banner = page.getByRole('region', { name: HERO_TITLE });
      await expectFullyInsideViewportWidth(banner, page, 'banner');
      await expectFullyInsideViewportWidth(banner.getByRole('heading', { level: 2, name: HERO_TITLE }), page, 'título del banner');
      await expectFullyInsideViewportWidth(banner.getByRole('link', { name: /^Ver ahora/ }), page, '«Ver ahora»');
      await expectFullyInsideViewportWidth(banner.getByRole('button', { name: /^Mi lista/ }), page, '«Mi lista» del banner');
      await expectFullyInsideViewportWidth(banner.getByRole('button', { name: /^Más información/ }), page, '«Más información»');

      // El fondo cubre el banner y el póster nítido (tarjeta 2:3) se ve ENTERO en todos los anchos.
      // Antes, en móvil, el póster era el fondo nítido y el degradado del texto tapaba su mitad
      // inferior; ahora va pequeño junto al título (como en el modal) y debe verse sin recortes.
      const images = banner.locator('img');
      await expectCovers(images.first(), banner, 'fondo del banner');
      await expectWholePoster(images.last(), banner, 'póster del banner');
      // El póster va JUNTO al título (misma fila en móvil, columna contigua en escritorio): el hueco
      // horizontal entre ambos no supera los 64 px. Regresión que evita: a 1280 px el póster estaba
      // en el borde derecho, a ~430 px del texto.
      const posterBox = await images.last().boundingBox();
      const titleBox = await banner.getByRole('heading', { level: 2, name: HERO_TITLE }).boundingBox();
      expect(titleBox!.x - (posterBox!.x + posterBox!.width), 'hueco entre póster y título').toBeLessThanOrEqual(64);
      expect(titleBox!.x - (posterBox!.x + posterBox!.width), 'el título no pisa el póster').toBeGreaterThanOrEqual(0);
      await expectNoHorizontalScroll(page);

      // Con la página completa (segunda página incluida) tampoco hay desborde.
      await page.getByRole('button', { name: 'Cargar más películas' }).scrollIntoViewIfNeeded();
      await page.getByRole('button', { name: 'Cargar más películas' }).click();
      await expect(page.getByText(/Mostrando 25 de 25/)).toBeVisible();
      await expectNoHorizontalScroll(page);
    });

    test('/favorites con películas y vacía', async ({ page, request, user, signIn }) => {
      await signIn(user);
      await page.goto('/favorites');
      await expect(page.getByRole('heading', { level: 2, name: 'Tu lista está vacía' })).toBeVisible();
      await expectFullyInsideViewportWidth(page.getByRole('link', { name: 'Explorar catálogo' }), page, 'invitación a explorar');
      await expectNoHorizontalScroll(page);

      await addFavoritesByTitle(request, user.token, [
        'Dredd',
        'Mad Max',
        'Interstellar',
        'El increíble viaje de la nave perdida más allá de las estrellas olvidadas',
        'La Casa del Lago',
      ]);
      await page.reload();
      await expect(page.getByRole('main').getByRole('button', { name: /\d{4} ·/ })).toHaveCount(5);
      await expectFullyInsideViewportWidth(page.getByRole('button', { name: 'Vaciar lista' }), page, '«Vaciar lista»');
      await expectNoHorizontalScroll(page);

      // Toda tarjeta cabe entera en horizontal (la rejilla no debe sacarlas de la pantalla).
      const cards = page.getByRole('main').getByRole('button', { name: /\d{4} ·/ });
      for (let index = 0; index < 5; index += 1) {
        await expectFullyInsideViewportWidth(cards.nth(index), page, `tarjeta ${index + 1}`);
      }
    });

    test('modal de detalle y diálogo de confirmación caben en pantalla', async ({ page, request, user, signIn }) => {
      await addFavoritesByTitle(request, user.token, ['Dredd']);
      await signIn(user);
      await page.goto('/favorites');

      await page.getByRole('button', { name: 'Vaciar lista' }).click();
      const confirm = page.getByRole('alertdialog');
      await expectFullyInsideViewportWidth(confirm, page, 'diálogo de confirmación');
      await expectFullyInsideViewportWidth(confirm.getByRole('button', { name: 'Sí, vaciar lista' }), page, 'botón de confirmar');
      await confirm.getByRole('button', { name: 'Cancelar' }).click();

      await page.getByRole('button', { name: /^Dredd \d{4}/ }).click();
      const dialog = page.getByRole('dialog', { name: 'Dredd' });
      await expectFullyInsideViewportWidth(dialog, page, 'modal de detalle');
      await expectFullyInsideViewportWidth(dialog.getByRole('button', { name: /^Cerrar detalles/ }), page, 'botón de cerrar');
      // El póster de la cabecera se ve entero también en 375 px (más pequeño, a la izquierda del título).
      await expectWholePoster(dialog.locator('img').last(), dialog, 'póster del modal');
      await expectNoHorizontalScroll(page);
    });
  });
}
