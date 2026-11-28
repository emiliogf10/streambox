/**
 * E2E del `poster` del reproductor cuando la imagen elegida está ROTA
 * (incidencia QA-V16-1), con el `<video>` real de Chromium.
 *
 * El atributo `poster` no avisa cuando su imagen falla: sin comprobarla, una
 * imagen de fondo que da 404 dejaba el vídeo en negro hasta el primer
 * fotograma. `useFirstLoadableImage` la prueba con `new Image()` y pasa a la
 * siguiente candidata. Vitest lo prueba con un `Image` falso; solo aquí se ve
 * que el `error` de una imagen de verdad llega y cambia el `poster`.
 *
 * **Sin tocar el catálogo compartido** (proyecto principal): las imágenes y el
 * vídeo se cambian interceptando la respuesta real de la API en la página del
 * test, y las imágenes se sirven desde direcciones inventadas de otro origen
 * (`https://media.streambox.example/...`), que nunca salen a Internet. Ningún
 * test reproduce el vídeo, así que no se guarda progreso.
 */
import type { Page } from '@playwright/test';
import { findMovieId, findSeriesIdAsAdmin, loginAdmin } from './support/api';
import { expect, test } from './support/fixtures';
import { CLIP_URL, serveClip } from './support/media';

/** Película y serie sembradas con vídeo (de otra web): aquí se les pone el clip. */
const MOVIE_TITLE = 'El marciano';
const SERIES_TITLE = 'Herederos del Silencio';

/** Imagen que responde 404. */
const BROKEN_IMAGE = 'https://media.streambox.example/e2e/rota.png';
/** Imagen que carga (un PNG de 1×1). */
const GOOD_IMAGE = 'https://media.streambox.example/e2e/buena.png';

/** PNG válido de 1×1 píxeles. */
const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=',
  'base64',
);

/** Sirve {@link GOOD_IMAGE} y responde 404 a {@link BROKEN_IMAGE}. Devuelve las URLs pedidas. */
async function serveImages(page: Page): Promise<string[]> {
  const requested: string[] = [];
  await page.route('https://media.streambox.example/e2e/*.png', (route) => {
    const url = route.request().url();
    requested.push(url);
    return url === GOOD_IMAGE
      ? route.fulfill({ status: 200, headers: { 'Content-Type': 'image/png' }, body: PNG })
      : route.fulfill({ status: 404, headers: { 'Content-Type': 'text/plain' }, body: 'No encontrado' });
  });
  return requested;
}

/** Cambia en la respuesta real de `GET <path>` lo que diga `patch` (vídeo e imágenes). */
async function patchResponse(page: Page, path: string, patch: (body: Record<string, unknown>) => void): Promise<void> {
  await page.route(`**${path}`, async (route) => {
    if (route.request().method() !== 'GET') return route.continue();
    const response = await route.fetch();
    const body = (await response.json()) as Record<string, unknown>;
    patch(body);
    await route.fulfill({ response, json: body });
  });
}

test.describe('Reproductor: poster con una imagen rota', () => {
  test('película: si la imagen de fondo da 404, el poster pasa a la portada (no se queda en negro)', async ({
    page,
    request,
    user,
    signIn,
  }) => {
    await signIn(user);
    const movieId = await findMovieId(request, user.token, MOVIE_TITLE);
    await serveClip(page);
    const requested = await serveImages(page);
    await patchResponse(page, `/api/movies/${movieId}`, (movie) => {
      movie.videoUrl = CLIP_URL;
      movie.backdropUrl = BROKEN_IMAGE;
      movie.imageUrl = GOOD_IMAGE;
    });

    await page.goto(`/ver/peliculas/${movieId}`);
    await expect(page.getByRole('heading', { level: 1, name: MOVIE_TITLE })).toBeVisible();
    const video = page.locator('video');
    await expect(video).toHaveAttribute('poster', GOOD_IMAGE);
    expect(requested).toContain(BROKEN_IMAGE);
  });

  test('episodio: una imagen del episodio rota no tapa la imagen de fondo de la serie', async ({
    page,
    request,
    user,
    signIn,
  }) => {
    await signIn(user);
    const seriesId = await findSeriesIdAsAdmin(request, await loginAdmin(request), SERIES_TITLE);
    await serveClip(page);
    const requested = await serveImages(page);
    await patchResponse(page, `/api/series/${seriesId}`, (series) => {
      series.backdropUrl = GOOD_IMAGE;
      for (const season of series.seasons as { episodes: Record<string, unknown>[] }[]) {
        for (const episode of season.episodes) {
          episode.videoUrl = CLIP_URL;
          episode.imageUrl = BROKEN_IMAGE;
        }
      }
    });

    await page.goto(`/series/${seriesId}`);
    await expect(page.getByRole('heading', { level: 1, name: SERIES_TITLE })).toBeVisible();
    await page.getByRole('link', { name: /^Empezar a ver / }).click();
    await expect(page.getByText('T1:E1 · Capítulo 1.1')).toBeVisible();
    await expect(page.locator('video')).toHaveAttribute('poster', GOOD_IMAGE);
    expect(requested).toContain(BROKEN_IMAGE);
  });
});
