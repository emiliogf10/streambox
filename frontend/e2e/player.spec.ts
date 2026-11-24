/**
 * E2E del reproductor integrado y de «Seguir viendo»: reproducir un vídeo de
 * verdad (el `<video>` de Chromium con `fixtures/clip.webm`, 60 s), que el
 * progreso se guarde en el servidor, reanudar desde su página y desde la fila
 * «Seguir viendo» del inicio, y quitarlo de esa fila. Con una película y con
 * un episodio (cuya tarjeta es de la serie). También el historial del perfil:
 * lo visto aparece en «Vistos recientemente» y cambia las cifras de lo visto.
 *
 * **Sin tocar el catálogo compartido.** Los vídeos sembrados son enlaces a
 * otra web (`https://videos.streambox.example/...`), que el reproductor no
 * abre. En vez de crear una película o editar una sembrada (cambiaría el banner
 * o los enlaces que comprueban otros specs), cada test intercepta en SU página
 * la respuesta real de `GET /api/movies/{id}` (o `/api/series/{id}`) y le pone
 * un `videoUrl` de archivo directo, que se sirve desde `fixtures/clip.webm`.
 * El backend no mira la terminación del vídeo, solo que lo haya (409
 * `NO_VIDEO_AVAILABLE` si no), así que el guardado, «Seguir viendo» y el
 * borrado son los reales. Cada test registra su propio usuario: su progreso no
 * se mezcla con el de los demás aunque corran en paralelo (proyecto principal).
 *
 * **Sin esperar en tiempo real.** Para llegar a los 10 s de reproducción que
 * hacen falta antes de guardar, el vídeo va a velocidad 4× (`playbackRate`):
 * cada aviso de `timeupdate` sigue siendo un avance pequeño y cuenta como
 * reproducción, no como salto.
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import type { Page, Response } from '@playwright/test';
import { findMovieId, findSeriesIdAsAdmin, loginAdmin } from './support/api';
import { escapeRegExp, expect, test } from './support/fixtures';

/** El vídeo de prueba (WebM, 60 s, sin sonido; ver `fixtures/generar-video.mjs`). */
const CLIP = readFileSync(fileURLToPath(new URL('./fixtures/clip.webm', import.meta.url)));

/** Dirección inventada del archivo de vídeo: nunca sale a Internet, la sirve {@link serveClip}. */
const CLIP_URL = 'https://media.streambox.example/e2e/clip.webm';

/** Película sembrada con vídeo (de otra web) y portada real. */
const MOVIE_TITLE = 'El marciano';

/** Miniserie sembrada (una temporada de cuatro episodios con vídeo). */
const SERIES_TITLE = 'Herederos del Silencio';

/**
 * Sirve {@link CLIP} en {@link CLIP_URL} como cualquier servidor de vídeos: con
 * peticiones parciales (`Range` → 206 con `Content-Range`). Sin ellas, Chromium
 * no deja saltar a otro segundo (ni a mano ni al reanudar: se quedaría en el 0).
 */
async function serveClip(page: Page): Promise<void> {
  await page.route(CLIP_URL, (route) => {
    const range = /^bytes=(\d+)-(\d*)$/.exec(route.request().headers()['range'] ?? '');
    const headers = { 'Accept-Ranges': 'bytes', 'Content-Type': 'video/webm' };
    if (!range) return route.fulfill({ status: 200, headers, body: CLIP });
    const start = Number(range[1]);
    const end = range[2] ? Math.min(Number(range[2]), CLIP.length - 1) : CLIP.length - 1;
    return route.fulfill({
      status: 206,
      headers: { ...headers, 'Content-Range': `bytes ${start}-${end}/${CLIP.length}` },
      body: CLIP.subarray(start, end + 1),
    });
  });
}

/** Respuesta real de `GET <path>` con el vídeo cambiado por {@link CLIP_URL} (en la película o en cada episodio). */
async function withClip(page: Page, path: string): Promise<void> {
  await page.route(`**${path}`, async (route) => {
    if (route.request().method() !== 'GET') return route.continue();
    const response = await route.fetch();
    const body = (await response.json()) as {
      videoUrl?: string | null;
      seasons?: { episodes: { videoUrl: string | null }[] }[];
    };
    if ('videoUrl' in body) body.videoUrl = CLIP_URL;
    for (const season of body.seasons ?? []) {
      for (const episode of season.episodes) episode.videoUrl = CLIP_URL;
    }
    await route.fulfill({ response, json: body });
  });
}

