/**
 * E2E de los subtítulos del reproductor (WebVTT, migración V15) con el
 * `<video>` real de Chromium: que la pista exista y cargue cuando todo admite
 * CORS, y que los subtítulos **nunca rompan el vídeo** cuando algo falla.
 *
 * Por qué hace falta un navegador de verdad: un `<track>` de otro origen solo se
 * carga si el `<video>` lleva `crossorigin`, y con él el navegador pide también
 * el vídeo con CORS. Si el servidor del vídeo no lo admite, el vídeo falla, y
 * `PlayerScreen` lo vuelve a montar sin `crossorigin` ni pista. jsdom (Vitest)
 * no descarga nada ni aplica CORS: solo aquí se ve que el rechazo de CORS llega
 * de verdad como `error` del `<video>`, que el segundo intento se reproduce y
 * que la pista que da 404 avisa sin parar el vídeo.
 *
 * **Sin tocar el catálogo compartido** (proyecto principal): el vídeo y los
 * subtítulos se ponen interceptando la respuesta de la API en la página del
 * test (`support/media.ts`), y cada test usa su propio usuario. Ningún test
 * reproduce 10 s seguidos sin querer: el administrador del tercero no guarda
 * progreso (no aparecería «Seguir viendo» en su inicio, que miran otros specs).
 */
import type { Page } from '@playwright/test';
import { findMovieId, loginAdmin, saveMovieProgressApi } from './support/api';
import { expect, test } from './support/fixtures';
import { CLIP_URL, serveClip, serveSubtitles, SUBTITLES_URL, withMedia } from './support/media';

/** Película sembrada con vídeo (de otra web): aquí se le pone el clip y los subtítulos. */
const MOVIE_TITLE = 'El marciano';

/** Aviso del pie cuando los subtítulos no se pueden usar (`PlayerScreen`). */
const SUBTITLES_FAILED = 'No se han podido cargar los subtítulos: el vídeo sigue sin ellos.';

/** Lo que el administrador lee además: dónde mirar. */
const SUBTITLES_FAILED_ADMIN =
  `${SUBTITLES_FAILED} Comprueba su URL en el panel y que los servidores del vídeo y de los subtítulos permitan CORS.`;

/** Abre el reproductor de la película desde su página (con un clic: cuenta como gesto del usuario). */
async function openPlayer(page: Page, movieId: number): Promise<void> {
  await page.goto(`/peliculas/${movieId}`);
  await page.getByRole('link', { name: new RegExp(`^(Ver ahora|Reanudar) ${MOVIE_TITLE}$`) }).click();
  await expect(page.getByRole('heading', { level: 1, name: MOVIE_TITLE })).toBeVisible();
}

/** El `<video>` con sus metadatos ya leídos (el archivo se ha descargado y entendido). */
async function loadedVideo(page: Page) {
  const video = page.locator('video');
  await expect(video).toHaveAttribute('src', CLIP_URL);
  await expect.poll(() => video.evaluate((element: HTMLVideoElement) => element.readyState)).toBeGreaterThanOrEqual(1);
  return video;
}

/** Activa la pista como lo haría el botón de subtítulos de los controles nativos. */
async function showSubtitles(page: Page): Promise<void> {
  await page.locator('video').evaluate((element: HTMLVideoElement) => {
    element.textTracks[0].mode = 'showing';
  });
}

/** Reproduce sin sonido unos instantes y comprueba que avanza (el vídeo «sigue»). */
async function expectPlays(page: Page): Promise<void> {
  const video = page.locator('video');
  const from = await video.evaluate((element: HTMLVideoElement) => {
    element.muted = true;
    void element.play();
    return element.currentTime;
  });
  await expect.poll(() => video.evaluate((element: HTMLVideoElement) => element.currentTime)).toBeGreaterThan(from + 0.5);
  await video.evaluate((element: HTMLVideoElement) => element.pause());
}

