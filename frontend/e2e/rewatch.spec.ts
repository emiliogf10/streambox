/**
 * E2E de «volver a ver ya no desmarca» (V15: `finished_at` y el campo
 * `watched`): algo terminado que se empieza otra vez sigue contando como visto
 * en el perfil (historial y cifras) y en la lista de episodios, mientras que el
 * botón y «Seguir viendo» siguen la posición ACTUAL (`finished`). Solo «Quitar
 * de Seguir viendo» lo borra todo.
 *
 * El progreso se prepara por la API (`PUT /api/users/me/progress/...`, lo mismo
 * que manda el reproductor): terminar el título y volver a verlo hasta un punto
 * menor. Lo que se comprueba es la interfaz con esos datos reales del servidor.
 * El vídeo se cambia por un archivo directo en la respuesta de la API
 * (`support/media.ts`): el progreso solo se enseña de lo que se ve en
 * StreamBox. Proyecto principal: no toca el catálogo y cada test usa su usuario.
 */
import { findMovieId, findSeriesIdAsAdmin, loginAdmin, saveEpisodeProgressApi, saveMovieProgressApi, seriesEpisodesApi } from './support/api';
import { escapeRegExp, expect, test } from './support/fixtures';
import { withMedia } from './support/media';

/** Película sembrada con vídeo. */
const MOVIE_TITLE = 'El marciano';

/** Miniserie sembrada (una temporada de cuatro episodios con vídeo). */
const SERIES_TITLE = 'Herederos del Silencio';

/** Duración que «manda el reproductor» en estos tests: 10 min, para leer «Quedan 9 min» al volver a verlo. */
const DURATION = 600;

test.describe('Volver a ver no desmarca lo visto', () => {
  test.beforeEach(async ({ user, signIn }) => {
    await signIn(user);
  });

  test('película: tras volver a verla, el perfil sigue diciendo «Visto», las cifras no bajan y «Quitar de Seguir viendo» lo borra todo', async ({
    page,
    request,
    user,
  }) => {
    const movieId = await findMovieId(request, user.token, MOVIE_TITLE);
    await withMedia(page, `/api/movies/${movieId}`);
    const stats = page.getByRole('region', { name: 'Resumen de lo que has visto' });
    const history = page.getByRole('region', { name: 'Vistos recientemente' });
    const moviesSeen = stats.locator('dt:text-is("Película vista") + dd');
    const time = stats.locator('dt:text-is("Tiempo visto (aprox.)") + dd');

    // Terminada: «Visto» sin barra, una película vista y sus 10 minutos.
    await saveMovieProgressApi(request, user.token, movieId, DURATION, DURATION);
    await page.goto('/perfil');
    await expect(history.getByRole('link', { name: new RegExp(`^${escapeRegExp(MOVIE_TITLE)}\\s+Visto$`) })).toBeVisible();
    await expect(moviesSeen).toHaveText('1');
    await expect(time).toContainText('10 minutos');

    // Vuelve a verla y para en el minuto 1: la posición baja, «visto» se queda.
    await saveMovieProgressApi(request, user.token, movieId, 60, DURATION);
    await page.reload();
    const card = history.getByRole('link', { name: new RegExp(`^${escapeRegExp(MOVIE_TITLE)}\\s+Visto\\s+Quedan 9 min$`) });
    await expect(card).toBeVisible();
    // La barra de la vez actual vuelve (oculta a los lectores de pantalla: su texto ya está escrito).
    await expect(card.locator('progress')).toHaveCount(1);
    await expect(moviesSeen).toHaveText('1');
    await expect(time).toContainText('10 minutos');

    // Su página: el botón sigue la posición («Reanudar», no «Volver a ver») y la barra dice las dos cosas.
    await page.goto(`/peliculas/${movieId}`);
    await expect(page.getByRole('link', { name: `Reanudar ${MOVIE_TITLE}` })).toBeVisible();
    await expect(page.getByRole('progressbar', { name: 'Visto · Quedan 9 min' })).toBeVisible();

    // En «Seguir viendo» está (a medias por posición). Quitarla borra también lo visto.
    await page.goto('/');
    const continueWatching = page.getByRole('region', { name: 'Seguir viendo' });
    await expect(continueWatching.getByRole('link', { name: new RegExp(`^Reanudar ${escapeRegExp(MOVIE_TITLE)}`) })).toBeVisible();
    await continueWatching.getByRole('button', { name: `Quitar ${MOVIE_TITLE} de Seguir viendo` }).click();
    await expect(page.getByText(`«${MOVIE_TITLE}» ya no está en Seguir viendo ni en lo que has visto.`, { exact: false })).toBeVisible();
    await expect(continueWatching).toHaveCount(0);

    await page.goto('/perfil');
    await expect(history.getByText('Aún no has visto nada')).toBeVisible();
    await expect(stats.locator('dt:text-is("Películas vistas") + dd')).toHaveText('0');
    await expect(time).toContainText('0 minutos');
  });

  test('episodio: la lista dice «Visto · Quedan…» con «Reanudar», el perfil lo sigue contando y quitar la serie lo borra', async ({
    page,
    request,
    user,
  }) => {
    const seriesId = await findSeriesIdAsAdmin(request, await loginAdmin(request), SERIES_TITLE);
    const [first] = (await seriesEpisodesApi(request, user.token, seriesId)).filter(
      (episode) => episode.seasonNumber === 1 && episode.episodeNumber === 1,
    );
    await withMedia(page, `/api/series/${seriesId}`);
    const stats = page.getByRole('region', { name: 'Resumen de lo que has visto' });

    await saveEpisodeProgressApi(request, user.token, first.id, DURATION, DURATION);
    await saveEpisodeProgressApi(request, user.token, first.id, 60, DURATION);

    await page.goto(`/series/${seriesId}`);
    await expect(page.getByRole('heading', { level: 1, name: SERIES_TITLE })).toBeVisible();
    const season = page.getByRole('list', { name: 'Episodios de la temporada 1' });
    const item = season.getByRole('listitem').filter({ hasText: first.title });
    await expect(item.getByRole('progressbar', { name: 'Visto · Quedan 9 min' })).toBeVisible();
    // El botón lo decide la posición: se reanuda, no se ofrece empezar de cero.
    await expect(item.getByRole('link', { name: `Reanudar T1:E1 ${first.title}` })).toBeVisible();
    // Y el botón principal de la serie propone ese mismo episodio a medias (no el siguiente).
    await expect(page.getByRole('link', { name: `Reanudar T1:E1 de ${SERIES_TITLE}: ${first.title}` })).toBeVisible();
    // Los demás episodios no tienen progreso.
    await expect(season.getByRole('progressbar')).toHaveCount(1);

    await page.goto('/perfil');
    await expect(stats.locator('dt:text-is("Episodio visto") + dd')).toHaveText('1');
    const history = page.getByRole('region', { name: 'Vistos recientemente' });
    await expect(history.getByRole('link', { name: new RegExp(`^${escapeRegExp(SERIES_TITLE)}\\s+T1:E1 · .*Visto\\s+Quedan 9 min$`) })).toBeVisible();

    await page.goto('/');
    const continueWatching = page.getByRole('region', { name: 'Seguir viendo' });
    await continueWatching.getByRole('button', { name: `Quitar ${SERIES_TITLE} de Seguir viendo` }).click();
    await expect(continueWatching).toHaveCount(0);
    await page.goto('/perfil');
    await expect(history.getByText('Aún no has visto nada')).toBeVisible();
    await expect(stats.locator('dt:text-is("Episodios vistos") + dd')).toHaveText('0');
  });
});
