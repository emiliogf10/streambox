/**
 * E2E de los «Me gusta» y las recomendaciones (punto 4 del plan): dar «Me
 * gusta» a una película y a una serie desde su página, verlas en la sección
 * «Te gusta» del perfil (también tras recargar: lo guarda el servidor), ver las
 * filas «… recomendadas para ti» del inicio con títulos de los mismos géneros
 * (y sin los que ya te gustan), y quitarlos: el perfil vuelve a su estado vacío
 * y las filas desaparecen. Además, el teclado y la cabecera a 375 px con todas
 * las acciones.
 *
 * Cada test registra su propio usuario, así que sus «Me gusta» no se mezclan
 * aunque corran en paralelo. No modifica el catálogo (va en el proyecto
 * principal de Playwright).
 */
import type { Locator, Page } from '@playwright/test';
import { MOVIES, SERIES_HERO, VISIBLE_SERIES } from './support/catalog';
import { escapeRegExp, expect, movieCard, test } from './support/fixtures';

/** Película a la que se da «Me gusta»: Ciencia ficción + Drama. */
const FILM = MOVIES.find((movie) => movie.title === 'Interstellar');
if (!FILM) throw new Error('El catálogo de ejemplo debe incluir «Interstellar»');

/**
 * Película que debe recomendarse: comparte los DOS géneros de «Interstellar»,
 * así que está entre las más afines (hay seis con esos dos géneros y se piden doce).
 */
const RECOMMENDED_FILM = MOVIES.find((movie) => movie.title === 'Blade Runner 2049');
if (!RECOMMENDED_FILM) throw new Error('El catálogo de ejemplo debe incluir «Blade Runner 2049»');
for (const genre of FILM.genres) {
  if (!RECOMMENDED_FILM.genres.includes(genre)) throw new Error('«Blade Runner 2049» debe compartir géneros con «Interstellar»');
}

/** Serie a la que se da «Me gusta» (la del banner de `/series`: Drama + Acción). */
const SHOW = SERIES_HERO;

/** Otra serie visible que comparte «Drama» con la anterior: debe recomendarse. */
const RECOMMENDED_SHOW = VISIBLE_SERIES.find((series) => series.title === 'Los Archivos de Niebla');
if (!RECOMMENDED_SHOW || !RECOMMENDED_SHOW.genres.some((genre) => SHOW.genres.includes(genre))) {
  throw new Error('«Los Archivos de Niebla» debe ser visible y compartir algún género con la serie del banner');
}

/** Aviso al dar «Me gusta» (mismo texto que `likedMessage` en `useLikes`). */
const liked = (title: string) => `Te gusta «${title}». Lo tendremos en cuenta para recomendarte títulos parecidos.`;
/** Aviso al quitarlo (`unlikedMessage`). */
const unliked = (title: string) => `Ya no te gusta «${title}».`;

/** Tarjeta (enlace) de una serie: «Título 2019–2022 · 2 temporadas». */
function seriesCard(scope: Page | Locator, title: string): Locator {
  return scope.getByRole('link', { name: new RegExp(`^${escapeRegExp(title)} \\d{4}`) });
}

/** Botón «Me gusta» / «Te gusta» de un título en la cabecera de su página. */
function likeButton(page: Page, title: string, state: 'Me gusta' | 'Te gusta'): Locator {
  return page
    .getByRole('region', { name: title })
    .getByRole('button', { name: new RegExp(`^${state}\\s*—\\s*${escapeRegExp(title)}$`) });
}

/** Abre la página de «Interstellar» desde la fila «Novedades» de la portada. */
async function openFilm(page: Page) {
  await page.goto('/');
  await movieCard(page.getByRole('region', { name: 'Novedades' }), FILM!.title).click();
  await expect(page.getByRole('heading', { level: 1, name: FILM!.title })).toBeVisible();
}

/** Abre la página de la serie del banner de `/series`. */
async function openShow(page: Page) {
  await page.goto('/series');
  await page.getByRole('region', { name: SHOW.title }).getByRole('link', { name: /^Más información/ }).click();
  await expect(page.getByRole('heading', { level: 1, name: SHOW.title })).toBeVisible();
}

