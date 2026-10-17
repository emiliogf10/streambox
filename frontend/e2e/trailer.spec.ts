/**
 * E2E de «Ver tráiler» y de que ningún botón diga «Ver» sin vídeo de verdad.
 *
 * Con el catálogo sembrado (`support/catalog.ts`) cubre las combinaciones de
 * vídeo y tráiler, que son opcionales:
 *  - **Banner de la portada** («Dune: Parte Dos», vídeo + tráiler): «Ver ahora»
 *    principal y «Ver tráiler» secundaria; el diálogo carga el reproductor sin
 *    cookies, «Ver en YouTube» y Escape (que lo cierra, quita el `<iframe>` y
 *    devuelve el foco). También a 375 px: el vídeo cabe y es 16:9.
 *  - **«Tenet»** (solo tráiler, sembrado con la forma corta `youtu.be`): sin
 *    «Ver ahora»; «Ver tráiler» es la principal y su `<iframe>` usa el id que el
 *    servidor extrajo al NORMALIZAR el enlace.
 *  - **«Noches de Invierno»** (ni vídeo ni tráiler): ningún botón promete ver nada.
 *  - **Series**: el tráiler de «Crónicas del Faro» en el banner de `/series` y en
 *    su página; «Estación Polar» (episodios sin vídeo): sin «Empezar a ver» ni «Ver».
 *
 * YouTube no se carga nunca: las peticiones a `youtube-nocookie.com` se
 * responden con una página vacía (`page.route`). Los ids de los tráileres son
 * inventados; aquí se comprueba qué pide la página, no el vídeo.
 */
import type { Page } from '@playwright/test';
import {
  HERO_TITLE,
  HERO_TRAILER_ID,
  MOVIES,
  normalizedTrailer,
  SERIES_HERO,
  SERIES_TRAILER_ID,
  TENET_TRAILER_ID,
  trailerEmbedUrl,
} from './support/catalog';
import { escapeRegExp, expect, movieCard, test } from './support/fixtures';

/** Abre la página de una película del catálogo sembrado (filtrando por su año, para no paginar). */
async function openMovie(page: Page, title: string): Promise<void> {
  const movie = MOVIES.find((candidate) => candidate.title === title);
  if (!movie) throw new Error(`«${title}» no está en el catálogo sembrado`);
  await page.goto(`/peliculas?anio=${movie.releaseYear}`);
  await movieCard(page.getByRole('region', { name: 'Resultados' }), title).click();
  await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible();
}

/** Abre la página de una serie desde las «Novedades» o el banner de `/series`. */
async function openSeries(page: Page, title: string): Promise<void> {
  await page.goto('/series');
  if (title === SERIES_HERO.title) {
    await page.getByRole('region', { name: title }).getByRole('link', { name: /^Más información/ }).click();
  } else {
    await page
      .getByRole('region', { name: 'Novedades' })
      .getByRole('link', { name: new RegExp(`^${escapeRegExp(title)} \\d{4}`) })
      .click();
  }
  await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible();
}

