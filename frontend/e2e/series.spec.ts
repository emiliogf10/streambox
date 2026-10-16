/**
 * E2E de las series (lo que ve un usuario): la sección `/series`, la página de
 * una serie con su selector de temporada en la URL, «Ver» de cada episodio,
 * «Mi lista» con su sección de series, el buscador mixto y la fila «Series» de
 * la portada. Y, en todos esos sitios, que la serie SIN episodios no aparece.
 *
 * Usa las series sembradas en `support/catalog.ts` (`SERIES`): la más reciente
 * con episodios es «Crónicas del Faro» (2 temporadas: 3 + 2 episodios) y la
 * última creada, «Serie Fantasma Sin Episodios», no tiene ninguno.
 */
import type { Locator, Page } from '@playwright/test';
import { addSeriesFavoritesByTitle, findSeriesIdAsAdmin, genreIdByName, loginAdmin } from './support/api';
import {
  EMPTY_SERIES,
  episodeTitle,
  episodeVideoUrl,
  SERIES_HERO,
  VISIBLE_SERIES,
} from './support/catalog';
import { escapeRegExp, expect, test } from './support/fixtures';

/**
 * Vídeo de un episodio de la serie del banner. Este spec comprueba «Ver» y «Empezar a ver», así que esa
 * serie debe sembrarse CON vídeos; si alguien se los quita, que falle aquí con un mensaje claro.
 */
function heroEpisodeVideo(season: number, episode: number): string {
  const url = episodeVideoUrl(SERIES_HERO, season, episode);
  if (!url) throw new Error(`«${SERIES_HERO.title}» debe sembrarse con vídeos en sus episodios`);
  return url;
}

/** Tarjeta (enlace) de una serie: «Título 2019–2022 · 2 temporadas». */
function seriesCard(scope: Page | Locator, title: string) {
  return scope.getByRole('link', { name: new RegExp(`^${escapeRegExp(title)} \\d{4}`) });
}

/** Abre la página de la serie del banner de `/series` y espera a que esté cargada. */
async function openHeroSeries(page: Page) {
  await page.goto('/series');
  await page.getByRole('region', { name: SERIES_HERO.title }).getByRole('link', { name: /^Ver episodios/ }).click();
  await expect(page.getByRole('heading', { level: 1, name: SERIES_HERO.title })).toBeVisible();
}

