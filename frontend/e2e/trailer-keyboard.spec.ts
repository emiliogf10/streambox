/**
 * Revisión de QA de «Ver tráiler»: teclado DENTRO del reproductor.
 *
 * El diálogo del tráiler se cierra con Escape (lo prueba `trailer.spec.ts`),
 * pero solo mientras el foco está en el propio StreamBox («Cerrar el
 * tráiler», «Ver en YouTube»). En cuanto el foco entra en el `<iframe>` de
 * YouTube, que es lo normal (con Tab para llegar a sus controles, o al hacer
 * clic en el vídeo para pausarlo), las pulsaciones van al documento de
 * YouTube, que es de otro origen: el `<dialog>` de StreamBox no recibe el
 * Escape y no se cierra. Comprobado también con el reproductor real de
 * YouTube en Chromium (informe de QA, incidencia trailer-2).
 *
 * El reproductor se sustituye por una página con un botón (como sus
 * controles), servida en el mismo origen que el real con `page.route`: así el
 * foco entra de verdad en un documento de otro origen, igual que con YouTube.
 *
 * **Limitación conocida y aceptada (incidencia QA trailer-2).** No tiene
 * arreglo desde la página: el navegador no deja que un documento capture las
 * teclas que se pulsan dentro de un marco de otro origen (es una barrera de
 * seguridad). La decisión es una **pista visible** en el diálogo («Pulsa Esc
 * para cerrar. Si estás dentro del vídeo, sal antes con Mayús+Tab.»); estos
 * tests comprueban que la pista está y que dice la verdad: Mayús+Tab saca el
 * foco del reproductor a «Cerrar el tráiler» y entonces Escape cierra. Si
 * algún día el Escape dentro del marco llegara a cerrar el diálogo, este test
 * seguiría pasando (no comprueba que el Escape dentro falle).
 */
import { MOVIES } from './support/catalog';
import { expect, movieCard, test } from './support/fixtures';

const PLAYER = '<!doctype html><title>Reproductor de prueba</title><button type="button">Reproducir</button>';

test.describe('Diálogo del tráiler: teclado con el foco dentro del reproductor', () => {
  test.beforeEach(async ({ page, user, signIn }) => {
    await page.route('https://www.youtube-nocookie.com/**', (route) =>
      route.fulfill({ contentType: 'text/html', body: PLAYER }),
    );
    await signIn(user);
    const tenet = MOVIES.find((movie) => movie.title === 'Tenet')!;
    await page.goto(`/peliculas?anio=${tenet.releaseYear}`);
    await movieCard(page.getByRole('region', { name: 'Resultados' }), 'Tenet').click();
    await page.getByRole('region', { name: 'Tenet' }).getByRole('button', { name: 'Ver tráiler de Tenet' }).click();
    await expect(page.getByRole('dialog', { name: 'Tráiler de Tenet' })).toBeVisible();
  });

  test('Tab desde «Cerrar» entra en el reproductor y Shift+Tab vuelve a «Cerrar» (el foco no se escapa del diálogo)', async ({
    page,
  }) => {
    const dialog = page.getByRole('dialog', { name: 'Tráiler de Tenet' });
    const close = dialog.getByRole('button', { name: 'Cerrar el tráiler' });
    await expect(close).toBeFocused();
    const player = page.frameLocator('iframe[title="Tráiler de Tenet (YouTube)"]');

    await page.keyboard.press('Tab');
    await expect(player.getByRole('button', { name: 'Reproducir' })).toBeFocused();
    await page.keyboard.press('Shift+Tab');

    await expect(close).toBeFocused();
  });

  test('la pista dice cómo cerrar desde el reproductor y es verdad: Mayús+Tab vuelve a «Cerrar» y Esc cierra', async ({
    page,
  }) => {
    const dialog = page.getByRole('dialog', { name: 'Tráiler de Tenet' });
    await expect(
      dialog.getByText('Pulsa Esc para cerrar. Si estás dentro del vídeo, sal antes con Mayús+Tab.'),
    ).toBeVisible();
    const player = page.frameLocator('iframe[title="Tráiler de Tenet (YouTube)"]');
    await page.keyboard.press('Tab');
    await expect(player.getByRole('button', { name: 'Reproducir' })).toBeFocused();

    await page.keyboard.press('Shift+Tab');
    await expect(dialog.getByRole('button', { name: 'Cerrar el tráiler' })).toBeFocused();
    await page.keyboard.press('Escape');

    await expect(page.getByRole('dialog')).toHaveCount(0);
    // Y el foco vuelve al botón que lo abrió, como en cualquier otro cierre.
    await expect(page.getByRole('region', { name: 'Tenet' }).getByRole('button', { name: 'Ver tráiler de Tenet' })).toBeFocused();
  });
});
