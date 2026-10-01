/**
 * E2E del catálogo de la portada: banner, filas y paginación con "Cargar más".
 *
 * Usa los 25 títulos sembrados (ver `support/catalog.ts`): con páginas de 20 son
 * exactamente dos páginas, así que la segunda solo aparece si el botón funciona.
 */
import { HERO_TITLE, MOVIES, PAGE_SIZE } from './support/catalog';
import { expect, movieCard, test } from './support/fixtures';

/** Géneros que tienen fila propia en la portada (≥ 3 películas cargadas). */
const GENRE_ROWS = ['Ciencia ficción', 'Acción', 'Drama'];

/** Regex de la etiqueta de una tarjeta: «Título 2014 · 2 h 1 min». */
const CARD_NAME = /\d{4} ·/;

test.describe('Catálogo', () => {
  test.beforeEach(async ({ page, user, signIn }) => {
    await signIn(user);
    await page.goto('/');
  });

  test('el banner es la película más reciente y tiene sus acciones', async ({ page }) => {
    const banner = page.getByRole('region', { name: HERO_TITLE });
    await expect(banner).toBeVisible();
    await expect(banner.getByText('Estreno reciente')).toBeVisible();
    await expect(banner.getByRole('link', { name: new RegExp(`^Ver ahora ${HERO_TITLE}`) })).toBeVisible();
    await expect(banner.getByRole('button', { name: /^Mi lista/ })).toBeVisible();
    await expect(banner.getByRole('button', { name: /^Más información/ })).toBeVisible();

    // La portada del banner se ha descargado de verdad (no es el hueco de respaldo).
    const image = banner.locator('img');
    await expect(image).toHaveCount(1);
    await expect.poll(() => image.evaluate((img: HTMLImageElement) => img.naturalWidth)).toBeGreaterThan(0);

    // ...y CUBRE todo el banner. Regresión real: con `relative` + `absolute` en el mismo contenedor
    // la portada salía a su tamaño intrínseco (600 px) y dejaba el resto del banner vacío.
    // (La sección tiene un margen lateral pequeño, por eso se pide ≥ 90 % de su ancho y no el 100 %.)
    const sectionBox = await banner.boundingBox();
    const imageBox = await image.boundingBox();
    expect(imageBox?.width ?? 0).toBeGreaterThanOrEqual((sectionBox?.width ?? Infinity) * 0.9);

    // Sinopsis y ficha técnica del banner.
    await expect(page.getByRole('heading', { level: 3, name: 'Sinopsis' })).toBeVisible();
    await expect(page.getByRole('heading', { level: 3, name: 'Ficha' })).toBeVisible();
  });

  test('hay una fila de Novedades y una por género con varias películas, sin repetir la del banner', async ({
    page,
  }) => {
    const news = page.getByRole('region', { name: 'Novedades' });
    await expect(news).toBeVisible();
    // La más reciente DESPUÉS del banner abre la fila (el banner no se repite debajo).
    await expect(news.getByRole('button', { name: CARD_NAME })).toHaveCount(12);
    await expect(movieCard(news, MOVIES[MOVIES.length - 2].title)).toBeVisible();
    await expect(movieCard(page.getByRole('main'), HERO_TITLE)).toHaveCount(0);

    for (const genre of GENRE_ROWS) {
      const row = page.getByRole('region', { name: genre, exact: true });
      await expect(row).toBeVisible();
      expect(await row.getByRole('button', { name: CARD_NAME }).count()).toBeGreaterThanOrEqual(3);
    }

    await expect(page.getByText(`Mostrando ${PAGE_SIZE} de ${MOVIES.length} películas`)).toBeVisible();
  });

  test('una película sin portada muestra el hueco de respaldo y no una imagen rota', async ({ page }) => {
    const news = page.getByRole('region', { name: 'Novedades' });
    // «La Casa del Lago» se sembró con una URL de portada inexistente (404).
    const card = movieCard(news, 'La Casa del Lago');
    await expect(card).toBeVisible();
    await expect(card.locator('img')).toHaveCount(0);
    // El título también aparece dentro del hueco (además del pie de la tarjeta).
    await expect(card.getByText('La Casa del Lago')).toHaveCount(2);
  });

  test('«Cargar más películas» añade la segunda página sin duplicados y el botón desaparece', async ({ page }) => {
    const main = page.getByRole('main');
    const oldest = MOVIES[0].title; // «Matrix», de la segunda página
    await expect(movieCard(main, oldest)).toHaveCount(0);

    const loadMore = page.getByRole('button', { name: 'Cargar más películas' });
    await expect(loadMore).toBeVisible();

    const nextPage = page.waitForResponse(
      (response) =>
        response.url().includes('/api/movies?') && response.url().includes('page=1') && response.status() === 200,
    );
    await loadMore.click();
    await nextPage;

    await expect(page.getByText(`Mostrando ${MOVIES.length} de ${MOVIES.length} películas`)).toBeVisible();
    await expect(loadMore).toBeHidden();
    await expect(movieCard(main, oldest).first()).toBeVisible();

    // Sin duplicados DENTRO de cada fila...
    const rows = main.getByRole('region');
    const rowCount = await rows.count();
    expect(rowCount).toBeGreaterThanOrEqual(4); // banner + Novedades + géneros
    for (let index = 0; index < rowCount; index += 1) {
      const labels = await rows.nth(index).getByRole('button', { name: CARD_NAME }).allTextContents();
      expect(new Set(labels).size, `Fila ${index} con tarjetas repetidas`).toBe(labels.length);
    }

    // ...y las 25 películas (banner + resto) están presentes en la página.
    const allCards = await main.getByRole('button', { name: CARD_NAME }).allTextContents();
    for (const movie of MOVIES.filter((m) => m.title !== HERO_TITLE)) {
      const prefix = `${movie.title}${movie.releaseYear}`;
      expect(
        allCards.some((text) => text.includes(prefix)),
        `Falta «${movie.title}» tras cargar la segunda página`,
      ).toBe(true);
    }
  });
});