/** El `<video>` del reproductor, ya con sus metadatos (duración conocida). */
async function waitForVideo(page: Page) {
  const video = page.locator('video');
  await expect(video).toHaveAttribute('src', CLIP_URL);
  await expect.poll(() => video.evaluate((element: HTMLVideoElement) => element.readyState)).toBeGreaterThanOrEqual(1);
  return video;
}

/**
 * Reproduce a 4× hasta pasar los 12 s del vídeo y pausa, que es cuando se
 * guarda. Devuelve la respuesta del `PUT` de progreso (204).
 */
async function watchAndPause(page: Page, progressPath: RegExp): Promise<Response> {
  const video = await waitForVideo(page);
  await video.evaluate((element: HTMLVideoElement) => {
    element.muted = true;
    element.playbackRate = 4;
    void element.play();
  });
  await expect.poll(() => video.evaluate((element: HTMLVideoElement) => element.currentTime), { timeout: 15_000 }).toBeGreaterThan(12);
  const saved = page.waitForResponse((response) => response.request().method() === 'PUT' && progressPath.test(response.url()));
  await video.evaluate((element: HTMLVideoElement) => element.pause());
  return saved;
}

/** La sección «Seguir viendo» del inicio. */
function continueWatching(page: Page) {
  return page.getByRole('region', { name: 'Seguir viendo' });
}

