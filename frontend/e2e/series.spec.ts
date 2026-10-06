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
import { addSeriesFavoritesByTitle, findSeriesIdAsAdmin, loginAdmin } from './support/api';
import {
  EMPTY_SERIES,
  episodeTitle,
  episodeVideoUrl,
  SERIES_HERO,
  VISIBLE_SERIES,
} from './support/catalog';
import { escapeRegExp, expect, test } from './support/fixtures';

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
    await expect(watch).toHaveAttribute('href', episodeVideoUrl(SERIES_HERO, 1, 3));
    await expect(watch).toHaveAttribute('target', '_blank');
    await expect(watch).toHaveAttribute('rel', /noopener/);

    // «Empezar a ver» de la cabecera apunta al primer episodio de la primera temporada.
    await expect(page.getByRole('link', { name: /^Empezar a ver/ })).toHaveAttribute(
      'href',
      episodeVideoUrl(SERIES_HERO, 1, 1),
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
