/**
 * E2E del catálogo de la portada: banner, filas y paginación con "Cargar más".
 *
 * Usa los 25 títulos sembrados (ver `support/catalog.ts`): con páginas de 20 son
 * exactamente dos páginas, así que la segunda solo aparece si el botón funciona.
 */
import type { Page } from '@playwright/test';
import { HERO_TITLE, MOVIES, PAGE_SIZE } from './support/catalog';
import { expect, movieCard, test } from './support/fixtures';
import { expectCovers, expectWholePoster } from './support/images';

/** Géneros que tienen fila propia en la portada (≥ 3 películas cargadas). */
const GENRE_ROWS = ['Ciencia ficción', 'Acción', 'Drama'];

/** Regex de la etiqueta de una tarjeta (un enlace): «Título 2014 · 2 h 1 min». */
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
    // «Más información» es un enlace a la página de la película (ya no abre un diálogo).
    await expect(banner.getByRole('link', { name: /^Más información/ })).toHaveAttribute('href', /^\/peliculas\/\d+$/);

    // Dos imágenes (en este orden en el DOM): el fondo desenfocado y el póster nítido. Las dos son
    // la MISMA URL, así que el navegador la descarga una sola vez.
    const images = banner.locator('img');
    await expect(images).toHaveCount(2);
    const [backdrop, poster] = [images.first(), images.last()];
    expect(await backdrop.getAttribute('src')).toBe(await poster.getAttribute('src'));

    // El FONDO cubre todo el banner. Regresión real: con `relative` + `absolute` en el mismo
    // contenedor la portada salía a su tamaño intrínseco (600 px) y dejaba el resto vacío.
    await expectCovers(backdrop, banner, 'fondo del banner');
    // El PÓSTER se ve entero (2:3, sin recorte). Defecto anterior: el póster vertical estirado a lo
    // ancho del banner solo dejaba ver una franja ampliada («PARTE DOS» gigante).
    await expectWholePoster(poster, banner, 'póster del banner');

    // Sinopsis recortada en el banner; ya no hay paneles «Sinopsis/Reparto/Ficha» debajo.
    await expect(banner.getByText(/^En un futuro cercano/)).toBeVisible();
    await expect(page.getByRole('heading', { level: 3 })).toHaveCount(0);
    await expect(page.getByText('Información no disponible en esta versión.')).toHaveCount(0);
  });

  test('hay una fila de Novedades y una por género con varias películas, sin repetir la del banner', async ({
    page,
  }) => {
    const news = page.getByRole('region', { name: 'Novedades' });
    await expect(news).toBeVisible();
    // La más reciente DESPUÉS del banner abre la fila (el banner no se repite debajo).
    await expect(news.getByRole('link', { name: CARD_NAME })).toHaveCount(12);
    await expect(movieCard(news, MOVIES[MOVIES.length - 2].title)).toBeVisible();
    await expect(movieCard(page.getByRole('main'), HERO_TITLE)).toHaveCount(0);

    for (const genre of GENRE_ROWS) {
      const row = page.getByRole('region', { name: genre, exact: true });
      await expect(row).toBeVisible();
      expect(await row.getByRole('link', { name: CARD_NAME }).count()).toBeGreaterThanOrEqual(3);
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
      const labels = await rows.nth(index).getByRole('link', { name: CARD_NAME }).allTextContents();
      expect(new Set(labels).size, `Fila ${index} con tarjetas repetidas`).toBe(labels.length);
    }

    // ...y las 25 películas (banner + resto) están presentes en la página.
    const allCards = await main.getByRole('link', { name: CARD_NAME }).allTextContents();
    for (const movie of MOVIES.filter((m) => m.title !== HERO_TITLE)) {
      const prefix = `${movie.title}${movie.releaseYear}`;
      expect(
        allCards.some((text) => text.includes(prefix)),
        `Falta «${movie.title}» tras cargar la segunda página`,
      ).toBe(true);
    }
  });
});