test.describe('Subtítulos en el reproductor', () => {
  test('con CORS en los dos servidores: la pista «Español» existe, el vídeo va con crossorigin y los subtítulos cargan', async ({
    page,
    request,
    user,
    signIn,
  }) => {
    await signIn(user);
    const movieId = await findMovieId(request, user.token, MOVIE_TITLE);
    await serveClip(page);
    const requested = await serveSubtitles(page);
    await withMedia(page, `/api/movies/${movieId}`, SUBTITLES_URL);

    await openPlayer(page, movieId);
    const video = await loadedVideo(page);
    await expect(video).toHaveAttribute('crossorigin', 'anonymous');
    const track = video.locator('track');
    await expect(track).toHaveCount(1);
    await expect(track).toHaveAttribute('kind', 'subtitles');
    await expect(track).toHaveAttribute('srclang', 'es');
    await expect(track).toHaveAttribute('label', 'Español');
    await expect(track).toHaveAttribute('src', SUBTITLES_URL);
    // Sin `default`: desactivada hasta que el usuario la pide (y por eso el navegador aún no la ha descargado).
    await expect(track).not.toHaveAttribute('default', /.*/);
    expect(await video.evaluate((element: HTMLVideoElement) => element.textTracks[0].mode)).toBe('disabled');

    await showSubtitles(page);
    // HTMLTrackElement.LOADED = 2 (ERROR = 3): el archivo ha llegado con CORS y es un WebVTT válido.
    await expect.poll(() => track.evaluate((element: HTMLTrackElement) => element.readyState)).toBe(2);
    expect(await video.evaluate((element: HTMLVideoElement) => element.textTracks[0].cues?.length)).toBe(2);
    expect(requested).toEqual([SUBTITLES_URL]);

    await expectPlays(page);
    await expect(page.getByText(SUBTITLES_FAILED)).toHaveCount(0);
  });

  test('vídeo sin CORS: se vuelve a montar sin crossorigin ni pista, se reproduce, avisa y retoma por donde iba', async ({
    page,
    request,
    user,
    signIn,
  }) => {
    await signIn(user);
    const movieId = await findMovieId(request, user.token, MOVIE_TITLE);
    // Iba por el segundo 30 de los 60 del clip: el segundo intento debe empezar ahí, no en el 0.
    await saveMovieProgressApi(request, user.token, movieId, 30, 60);
    await serveClip(page, { cors: false });
    await serveSubtitles(page);
    await withMedia(page, `/api/movies/${movieId}`, SUBTITLES_URL);

    await openPlayer(page, movieId);
    // El aviso (para un USUARIO, sin la pista técnica del administrador), sin pantalla de error.
    await expect(page.getByRole('status').getByText(SUBTITLES_FAILED, { exact: true })).toBeVisible();
    await expect(page.getByRole('heading', { name: 'No se pudo reproducir el vídeo' })).toHaveCount(0);

    const video = await loadedVideo(page);
    await expect(video).not.toHaveAttribute('crossorigin', /.*/);
    await expect(video.locator('track')).toHaveCount(0);
    await expect(page.getByText('Sigues por 0:30.')).toBeVisible();
    expect(await video.evaluate((element: HTMLVideoElement) => element.currentTime)).toBeGreaterThanOrEqual(29);
    await expectPlays(page);
    await expect(page.getByRole('heading', { name: 'No se pudo reproducir el vídeo' })).toHaveCount(0);
  });

  test('subtítulos que dan 404 (administrador): al activarlos avisa con la pista de dónde mirar y el vídeo sigue', async ({
    page,
    request,
    signIn,
  }) => {
    const adminToken = await loginAdmin(request);
    await signIn({ token: adminToken });
    const movieId = await findMovieId(request, adminToken, MOVIE_TITLE);
    await serveClip(page);
    const requested = await serveSubtitles(page, { status: 404 });
    await withMedia(page, `/api/movies/${movieId}`, SUBTITLES_URL);

    await openPlayer(page, movieId);
    const video = await loadedVideo(page);
    await expect(video).toHaveAttribute('crossorigin', 'anonymous');
    await expect(page.getByText(SUBTITLES_FAILED, { exact: false })).toHaveCount(0);

    await showSubtitles(page);
    await expect(page.getByRole('status').getByText(SUBTITLES_FAILED_ADMIN, { exact: true })).toBeVisible();
    expect(requested).toEqual([SUBTITLES_URL]);
    // La pista se quita; el vídeo es el mismo (no se ha vuelto a montar) y sigue reproduciéndose.
    await expect(video.locator('track')).toHaveCount(0);
    await expectPlays(page);
    await expect(page.getByRole('heading', { name: 'No se pudo reproducir el vídeo' })).toHaveCount(0);
  });
});