test.describe('Tráileres y «Ver» solo con vídeo', () => {
  test.beforeEach(async ({ page, user, signIn }) => {
    await page.route('https://www.youtube-nocookie.com/**', (route) =>
      route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Reproductor de prueba</title>' }),
    );
    await signIn(user);
  });

  test('banner de la portada: «Ver ahora» principal, «Ver tráiler» abre el reproductor y Escape lo cierra devolviendo el foco', async ({
    page,
  }) => {
    await page.goto('/');
    const banner = page.getByRole('region', { name: HERO_TITLE });
    const watch = banner.getByRole('link', { name: new RegExp(`^Ver ahora ${escapeRegExp(HERO_TITLE)}`) });
    const trailerButton = banner.getByRole('button', { name: `Ver tráiler de ${HERO_TITLE}` });
    await expect(watch).toBeVisible();
    await expect(trailerButton).toBeVisible();
    await expect(trailerButton).toHaveAttribute('aria-haspopup', 'dialog');
    // Antes de pedirlo, nada de YouTube en la página.
    await expect(page.locator('iframe')).toHaveCount(0);

    await trailerButton.click();

    const dialog = page.getByRole('dialog', { name: `Tráiler de ${HERO_TITLE}` });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole('button', { name: 'Cerrar el tráiler' })).toBeFocused();
    const iframe = dialog.locator('iframe');
    await expect(iframe).toHaveAttribute('src', trailerEmbedUrl(HERO_TRAILER_ID));
    await expect(iframe).toHaveAttribute('title', `Tráiler de ${HERO_TITLE} (YouTube)`);
    await expect(iframe).toHaveAttribute('sandbox', /allow-scripts/);
    await expect(iframe).toHaveAttribute('referrerpolicy', 'strict-origin-when-cross-origin');
    // El servidor guardó el enlace normalizado (sin el `&t=5s` con el que se sembró).
    const youtube = dialog.getByRole('link', { name: 'Ver en YouTube (se abre en una pestaña nueva)' });
    await expect(youtube).toHaveAttribute('href', normalizedTrailer(HERO_TRAILER_ID));
    await expect(youtube).toHaveAttribute('target', '_blank');
    await expect(youtube).toHaveAttribute('rel', /noopener/);

    await page.keyboard.press('Escape');

    await expect(page.getByRole('dialog')).toHaveCount(0);
    // Al cerrar, el reproductor desaparece: el vídeo no sigue sonando de fondo.
    await expect(page.locator('iframe')).toHaveCount(0);
    await expect(trailerButton).toBeFocused();
  });

  test('a 375 px el diálogo del tráiler cabe en la pantalla y el vídeo es 16:9', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    await page.goto('/');
    await page.getByRole('region', { name: HERO_TITLE }).getByRole('button', { name: `Ver tráiler de ${HERO_TITLE}` }).click();

    const dialog = page.getByRole('dialog', { name: `Tráiler de ${HERO_TITLE}` });
    await expect(dialog).toBeVisible();
    const box = await dialog.boundingBox();
    const video = await dialog.locator('iframe').boundingBox();
    expect(box, 'el diálogo debe tener tamaño').not.toBeNull();
    expect(video, 'el reproductor debe tener tamaño').not.toBeNull();
    expect(box!.x).toBeGreaterThanOrEqual(0);
    expect(box!.x + box!.width).toBeLessThanOrEqual(375);
    expect(video!.width / video!.height).toBeCloseTo(16 / 9, 1);
    // El «Ver en YouTube» se alcanza sin desplazarse en horizontal.
    const youtube = await dialog.getByRole('link', { name: /^Ver en YouTube/ }).boundingBox();
    expect(youtube!.x + youtube!.width).toBeLessThanOrEqual(375);
  });

  /**
   * Revisión de QA: móvil apaisado (812 × 375). Ahí el ancho 16:9 no cabría en
   * el alto, y `TrailerDialog` limita el vídeo por la altura: el reproductor
   * entero y el botón de cerrar deben verse sin desplazarse, y Escape sigue
   * cerrándolo.
   */
  test('en un móvil apaisado el reproductor entero y «Cerrar» se ven sin desplazarse', async ({ page }) => {
    await page.setViewportSize({ width: 812, height: 375 });
    await page.goto('/');
    const trailerButton = page.getByRole('region', { name: HERO_TITLE }).getByRole('button', { name: `Ver tráiler de ${HERO_TITLE}` });
    await trailerButton.click();

    const dialog = page.getByRole('dialog', { name: `Tráiler de ${HERO_TITLE}` });
    await expect(dialog).toBeVisible();
    const close = dialog.getByRole('button', { name: 'Cerrar el tráiler' });
    await expect(close).toBeInViewport({ ratio: 1 });
    const video = await dialog.locator('iframe').boundingBox();
    expect(video, 'el reproductor debe tener tamaño').not.toBeNull();
    expect(video!.y).toBeGreaterThanOrEqual(0);
    expect(video!.y + video!.height, 'el vídeo no debe salirse por abajo').toBeLessThanOrEqual(375);
    expect(video!.x + video!.width).toBeLessThanOrEqual(812);
    expect(video!.width / video!.height).toBeCloseTo(16 / 9, 1);

    await page.keyboard.press('Escape');
    await expect(dialog).toBeHidden();
    await expect(trailerButton).toBeFocused();
  });

  test('película solo con tráiler («Tenet»): sin «Ver ahora»; «Ver tráiler» es la principal y usa el enlace normalizado', async ({
    page,
  }) => {
    await openMovie(page, 'Tenet');
    const header = page.getByRole('region', { name: 'Tenet' });

    await expect(header.getByRole('link', { name: /^Ver ahora/ })).toHaveCount(0);
    await expect(header.getByRole('button', { name: /^Ver ahora/ })).toHaveCount(0);
    const trailerButton = header.getByRole('button', { name: 'Ver tráiler de Tenet' });
    // Acción principal: la variante blanca (las secundarias llevan borde). Se busca
    // la clase exacta `bg-white`: un /bg-white/ suelto casaría también con el
    // `hover:bg-white/10` de las secundarias.
    await expect(trailerButton).toHaveClass(/(^|\s)bg-white(\s|$)/);

    await trailerButton.click();
    const dialog = page.getByRole('dialog', { name: 'Tráiler de Tenet' });
    // Se sembró como `https://youtu.be/<id>?si=e2e`: la API lo devuelve normalizado y el id llega intacto.
    await expect(dialog.locator('iframe')).toHaveAttribute('src', trailerEmbedUrl(TENET_TRAILER_ID));
    await expect(dialog.getByRole('link', { name: /^Ver en YouTube/ })).toHaveAttribute(
      'href',
      normalizedTrailer(TENET_TRAILER_ID),
    );

    await dialog.getByRole('button', { name: 'Cerrar el tráiler' }).click();
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(page.locator('iframe')).toHaveCount(0);
    await expect(trailerButton).toBeFocused();
  });

  test('película sin vídeo ni tráiler («Noches de Invierno»): ningún botón promete ver nada', async ({ page }) => {
    await openMovie(page, 'Noches de Invierno');
    const header = page.getByRole('region', { name: 'Noches de Invierno' });

    await expect(header.getByText(/^Ver ahora$|^Ver tráiler$/)).toHaveCount(0);
    await expect(header.getByRole('link', { name: /^Ver / })).toHaveCount(0);
    await expect(header.getByRole('button', { name: /^Ver / })).toHaveCount(0);
    // Lo que sí se puede hacer sigue ahí.
    await expect(header.getByRole('button', { name: /^Mi lista/ })).toBeVisible();
  });

  test('series: el tráiler de la serie del banner está en /series y en su página, junto a «Empezar a ver»', async ({
    page,
  }) => {
    await page.goto('/series');
    const banner = page.getByRole('region', { name: SERIES_HERO.title });
    await expect(banner.getByRole('button', { name: `Ver tráiler de ${SERIES_HERO.title}` })).toBeVisible();

    await openSeries(page, SERIES_HERO.title);
    const header = page.getByRole('region', { name: SERIES_HERO.title });
    await expect(header.getByRole('link', { name: /^Empezar a ver/ })).toBeVisible();
    const trailerButton = header.getByRole('button', { name: `Ver tráiler de ${SERIES_HERO.title}` });
    // Con «Empezar a ver», el tráiler es secundario.
    await expect(trailerButton).not.toHaveClass(/(^|\s)bg-white(\s|$)/);

    await trailerButton.click();
    await expect(page.getByRole('dialog', { name: `Tráiler de ${SERIES_HERO.title}` }).locator('iframe')).toHaveAttribute(
      'src',
      trailerEmbedUrl(SERIES_TRAILER_ID),
    );
    await page.keyboard.press('Escape');
    await expect(trailerButton).toBeFocused();
  });

  test('serie cuyos episodios no tienen vídeo («Estación Polar»): sin «Empezar a ver» ni «Ver», pero con sus episodios', async ({
    page,
  }) => {
    await openSeries(page, 'Estación Polar');

    await expect(page.getByRole('link', { name: /^Empezar a ver/ })).toHaveCount(0);
    await expect(page.getByRole('button', { name: /^Empezar a ver/ })).toHaveCount(0);
    const list = page.getByRole('list', { name: 'Episodios de la temporada 1' });
    await expect(list.getByRole('listitem')).toHaveCount(2);
    await expect(list.getByRole('link')).toHaveCount(0);
    await expect(list.getByRole('button')).toHaveCount(0);
    // Tampoco tiene tráiler: la única acción de la cabecera es «Mi lista».
    await expect(page.getByRole('button', { name: /Ver tráiler/ })).toHaveCount(0);
    await expect(page.getByRole('region', { name: 'Estación Polar' }).getByRole('button', { name: /^Mi lista/ })).toBeVisible();
  });
});