test.describe('Reproductor y «Seguir viendo»', () => {
  test.beforeEach(async ({ page, user, signIn }) => {
    await signIn(user);
    await serveClip(page);
  });

  test('película: se guarda al pausar, la página dice «Reanudar», «Seguir viendo» reanuda donde se dejó y se puede quitar', async ({
    page,
    request,
    user,
  }) => {
    test.slow();
    const movieId = await findMovieId(request, user.token, MOVIE_TITLE);
    await withClip(page, `/api/movies/${movieId}`);

    // Sin nada a medias, el inicio no tiene «Seguir viendo».
    await page.goto('/');
    await expect(page.getByRole('region', { name: 'Novedades' })).toBeVisible();
    await expect(continueWatching(page)).toHaveCount(0);

    await page.goto(`/peliculas/${movieId}`);
    await page.getByRole('link', { name: `Ver ahora ${MOVIE_TITLE}` }).click();
    await expect(page.getByRole('heading', { level: 1, name: MOVIE_TITLE })).toBeVisible();

    const saved = await watchAndPause(page, new RegExp(`/api/users/me/progress/movies/${movieId}$`));
    expect(saved.status()).toBe(204);
    const sent = saved.request().postDataJSON() as { positionSeconds: number; durationSeconds: number };
    expect(sent.durationSeconds).toBe(60);
    expect(sent.positionSeconds).toBeGreaterThanOrEqual(12);

    // «Volver» lleva a la película, que ya ofrece reanudar (lo dice el servidor, también tras recargar).
    await page.getByRole('button', { name: 'Volver' }).click();
    await expect(page.getByRole('heading', { level: 1, name: MOVIE_TITLE })).toBeVisible();
    await page.reload();
    await expect(page.getByRole('link', { name: `Reanudar ${MOVIE_TITLE}` })).toBeVisible();
    await expect(page.getByRole('progressbar', { name: /^Queda/ })).toBeVisible();

    // En el inicio, la tarjeta de «Seguir viendo» lleva al reproductor y reanuda.
    await page.goto('/');
    const card = continueWatching(page).getByRole('link', { name: new RegExp(`^Reanudar ${escapeRegExp(MOVIE_TITLE)}`) });
    await card.click();
    const video = await waitForVideo(page);
    await expect(page.getByText(/^Sigues por 0:1\d\.$/)).toBeVisible();
    expect(await video.evaluate((element: HTMLVideoElement) => element.currentTime)).toBeGreaterThanOrEqual(sent.positionSeconds - 1);

    // Quitarla: desaparece la fila (era la única), se avisa y el servidor lo ha olvidado.
    await page.getByRole('button', { name: 'Volver' }).click();
    const removed = page.waitForResponse(
      (response) => response.request().method() === 'DELETE' && response.url().endsWith(`/continue-watching/movies/${movieId}`),
    );
    await continueWatching(page).getByRole('button', { name: `Quitar ${MOVIE_TITLE} de Seguir viendo` }).click();
    expect((await removed).status()).toBe(204);
    await expect(page.getByText(`«${MOVIE_TITLE}» ya no está en Seguir viendo ni en lo que has visto.`, { exact: false })).toBeVisible();
    await expect(continueWatching(page)).toHaveCount(0);
    await page.reload();
    await expect(page.getByRole('region', { name: 'Novedades' })).toBeVisible();
    await expect(continueWatching(page)).toHaveCount(0);
  });

  test('perfil: lo visto sale en «Vistos recientemente» (enlazado a su página) y las cifras pasan de cero a lo real', async ({
    page,
    request,
    user,
  }) => {
    test.slow();
    const movieId = await findMovieId(request, user.token, MOVIE_TITLE);
    await withClip(page, `/api/movies/${movieId}`);
    const stats = page.getByRole('region', { name: 'Resumen de lo que has visto' });
    const history = page.getByRole('region', { name: 'Vistos recientemente' });

    // Cuenta recién creada: ceros que dice el servidor (no una silueta) y el historial vacío.
    await page.goto('/perfil');
    await expect(history.getByText('Aún no has visto nada')).toBeVisible();
    await expect(stats.locator('dt:text-is("Películas vistas") + dd')).toHaveText('0');
    await expect(stats.locator('dt:text-is("Tiempo visto (aprox.)") + dd')).toContainText('0 minutos');

    // Más de 10 s de reproducción real (si no, no se guarda nada) y después, hasta el final: queda terminada.
    await page.goto(`/peliculas/${movieId}`);
    await page.getByRole('link', { name: `Ver ahora ${MOVIE_TITLE}` }).click();
    await expect(page.getByRole('heading', { level: 1, name: MOVIE_TITLE })).toBeVisible();
    const progressPath = new RegExp(`/api/users/me/progress/movies/${movieId}$`);
    expect((await watchAndPause(page, progressPath)).status()).toBe(204);
    const finished = page.waitForResponse((response) => {
      if (response.request().method() !== 'PUT' || !progressPath.test(response.url())) return false;
      const sent = response.request().postDataJSON() as { positionSeconds: number; durationSeconds: number };
      return sent.positionSeconds === sent.durationSeconds;
    });
    await page.locator('video').evaluate((element: HTMLVideoElement) => {
      element.currentTime = element.duration - 0.5;
      void element.play();
    });
    expect((await finished).status()).toBe(204);

    // Al perfil sin recargar (como un usuario): «Volver» y el menú de usuario.
    await page.getByRole('button', { name: 'Volver' }).click();
    await expect(page.getByRole('heading', { level: 1, name: MOVIE_TITLE })).toBeVisible();
    await page.getByRole('button', { name: 'Menú de usuario' }).click();
    await page.getByRole('link', { name: 'Mi perfil' }).click();

    // La tarjeta lleva a la página de la película (no al reproductor) y dice «Visto», sin barra.
    const card = history.getByRole('link', { name: new RegExp(`^${escapeRegExp(MOVIE_TITLE)}\\s+Visto$`) });
    await expect(card).toBeVisible();
    await expect(card).toHaveAttribute('href', `/peliculas/${movieId}`);
    await expect(card.locator('progress')).toHaveCount(0);
    await expect(history.getByText('1 título · lo más reciente primero')).toBeVisible();

    // Las cifras: una película vista (en singular) y el minuto del clip (60 s terminados).
    await expect(stats.locator('dt:text-is("Película vista") + dd')).toHaveText('1');
    await expect(stats.locator('dt:text-is("Episodios vistos") + dd')).toHaveText('0');
    await expect(stats.locator('dt:text-is("Tiempo visto (aprox.)") + dd')).toContainText('1 minuto');

    await card.click();
    await expect(page).toHaveURL(new RegExp(`/peliculas/${movieId}$`));
    await expect(page.getByRole('heading', { level: 1, name: MOVIE_TITLE })).toBeVisible();
  });

  test('menos de 10 s de reproducción no se guardan: abrir un vídeo por error no llena «Seguir viendo»', async ({
    page,
    request,
    user,
  }) => {
    const movieId = await findMovieId(request, user.token, MOVIE_TITLE);
    await withClip(page, `/api/movies/${movieId}`);
    const puts: string[] = [];
    page.on('request', (sent) => {
      if (sent.method() === 'PUT' && sent.url().includes('/progress/')) puts.push(sent.url());
    });

    await page.goto(`/peliculas/${movieId}`);
    await page.getByRole('link', { name: `Ver ahora ${MOVIE_TITLE}` }).click();
    const video = await waitForVideo(page);
    await video.evaluate((element: HTMLVideoElement) => {
      element.muted = true;
      void element.play();
    });
    await expect.poll(() => video.evaluate((element: HTMLVideoElement) => element.currentTime)).toBeGreaterThan(1);
    await video.evaluate((element: HTMLVideoElement) => element.pause());
    await page.getByRole('button', { name: 'Volver' }).click();
    await expect(page.getByRole('link', { name: `Ver ahora ${MOVIE_TITLE}` })).toBeVisible();

    expect(puts).toEqual([]);
  });

  test('episodio: la serie ofrece «Reanudar T1:E1» con su barra, la tarjeta es de la serie y quitarla borra el progreso de la serie', async ({
    page,
    request,
  }) => {
    test.slow();
    const seriesId = await findSeriesIdAsAdmin(request, await loginAdmin(request), SERIES_TITLE);
    await withClip(page, `/api/series/${seriesId}`);

    await page.goto(`/series/${seriesId}`);
    await expect(page.getByRole('heading', { level: 1, name: SERIES_TITLE })).toBeVisible();
    await page.getByRole('link', { name: /^Empezar a ver / }).click();
    await expect(page.getByRole('heading', { level: 1, name: SERIES_TITLE })).toBeVisible();
    await expect(page.getByText('T1:E1 · Capítulo 1.1')).toBeVisible();

    const saved = await watchAndPause(page, /\/api\/users\/me\/progress\/episodes\/\d+$/);
    expect(saved.status()).toBe(204);

    await page.getByRole('button', { name: 'Volver' }).click();
    await expect(page.getByRole('link', { name: `Reanudar T1:E1 de ${SERIES_TITLE}: Capítulo 1.1` })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Reanudar T1:E1 Capítulo 1.1' })).toBeVisible();
    await expect(page.getByRole('progressbar', { name: /^Queda/ })).toBeVisible();

    await page.goto('/');
    const card = continueWatching(page).getByRole('link', { name: new RegExp(`^Reanudar ${escapeRegExp(SERIES_TITLE)}`) });
    await expect(card).toContainText('T1:E1');
    const removed = page.waitForResponse(
      (response) => response.request().method() === 'DELETE' && response.url().endsWith(`/continue-watching/series/${seriesId}`),
    );
    await continueWatching(page).getByRole('button', { name: `Quitar ${SERIES_TITLE} de Seguir viendo` }).click();
    expect((await removed).status()).toBe(204);
    await expect(continueWatching(page)).toHaveCount(0);

    // La serie vuelve a empezar por el principio.
    await page.goto(`/series/${seriesId}`);
    await expect(page.getByRole('link', { name: /^Empezar a ver / })).toBeVisible();
    await expect(page.getByRole('progressbar')).toHaveCount(0);
  });

  /**
   * Decisión QA-4: la tarjeta de una serie y el botón de su página proponen lo
   * mismo. Terminado el T1:E1 (el último que se tocó), los dos llevan al T1:E2,
   * que aún no se ha empezado: la tarjeta dice «Siguiente episodio» y su enlace
   * empieza por «Ver» (no «Reanudar»: el reproductor empezará en el 0).
   */
  test('episodio terminado: la tarjeta de «Seguir viendo» y la serie proponen el siguiente («Ver T1:E2»)', async ({
    page,
    request,
  }) => {
    test.slow();
    const seriesId = await findSeriesIdAsAdmin(request, await loginAdmin(request), SERIES_TITLE);
    await withClip(page, `/api/series/${seriesId}`);

    await page.goto(`/series/${seriesId}`);
    await page.getByRole('link', { name: /^Empezar a ver / }).click();
    await expect(page.getByText('T1:E1 · Capítulo 1.1')).toBeVisible();

    // 10 s de reproducción real (si no, no se guarda nada) y después, al final: se guarda como terminado.
    const video = await waitForVideo(page);
    await video.evaluate((element: HTMLVideoElement) => {
      element.muted = true;
      element.playbackRate = 4;
      void element.play();
    });
    await expect.poll(() => video.evaluate((element: HTMLVideoElement) => element.currentTime), { timeout: 15_000 }).toBeGreaterThan(12);
    const finished = page.waitForResponse((response) => {
      if (response.request().method() !== 'PUT' || !/\/progress\/episodes\/\d+$/.test(response.url())) return false;
      const sent = response.request().postDataJSON() as { positionSeconds: number; durationSeconds: number };
      return sent.positionSeconds === sent.durationSeconds;
    });
    await video.evaluate((element: HTMLVideoElement) => {
      element.currentTime = element.duration - 0.5;
    });
    expect((await finished).status()).toBe(204);

    await page.goto('/');
    const card = continueWatching(page).getByRole('link', {
      name: new RegExp(`^Ver ${escapeRegExp(SERIES_TITLE)} T1:E2 · Siguiente episodio`),
    });
    await expect(card).toBeVisible();
    await expect(continueWatching(page).getByRole('link', { name: /^Reanudar / })).toHaveCount(0);
    const cardTarget = await card.getAttribute('href');
    expect(cardTarget).toMatch(new RegExp(`^/ver/series/${seriesId}/episodios/\\d+$`));

    await page.goto(`/series/${seriesId}`);
    const next = page.getByRole('link', { name: `Ver T1:E2 de ${SERIES_TITLE}: Capítulo 1.2` });
    await expect(next).toBeVisible();
    await expect(next).toHaveAttribute('href', cardTarget ?? '');
  });

  /**
   * Incidencia QA-1, en un navegador de verdad (con `BrowserRouter` y su
   * historial real, que el `MemoryRouter` de Vitest no tiene): el episodio se
   * abre en una pestaña NUEVA, sin nada detrás. «Ver el siguiente» sustituye la
   * entrada (`replace`) y le da otra clave; antes, «Volver» lo tomaba por una
   * pantalla de StreamBox y hacía «Atrás», que dejaba la pestaña en `about:blank`.
   */
  test('episodio abierto en una pestaña nueva: tras «Ver el siguiente», «Volver» lleva a la serie y no fuera de StreamBox', async ({
    page,
    request,
  }) => {
    test.slow();
    const seriesId = await findSeriesIdAsAdmin(request, await loginAdmin(request), SERIES_TITLE);
    // Solo para saber la dirección del reproductor del primer episodio (con el vídeo cambiado, el botón lleva a él).
    await withClip(page, `/api/series/${seriesId}`);
    await page.goto(`/series/${seriesId}`);
    const playerPath = await page.getByRole('link', { name: /^Empezar a ver / }).getAttribute('href');
    expect(playerPath).toMatch(/^\/ver\/series\/\d+\/episodios\/\d+$/);

    // Pestaña nueva de la misma sesión (comparte las cookies): su historial empieza en el reproductor.
    const tab = await page.context().newPage();
    await serveClip(tab);
    await withClip(tab, `/api/series/${seriesId}`);
    await tab.goto(playerPath ?? '');
    await expect(tab.getByText('T1:E1 · Capítulo 1.1')).toBeVisible();

    // Hasta el final del episodio (el último medio segundo a 4×).
    const video = await waitForVideo(tab);
    await video.evaluate((element: HTMLVideoElement) => {
      element.muted = true;
      element.playbackRate = 4;
      element.currentTime = element.duration - 0.5;
      void element.play();
    });
    await tab.getByRole('link', { name: /^Ver el siguiente: T1:E2 / }).click();
    await expect(tab.getByText(/^T1:E2 · /)).toBeVisible();

    await tab.getByRole('button', { name: 'Volver' }).click();

    await expect(tab).toHaveURL(new RegExp(`/series/${seriesId}$`));
    // La página de la serie ya está pintada: el reproductor (con su «Volver») ha desaparecido y está la ficha.
    await expect(tab.getByRole('button', { name: 'Volver' })).toHaveCount(0);
    await expect(tab.getByRole('heading', { level: 1, name: SERIES_TITLE })).toBeVisible();
    await tab.close();
  });
});