test.describe('Series', () => {
  test.beforeEach(async ({ user, signIn }) => {
    await signIn(user);
  });

  test('«Series» de la barra lleva a /series: banner con la más reciente, filas y total', async ({ page }) => {
    await page.goto('/');
    const nav = page.getByRole('navigation', { name: 'Principal' });
    await nav.getByRole('link', { name: 'Series' }).click();

    await expect(page).toHaveURL(/\/series$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Series' })).toBeVisible();
    await expect(nav.getByRole('link', { name: 'Series' })).toHaveAttribute('aria-current', 'page');

    const banner = page.getByRole('region', { name: SERIES_HERO.title });
    await expect(banner).toBeVisible();
    await expect(banner.getByText('Novedad')).toBeVisible();
    await expect(banner.getByRole('list', { name: 'Datos de la serie' })).toContainText('2 temporadas');
    // Las demás, en «Novedades» (sin repetir la del banner) y en la fila de su género común.
    const news = page.getByRole('region', { name: 'Novedades' });
    for (const series of VISIBLE_SERIES.filter((item) => item !== SERIES_HERO)) {
      await expect(seriesCard(news, series.title)).toBeVisible();
    }
    await expect(seriesCard(news, SERIES_HERO.title)).toHaveCount(0);
    await expect(page.getByRole('region', { name: 'Drama', exact: true })).toBeVisible();
    await expect(page.getByText(`Mostrando ${VISIBLE_SERIES.length} de ${VISIBLE_SERIES.length} series`)).toBeVisible();
  });

  test('una serie con portada inexistente muestra el hueco de respaldo, no una imagen rota', async ({ page }) => {
    await page.goto('/series');
    // «Estación Polar» se sembró con una portada que no existe (404).
    const card = seriesCard(page.getByRole('region', { name: 'Novedades' }), 'Estación Polar');
    await expect(card).toBeVisible();
    await expect(card.locator('img')).toHaveCount(0);
    await expect(card.getByText('Estación Polar')).toHaveCount(2); // en el hueco y en el pie
    // Y está en emisión: «2023–» en su tarjeta.
    await expect(card).toContainText('2023– · 1 temporada');
  });

  test('abrir una serie y cambiar de temporada: la temporada va en la URL y cambia la lista', async ({ page }) => {
    await openHeroSeries(page);
    await expect(page).toHaveURL(/\/series\/\d+$/);

    // Por defecto, la primera temporada.
    const picker = page.getByRole('navigation', { name: 'Temporadas' });
    await expect(picker.getByRole('link', { name: 'Temporada 1' })).toHaveAttribute('aria-current', 'true');
    await expect(page.getByText(`Temporada 1 · ${SERIES_HERO.seasons[0]} episodios`)).toBeVisible();
    const season1 = page.getByRole('list', { name: 'Episodios de la temporada 1' });
    await expect(season1.getByRole('heading', { level: 3 })).toHaveText(
      Array.from({ length: SERIES_HERO.seasons[0] }, (_, i) => `${i + 1}. ${episodeTitle(1, i + 1)}`),
    );

    await picker.getByRole('link', { name: 'Temporada 2' }).click();

    await expect(page).toHaveURL(/\/series\/\d+\?temporada=2$/);
    await expect(picker.getByRole('link', { name: 'Temporada 2' })).toHaveAttribute('aria-current', 'true');
    await expect(page.getByText(`Temporada 2 · ${SERIES_HERO.seasons[1]} episodios`)).toBeVisible();
    await expect(page.getByRole('list', { name: 'Episodios de la temporada 2' }).getByRole('listitem')).toHaveCount(
      SERIES_HERO.seasons[1],
    );

    // Recargar conserva la temporada (vive en la URL).
    await page.reload();
    await expect(page.getByRole('list', { name: 'Episodios de la temporada 2' })).toBeVisible();
  });

  test('«Ver» de un episodio abre su vídeo en otra pestaña con nombre único', async ({ page }) => {
    await openHeroSeries(page);

    const list = page.getByRole('list', { name: 'Episodios de la temporada 1' });
    const watch = list.getByRole('link', { name: `Ver T1:E3 ${episodeTitle(1, 3)} (se abre en una pestaña nueva)` });
    await expect(watch).toHaveAttribute('href', heroEpisodeVideo(1, 3));
    await expect(watch).toHaveAttribute('target', '_blank');
    await expect(watch).toHaveAttribute('rel', /noopener/);

    // «Empezar a ver» de la cabecera apunta al primer episodio de la primera temporada.
    await expect(page.getByRole('link', { name: /^Empezar a ver/ })).toHaveAttribute(
      'href',
      heroEpisodeVideo(1, 1),
    );
  });

  test('el selector de temporada se maneja con teclado (Tab + Intro) y el foco se queda en él', async ({ page }) => {
    await openHeroSeries(page);
    const picker = page.getByRole('navigation', { name: 'Temporadas' });

    await picker.getByRole('link', { name: 'Temporada 1' }).focus();
    await page.keyboard.press('Tab');
    const second = picker.getByRole('link', { name: 'Temporada 2' });
    await expect(second).toBeFocused();
    await page.keyboard.press('Enter');

    await expect(page).toHaveURL(/\?temporada=2$/);
    await expect(page.getByRole('list', { name: 'Episodios de la temporada 2' })).toBeVisible();
    await expect(second).toBeFocused();
  });

  test('una temporada inválida en la URL muestra la primera', async ({ page }) => {
    await openHeroSeries(page);
    await page.goto(`${new URL(page.url()).pathname}?temporada=99`);

    await expect(page.getByRole('list', { name: 'Episodios de la temporada 1' })).toBeVisible();
  });

  test('añadir una serie a «Mi lista», verla en su sección y quitarla', async ({ page }) => {
    await openHeroSeries(page);

    await page.getByRole('button', { name: /^Mi lista/ }).click();
    await expect(page.getByText(`«${SERIES_HERO.title}» se ha añadido a tu lista.`)).toBeVisible();
    await expect(page.getByRole('button', { name: /^En mi lista/ })).toBeVisible();

    await page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Mi lista' }).click();
    const seriesSection = page.getByRole('region', { name: 'Series' });
    await expect(seriesCard(seriesSection, SERIES_HERO.title)).toBeVisible();
    // La sección de películas existe y dice que está vacía.
    await expect(page.getByRole('region', { name: 'Películas' }).getByText('Todavía no has guardado ninguna película.')).toBeVisible();

    // Persistencia real: sigue tras recargar.
    await page.reload();
    await expect(seriesCard(seriesSection, SERIES_HERO.title)).toBeVisible();

    // La tarjeta lleva a la serie; desde allí se quita.
    await seriesCard(seriesSection, SERIES_HERO.title).click();
    await page.getByRole('button', { name: /^En mi lista/ }).click();
    await expect(page.getByText(`«${SERIES_HERO.title}» se ha quitado de tu lista.`)).toBeVisible();
    await page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name: 'Mi lista' }).click();
    await expect(page.getByRole('heading', { level: 2, name: 'Tu lista está vacía' })).toBeVisible();
  });

  test('«Vaciar lista» quita también las series', async ({ page, request, user }) => {
    await addSeriesFavoritesByTitle(request, user.token, [SERIES_HERO.title, 'Estación Polar']);
    await page.goto('/favorites');

    await page.getByRole('button', { name: 'Vaciar lista' }).click();
    const confirm = page.getByRole('alertdialog', { name: '¿Vaciar tu lista?' });
    await expect(confirm).toContainText('Se quitarán las 2 series de Mi lista.');
    await confirm.getByRole('button', { name: 'Sí, vaciar lista' }).click();

    await expect(page.getByRole('heading', { level: 2, name: 'Tu lista está vacía' })).toBeVisible();
    await page.reload();
    await expect(page.getByRole('heading', { level: 2, name: 'Tu lista está vacía' })).toBeVisible();
  });

  test('el buscador encuentra una serie en su grupo y lleva a su página', async ({ page }) => {
    await page.goto('/');
    const search = page.getByRole('combobox', { name: 'Buscar películas y series por título' });
    await search.fill('faro');

    const list = page.getByRole('listbox', { name: 'Resultados de la búsqueda' });
    const group = list.getByRole('group', { name: 'Series' });
    await expect(group.getByRole('option', { name: new RegExp(SERIES_HERO.title) })).toBeVisible();
    await expect(list.getByRole('group', { name: 'Películas' })).toHaveCount(0);
    await expect(page.getByRole('status').filter({ hasText: '1 resultado. 1 serie.' })).toHaveCount(1);

    await search.press('ArrowDown');
    await search.press('Enter');

    await expect(page).toHaveURL(/\/series\/\d+$/);
    await expect(page.getByRole('heading', { level: 1, name: SERIES_HERO.title })).toBeVisible();
    await expect(search).toHaveValue('');
  });

  test('la portada tiene la fila «Series» con las más recientes y «Ver todas» lleva a /series', async ({ page }) => {
    await page.goto('/');
    const row = page.getByRole('region', { name: 'Series', exact: true });
    await expect(row).toBeVisible();
    // La primera tarjeta es la más reciente con episodios.
    await expect(row.getByRole('listitem').first().getByRole('link')).toHaveAccessibleName(
      new RegExp(`^${escapeRegExp(SERIES_HERO.title)}`),
    );
    for (const series of VISIBLE_SERIES) {
      await expect(seriesCard(row, series.title)).toBeVisible();
    }

    await seriesCard(row, SERIES_HERO.title).click();
    await expect(page.getByRole('heading', { level: 1, name: SERIES_HERO.title })).toBeVisible();

    await page.goBack();
    await page.getByRole('region', { name: 'Series', exact: true }).getByRole('link', { name: /^Ver todas/ }).click();
    await expect(page).toHaveURL(/\/series$/);
  });

  test('la serie SIN episodios no aparece en ningún sitio y su página da «Serie no encontrada»', async ({
    page,
    request,
  }) => {
    // Ni en /series (sería el banner: es la última creada)...
    await page.goto('/series');
    await expect(page.getByRole('region', { name: SERIES_HERO.title })).toBeVisible();
    await expect(page.getByRole('main').getByText(EMPTY_SERIES.title)).toHaveCount(0);

    // ...ni en la fila de la portada...
    await page.goto('/');
    await expect(page.getByRole('region', { name: 'Series', exact: true })).toBeVisible();
    await expect(page.getByRole('main').getByText(EMPTY_SERIES.title)).toHaveCount(0);

    // ...ni en el buscador...
    await page.getByRole('combobox', { name: 'Buscar películas y series por título' }).fill('fantasma');
    await expect(page.getByText('Sin resultados para «fantasma».')).toBeVisible();

    // ...y su URL directa es una página «no encontrada» con salida a /series.
    const emptyId = await findSeriesIdAsAdmin(request, await loginAdmin(request), EMPTY_SERIES.title);
    await page.goto(`/series/${emptyId}`);
    await expect(page.getByRole('heading', { level: 1, name: 'Serie no encontrada' })).toBeVisible();
    await page.getByRole('link', { name: 'Ver todas las series' }).click();
    await expect(page).toHaveURL(/\/series$/);
  });
});