/**
 * Abre la portada y espera a que el servidor haya respondido a las
 * recomendaciones; devuelve su `basedOnLikes`.
 *
 * Ya no vale esperar a que se vaya su esqueleto: a quien no tiene «Me gusta» no
 * se le pinta ninguno (ver `RecommendedRows`), así que esa espera pasaría antes
 * de la respuesta y un «no hay filas» podría comprobarse demasiado pronto. Se
 * escucha la respuesta ANTES de navegar porque la portada la pide en su primer
 * render, en paralelo con el catálogo. Con `basedOnLikes: false` el componente
 * no pinta nada, así que tras confirmarlo la ausencia de filas es determinista.
 */
async function openHomeAndWaitForRecommendations(page: Page): Promise<boolean> {
  const answered = page.waitForResponse(
    (response) =>
      response.request().method() === 'GET' && new URL(response.url()).pathname === '/api/users/me/recommendations',
  );
  await page.goto('/');
  const body = (await (await answered).json()) as { basedOnLikes: boolean };
  await expect(page.getByRole('region', { name: 'Novedades' })).toBeVisible();
  return body.basedOnLikes;
}

test.describe('«Me gusta» y recomendaciones', () => {
  test.beforeEach(async ({ user, signIn }) => {
    await signIn(user);
  });

  test('dar «Me gusta» a una película y a una serie: salen en el perfil y el inicio recomienda títulos parecidos; al quitarlos, desaparecen', async ({
    page,
  }) => {
    // Recorrido largo (unas quince cargas de página). Sin traza tarda ~15 s, pero con la traza de
    // Playwright (`retain-on-failure`, se graba siempre) el cierre del contexto guarda instantáneas de
    // páginas de ~9000 nodos y, en una máquina cargada, la suma pasaba de los 30 s por defecto: fallaba en
    // el «Tearing down "context"» con todas las comprobaciones ya en verde (revisión de QA, 2026-10-18).
    // Se le da el triple de tiempo, como a `trailer-layout.spec.ts`; no se quita ninguna comprobación.
    test.slow();
    // Película.
    await openFilm(page);
    await likeButton(page, FILM.title, 'Me gusta').click();
    await expect(page.getByText(liked(FILM.title))).toBeVisible();
    await expect(likeButton(page, FILM.title, 'Te gusta')).toHaveAttribute('aria-pressed', 'true');
    // «Mi lista» es otra cosa: no cambia.
    await expect(page.getByRole('region', { name: FILM.title }).getByRole('button', { name: /^Mi lista/ })).toBeVisible();

    // Serie.
    await openShow(page);
    await likeButton(page, SHOW.title, 'Me gusta').click();
    await expect(page.getByText(liked(SHOW.title))).toBeVisible();
    await expect(likeButton(page, SHOW.title, 'Te gusta')).toHaveAttribute('aria-pressed', 'true');

    // Perfil: las dos, también tras recargar (vienen del servidor, no del estado local).
    await page.goto('/perfil');
    const likes = page.getByRole('region', { name: 'Te gusta' });
    await expect(likes.getByText('2 títulos · lo más reciente primero')).toBeVisible();
    await expect(movieCard(likes, FILM.title)).toBeVisible();
    await expect(seriesCard(likes, SHOW.title)).toBeVisible();
    await page.reload();
    await expect(movieCard(page.getByRole('region', { name: 'Te gusta' }), FILM.title)).toBeVisible();

    // Inicio: las dos filas, con títulos de los mismos géneros y sin los que ya te gustan.
    await page.goto('/');
    const recommendedMovies = page.getByRole('region', { name: 'Películas recomendadas para ti' });
    await expect(recommendedMovies).toBeVisible();
    await expect(movieCard(recommendedMovies, RECOMMENDED_FILM.title)).toBeVisible();
    await expect(movieCard(recommendedMovies, FILM.title)).toHaveCount(0);
    const recommendedSeries = page.getByRole('region', { name: 'Series recomendadas para ti' });
    await expect(seriesCard(recommendedSeries, RECOMMENDED_SHOW.title)).toBeVisible();
    await expect(seriesCard(recommendedSeries, SHOW.title)).toHaveCount(0);
    // Justo después de «Novedades».
    const rows = await page.getByRole('heading', { level: 2 }).allTextContents();
    expect(rows.indexOf('Películas recomendadas para ti')).toBe(rows.indexOf('Novedades') + 1);

    // Quitar los dos desde su página (se llega desde las tarjetas del perfil).
    await page.goto('/perfil');
    await movieCard(page.getByRole('region', { name: 'Te gusta' }), FILM.title).click();
    await likeButton(page, FILM.title, 'Te gusta').click();
    await expect(page.getByText(unliked(FILM.title))).toBeVisible();
    await expect(likeButton(page, FILM.title, 'Me gusta')).toHaveAttribute('aria-pressed', 'false');
    await page.goto('/perfil');
    await seriesCard(page.getByRole('region', { name: 'Te gusta' }), SHOW.title).click();
    await likeButton(page, SHOW.title, 'Te gusta').click();
    await expect(page.getByText(unliked(SHOW.title))).toBeVisible();

    // El perfil vuelve al estado vacío y el inicio, a no tener filas recomendadas.
    await page.goto('/perfil');
    await expect(page.getByRole('region', { name: 'Te gusta' }).getByText('Todavía no te gusta ningún título')).toBeVisible();
    expect(await openHomeAndWaitForRecommendations(page)).toBe(false);
    await expect(page.getByRole('region', { name: /recomendadas para ti$/ })).toHaveCount(0);
  });

  test('un usuario nuevo: «Te gusta» explica cómo llenarlo y el inicio no tiene filas recomendadas', async ({ page }) => {
    await page.goto('/perfil');
    const likes = page.getByRole('region', { name: 'Te gusta' });
    await expect(likes.getByText('Todavía no te gusta ningún título')).toBeVisible();
    await expect(
      likes.getByText(
        'Cuando veas una película o una serie que te guste, pulsa «Me gusta» en su página. Aquí la verás, y en el inicio te recomendaremos títulos parecidos.',
      ),
    ).toBeVisible();

    expect(await openHomeAndWaitForRecommendations(page)).toBe(false);
    await expect(page.getByRole('region', { name: /recomendadas para ti$/ })).toHaveCount(0);
    // Ni un hueco ni un aviso de carga que no lleva a nada.
    await expect(page.getByText('Cargando recomendaciones...')).toHaveCount(0);
  });

  test('con el teclado: Espacio da y quita el «Me gusta» y el foco no se mueve del botón', async ({ page }) => {
    await openFilm(page);
    const heart = likeButton(page, FILM.title, 'Me gusta');
    await heart.focus();

    await page.keyboard.press('Space');
    const pressed = likeButton(page, FILM.title, 'Te gusta');
    await expect(pressed).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByText(liked(FILM.title))).toBeVisible();
    await expect(pressed).toBeFocused();
    // Ya sin petición en curso (se desbloquea), vuelve a pulsarse con el teclado.
    await expect(pressed).not.toHaveAttribute('aria-disabled', 'true');

    await page.keyboard.press('Space');
    await expect(likeButton(page, FILM.title, 'Me gusta')).toHaveAttribute('aria-pressed', 'false');
    await expect(likeButton(page, FILM.title, 'Me gusta')).toBeFocused();
  });

  test('a 375 px, la cabecera con «Ver ahora», «Ver tráiler», «Mi lista» y «Me gusta» no parte ningún texto ni desborda', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    // La película del banner tiene vídeo y tráiler: las cuatro acciones de un usuario.
    const hero = MOVIES[MOVIES.length - 1];
    await page.goto('/');
    await page.getByRole('region', { name: hero.title }).getByRole('link', { name: /^Más información/ }).click();
    const header = page.getByRole('region', { name: hero.title });
    await expect(header.getByRole('heading', { level: 1, name: hero.title })).toBeVisible();

    const actions = [
      header.getByRole('link', { name: /^Ver ahora/ }),
      header.getByRole('button', { name: /^Ver tráiler/ }),
      header.getByRole('button', { name: /^Mi lista/ }),
      header.getByRole('button', { name: /^Me gusta/ }),
    ];
    for (const action of actions) {
      await expect(action).toBeVisible();
      const box = await action.boundingBox();
      // Una sola línea: la altura del botón (44 px) y no la de dos líneas de texto.
      expect(box?.height ?? 0).toBeLessThanOrEqual(48);
      expect(box?.height ?? 0).toBeGreaterThanOrEqual(44);
    }
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth > document.documentElement.clientWidth);
    expect(overflow, 'la página no debe tener desplazamiento horizontal').toBe(false);
  });
});