/**
 * Flechas «Ver anteriores/siguientes» de las filas de la portada: solo cuando
 * la fila desborda. Origen: en una pantalla ultraancha (~3400 px) la fila
 * «Novedades» (12 pósters) cabía entera y aun así enseñaba las flechas; lo
 * mismo la fila «Series» (4 series sembradas). Se mide en el navegador real,
 * porque jsdom no maqueta (los casos finos están en `PosterRow.test.tsx`).
 */
test.describe('Catálogo: flechas de las filas', () => {
  test.beforeEach(async ({ user, signIn }) => {
    await signIn(user);
  });

  /** Fila de la portada por su título y sus dos flechas. */
  function row(page: Page, name: string) {
    const region = page.getByRole('region', { name, exact: true });
    return {
      region,
      list: region.getByRole('list'),
      previous: region.getByRole('button', { name: `Ver anteriores en ${name}` }),
      next: region.getByRole('button', { name: `Ver siguientes en ${name}` }),
    };
  }

  /** Distancia entre el principio de la fila y sus tarjetas: no debe cambiar al ocultar las flechas. */
  async function headerOffset(rowParts: ReturnType<typeof row>): Promise<number> {
    const section = (await rowParts.region.boundingBox())!;
    const list = (await rowParts.list.boundingBox())!;
    return list.y - section.y;
  }

  test('pantalla ultraancha (3440 px): las filas que caben enteras no tienen flechas', async ({ page }) => {
    await page.setViewportSize({ width: 3440, height: 1440 });
    await page.goto('/');
    const news = row(page, 'Novedades');
    const series = row(page, 'Series');
    await expect(news.list.getByRole('link', { name: CARD_NAME })).toHaveCount(12);
    await expect(series.list.getByRole('listitem')).not.toHaveCount(0);

    // La premisa: las 12 tarjetas caben (no hay nada que desplazar)...
    const fits = await news.list.evaluate((list) => list.scrollWidth <= list.clientWidth);
    expect(fits, 'a 3440 px «Novedades» debería caber entera').toBe(true);
    // ...y por eso no hay flechas, ni en «Novedades» ni en «Series».
    await expect(news.previous).toHaveCount(0);
    await expect(news.next).toHaveCount(0);
    await expect(series.next).toHaveCount(0);
  });

  test('escritorio (1280 px): «Novedades» desborda y tiene flechas que funcionan; «Series» (4) cabe y no las tiene', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto('/');
    const news = row(page, 'Novedades');

    await expect(news.next).toBeVisible();
    await expect(news.previous).toHaveAttribute('aria-disabled', 'true');
    await expect(news.next).toHaveAttribute('aria-disabled', 'false');
    await news.next.click();
    await expect(news.previous).toHaveAttribute('aria-disabled', 'false');

    await expect(row(page, 'Series').list.getByRole('listitem')).not.toHaveCount(0);
    await expect(row(page, 'Series').next).toHaveCount(0);
  });

  test('al ensanchar la ventana las flechas desaparecen sin mover las tarjetas, y al estrecharla vuelven', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 });
    await page.goto('/');
    const news = row(page, 'Novedades');
    await expect(news.next).toBeVisible();
    const withArrows = await headerOffset(news);

    await page.setViewportSize({ width: 3440, height: 1440 });
    await expect(news.next).toHaveCount(0);
    // La cabecera reserva el alto de las flechas: las tarjetas quedan a la misma distancia del título.
    expect(await headerOffset(news)).toBeCloseTo(withArrows, 0);

    await page.setViewportSize({ width: 1024, height: 800 });
    await expect(news.next).toBeVisible();
  });

  test('móvil (375 px): sin flechas, la fila se desplaza con el dedo', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto('/');
    const news = row(page, 'Novedades');
    await expect(news.list.getByRole('link', { name: CARD_NAME })).toHaveCount(12);

    // Desborda, pero las flechas solo se muestran desde `md` (en táctil se arrastra).
    const overflows = await news.list.evaluate((list) => list.scrollWidth > list.clientWidth);
    expect(overflows).toBe(true);
    await expect(news.next).toBeHidden();
  });
});