/**
 * Filtros de `/series` (los mismos que `/peliculas`: `useCatalogFilters`), con
 * el servidor de verdad: `GET /api/series/search` con `genreId`, `releaseYear`,
 * `sort` y `direction`. Los recuentos salen de `SERIES` (`support/catalog.ts`)
 * y nunca cuentan la serie sin episodios, aunque coincida con el filtro.
 */
test.describe('Series: filtros', () => {
  test.beforeEach(async ({ user, signIn }) => {
    await signIn(user);
  });

  /** Controles de la barra de filtros de `/series`. */
  function filterBar(page: Page) {
    const form = page.getByRole('form', { name: 'Filtrar series' });
    return {
      genre: form.getByRole('combobox', { name: 'Género' }),
      year: form.getByRole('textbox', { name: 'Año' }),
      sort: form.getByRole('combobox', { name: 'Ordenar por' }),
      clear: form.getByRole('button', { name: 'Quitar filtros' }),
    };
  }

  /** Visibles que cumplen la condición, en el orden por defecto («Más recientes»: el inverso al de creación). */
  function newestFirstWhere(predicate: (series: (typeof VISIBLE_SERIES)[number]) => boolean): string[] {
    return VISIBLE_SERIES.filter(predicate)
      .map((series) => series.title)
      .reverse();
  }

  /** La cuadrícula muestra EXACTAMENTE estas series, en este orden, con su recuento. */
  async function expectResults(page: Page, titles: readonly string[]) {
    const results = page.getByRole('region', { name: 'Resultados' });
    await expect(results.getByRole('status').first()).toHaveText(titles.length === 1 ? '1 serie' : `${titles.length} series`);
    const cards = results.getByRole('listitem');
    await expect(cards).toHaveCount(titles.length);
    await expect(cards).toContainText(titles);
  }

  test('género y después año: la URL los acumula, los resultados son exactos (sin la serie sin episodios) y «Atrás» deshace cada paso', async ({
    page,
    request,
    user,
  }) => {
    const dramaId = await genreIdByName(request, user.token, 'Drama');
    await page.goto('/series');
    await expect(page.getByRole('region', { name: SERIES_HERO.title })).toBeVisible();
    const bar = filterBar(page);

    await bar.genre.selectOption({ label: 'Drama' });
    await expect(page).toHaveURL(new RegExp(`/series\\?genero=${dramaId}$`));
    // «Serie Fantasma Sin Episodios» también es de Drama, pero no se ve.
    await expectResults(page, newestFirstWhere((series) => series.genres.includes('Drama')));
    await expect(page.getByRole('main').getByText(EMPTY_SERIES.title)).toHaveCount(0);

    await bar.year.fill('2021');
    await expect(page).toHaveURL(new RegExp(`/series\\?genero=${dramaId}&anio=2021$`));
    await expectResults(page, ['Herederos del Silencio']);

    await page.goBack();
    await expect(page).toHaveURL(new RegExp(`/series\\?genero=${dramaId}$`));
    await expect(bar.year).toHaveValue('');
    await expectResults(page, newestFirstWhere((series) => series.genres.includes('Drama')));

    await page.goBack();
    await expect(page).toHaveURL(/\/series$/);
    await expect(page.getByRole('region', { name: SERIES_HERO.title })).toBeVisible();
  });

  test('ordenar por año (más antiguas) pide el orden al servidor y no ofrece órdenes por duración', async ({ page }) => {
    await page.goto('/series');
    const bar = filterBar(page);
    await expect(bar.sort.getByRole('option')).toHaveText([
      'Más recientes',
      'Título A–Z',
      'Título Z–A',
      'Año: más nuevas',
      'Año: más antiguas',
    ]);

    await bar.sort.selectOption({ label: 'Año: más antiguas' });

    await expect(page).toHaveURL(/\/series\?orden=anio-asc$/);
    const byYear = [...VISIBLE_SERIES].sort((a, b) => a.releaseYear - b.releaseYear).map((series) => series.title);
    await expectResults(page, byYear);
  });

  test('sin resultados (2025: solo la serie sin episodios): «No hay series con estos filtros» y «Quitar filtros» vuelve al banner', async ({
    page,
  }) => {
    expect(EMPTY_SERIES.releaseYear).toBe(2025);
    await page.goto('/series?anio=2025');

    await expect(page.getByRole('heading', { level: 2, name: 'No hay series con estos filtros' })).toBeVisible();
    await expect(page.getByText('Prueba con otro género u otro año, o quita los filtros.')).toBeVisible();
    // El del estado vacío (el último: el de la barra va antes en la página).
    await page.getByRole('main').getByRole('button', { name: 'Quitar filtros' }).last().click();

    await expect(page).toHaveURL(/\/series$/);
    await expect(page.getByRole('region', { name: SERIES_HERO.title })).toBeVisible();
    await expect(filterBar(page).genre).toBeFocused();
  });

  /**
   * Los géneros de la ficha de una serie llevan a `/series` filtrada (no a
   * películas). El nombre accesible se comprueba EXACTO en Chromium real: con
   * un `<span class="sr-only">` saldría «Acción , ver todas…» (ver
   * `GenreLinks` y el test equivalente de `movies.spec.ts`).
   */
  test('un género de la ficha de una serie lleva a /series con ese filtro; su nombre accesible no tiene espacio ante la coma', async ({
    page,
    request,
    user,
  }) => {
    const actionId = await genreIdByName(request, user.token, 'Acción');
    await openHeroSeries(page);
    const link = page.getByRole('region', { name: 'Ficha' }).getByRole('link', { name: /^Acción\b/ });
    await expect(link).toHaveAccessibleName('Acción, ver todas las series del género');

    await link.click();

    await expect(page).toHaveURL(new RegExp(`/series\\?genero=${actionId}$`));
    await expect(filterBar(page).genre).toHaveValue(String(actionId));
    await expectResults(page, newestFirstWhere((series) => series.genres.includes('Acción')));
  });
});
