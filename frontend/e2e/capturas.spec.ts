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
import { addFavoritesByTitle, findSeriesIdAsAdmin, loginAdmin } from './support/api';
import { HERO_TITLE, SERIES_HERO } from './support/catalog';
import { expect, movieCard, test, waitForMovieForm } from './support/fixtures';

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

    test('Películas: sin filtros, con filtros, error del año y sin resultados', async ({ page, user, signIn }) => {
      await signIn(user);
      await page.goto('/peliculas');
      const banner = page.getByRole('region', { name: HERO_TITLE });
      await expect(banner).toBeVisible();
      await expect.poll(() => banner.locator('img').first().evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0)).toBe(true);
      await page.waitForLoadState('networkidle');
      await shot(page, '24-peliculas-arriba');
      // Igual que en la portada: bajar antes de la captura completa para que lleguen las portadas diferidas.
      await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight));
      await page.waitForLoadState('networkidle');
      await page.evaluate(() => window.scrollTo(0, 0));
      await shot(page, '25-peliculas-completa', true);

      // Con filtros: Acción por título (11 resultados, cuadrícula con varias filas y «Quitar filtros»).
      const form = page.getByRole('form', { name: 'Filtrar películas' });
      await form.getByRole('combobox', { name: 'Género' }).selectOption({ label: 'Acción' });
      await form.getByRole('combobox', { name: 'Ordenar por' }).selectOption({ label: 'Título A–Z' });
      await expect(page).toHaveURL(/\?genero=\d+&orden=titulo-asc$/);
      const results = page.getByRole('region', { name: 'Resultados' });
      await expect(results.getByRole('status').first()).toHaveText('11 películas');
      await page.waitForLoadState('networkidle');
      await shot(page, '26-peliculas-con-filtros', true);

      // El error del campo «Año» (el más estrecho de la barra).
      const year = form.getByRole('textbox', { name: 'Año' });
      await year.fill('99');
      await year.press('Tab');
      await expect(page.getByText('Escribe un año entre 1888 y 2100.')).toBeVisible();
      await shot(page, '27-peliculas-error-anio');

      // Sin resultados: Acción de 1982 («Blade Runner» es solo Ciencia ficción).
      await year.fill('1982');
      await expect(results.getByRole('heading', { name: 'No hay películas con estos filtros' })).toBeVisible();
      await shot(page, '28-peliculas-sin-resultados');
    });

    test('Series: listado y página de una serie', async ({ page, user, signIn }) => {
      await signIn(user);
      await page.goto('/series');
      const banner = page.getByRole('region', { name: SERIES_HERO.title });
      await expect(banner).toBeVisible();
      await expect.poll(() => banner.locator('img').first().evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0)).toBe(true);
      await page.waitForLoadState('networkidle');
      await shot(page, '29-series-arriba');
      await shot(page, '30-series-completa', true);

      await banner.getByRole('link', { name: /^Ver episodios/ }).click();
      await expect(page.getByRole('heading', { level: 1, name: SERIES_HERO.title })).toBeVisible();
      await expect(page.getByRole('list', { name: 'Episodios de la temporada 1' })).toBeVisible();
      await page.waitForLoadState('networkidle');
      await shot(page, '31-serie-detalle', true);

      await page.getByRole('navigation', { name: 'Temporadas' }).getByRole('link', { name: 'Temporada 2' }).click();
      await expect(page.getByRole('list', { name: 'Episodios de la temporada 2' })).toBeVisible();
      await shot(page, '32-serie-temporada-2');
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

    test('panel de administración (solo lectura)', async ({ page, request, signIn }) => {
      await signIn({ token: await loginAdmin(request) });
      await page.goto('/admin/peliculas');
      await expect(page.getByRole('table')).toBeVisible();
      await page.waitForLoadState('networkidle');
      await shot(page, '13-admin-peliculas', true);

      await page.getByRole('button', { name: `Borrar ${HERO_TITLE}` }).click();
      await expect(page.getByRole('alertdialog')).toBeVisible();
      await shot(page, '14-admin-confirmar-borrado');
      await page.getByRole('alertdialog').getByRole('button', { name: 'Cancelar' }).click();

      await page.goto('/admin/peliculas/nueva');
      // Formulario completo antes de pulsar: la llegada de los géneros desplaza el botón (ver la función).
      await waitForMovieForm(page);
      await page.getByLabel('URL de la portada').fill('/covers/interstellar.webp');
      await page.getByRole('button', { name: 'Crear película' }).click();
      await expect(page.getByText('Elige al menos un género')).toBeVisible();
      await page.waitForLoadState('networkidle');
      await shot(page, '15-admin-formulario-errores', true);

      await page.goto('/admin/generos');
      await expect(page.getByRole('list', { name: 'Géneros' })).toBeVisible();
      await page.getByRole('button', { name: 'Renombrar Drama' }).click();
      await shot(page, '16-admin-generos-renombrar', true);
      await page.keyboard.press('Escape');
    });

    test('panel de series (solo lectura)', async ({ page, request, signIn }) => {
      const adminToken = await loginAdmin(request);
      await signIn({ token: adminToken });
      await page.goto('/admin/series');
      await expect(page.getByRole('table', { name: /^Series del catálogo/ })).toBeVisible();
      await page.waitForLoadState('networkidle');
      await shot(page, '17-admin-series', true);

      await page.goto('/admin/series/nueva');
      await waitForMovieForm(page);
      await page.getByLabel('Año de fin (opcional)').fill('1500');
      await page.getByRole('button', { name: 'Crear serie' }).click();
      await expect(page.getByText('Indica al menos un género')).toBeVisible();
      await shot(page, '18-admin-serie-formulario-errores', true);

      // Edición de una serie sembrada con episodios: la lista por temporadas y el diálogo de episodio
      // (abierto con los valores propuestos y después con errores; no se guarda nada).
      await page.goto(`/admin/series/${await findSeriesIdAsAdmin(request, adminToken, SERIES_HERO.title)}/editar`);
      const episodes = page.getByRole('region', { name: 'Episodios', exact: true });
      await expect(episodes.getByRole('list', { name: /^Temporada 1 · / })).toBeVisible();
      await page.waitForLoadState('networkidle');
      await shot(page, '19-admin-serie-edicion-episodios', true);
      await episodes.scrollIntoViewIfNeeded();
      await shot(page, '20-admin-serie-episodios');

      await episodes.getByRole('button', { name: 'Añadir episodio' }).click();
      const dialog = page.getByRole('dialog', { name: 'Añadir episodio' });
      await expect(dialog.getByLabel('Título', { exact: true })).toBeFocused();
      await shot(page, '21-admin-episodio-modal');
      await dialog.getByRole('button', { name: 'Añadir episodio' }).click();
      await expect(dialog.getByLabel('Título', { exact: true })).toHaveAccessibleDescription('El título es obligatorio');
      await shot(page, '22-admin-episodio-modal-errores');
      await page.keyboard.press('Escape');

      await episodes.getByRole('button', { name: /^Borrar episodio T1:E1 / }).click();
      await expect(page.getByRole('alertdialog')).toBeVisible();
      await shot(page, '23-admin-episodio-confirmar-borrado');
      await page.getByRole('alertdialog').getByRole('button', { name: 'Cancelar' }).click();
    });
  });
}
