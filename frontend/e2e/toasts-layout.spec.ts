/**
 * E2E de DÓNDE quedan los avisos (toasts) en un navegador real: lo que jsdom no
 * puede medir (posiciones, bloques contenedores, la barra que se aparta).
 *
 * 1. **En móvil, un error persistente no tapa la barra superior.** Los errores
 *    no se cierran solos (B5) y en móvil la zona de avisos va ARRIBA (E-10).
 *    Pegada al borde, un error de red al añadir a «Mi lista» ocupaba y=20–112 y
 *    tapaba logo, pestañas, buscar y menú hasta cerrarlo a mano. Ahora va justo
 *    debajo de la barra, sube con ella cuando se aparta al bajar y vuelve a
 *    bajar cuando la barra regresa. En escritorio sigue abajo a la derecha.
 * 2. **Dentro de un diálogo largo, la zona de avisos no se desplaza con el
 *    contenido** (B11). Con `scale: 1` como estado final, el `<dialog>` era el
 *    bloque contenedor de sus hijos `position: fixed` y, como el que hace scroll
 *    es el diálogo, la zona se iba con el contenido (medido: top 29 → −202).
 *    Con `scale: none` queda pegada a la ventana. Se mide la propia zona del
 *    diálogo (existe, vacía, mientras está abierto: ver `Modal`), sin necesidad
 *    de provocar un aviso.
 *
 * Solo lectura: nada se guarda (el `POST` de «Mi lista» se corta en el navegador
 * y el diálogo de episodio se cierra sin enviar).
 */
import type { Page } from '@playwright/test';
import { findSeriesIdAsAdmin, loginAdmin } from './support/api';
import { HERO_TITLE, SERIES_HERO } from './support/catalog';
import { expect, test } from './support/fixtures';

/** Caja (en coordenadas de la ventana) de la zona de avisos de la PÁGINA (la que no está dentro de un diálogo). */
async function pageToastsBox(page: Page) {
  return page.evaluate(() => {
    const region = [...document.querySelectorAll('section[aria-label="Avisos"]')].find((el) => !el.closest('dialog'));
    const { top, bottom, left, right } = region!.getBoundingClientRect();
    return { top, bottom, left, right };
  });
}

/** Parte inferior VISIBLE de la barra superior (0 si está apartada por encima de la ventana). */
async function headerBottom(page: Page) {
  return page.getByRole('banner').evaluate((header) => Math.max(0, header.getBoundingClientRect().bottom));
}

/** Lanza un error que no se cierra solo: «Mi lista» del banner de /peliculas con el `POST` cortado. */
async function showPersistentError(page: Page) {
  await page.route('**/api/users/me/favorites/**', (route) =>
    route.request().method() === 'POST' ? route.abort('internetdisconnected') : route.continue(),
  );
  await page.goto('/peliculas');
  const banner = page.getByRole('region', { name: HERO_TITLE });
  await banner.getByRole('button', { name: /^Mi lista/ }).click();
  const toasts = page.getByRole('region', { name: 'Avisos' });
  await expect(toasts.getByRole('alert').getByRole('button', { name: 'Cerrar aviso' })).toBeVisible();
  // Que termine la entrada del aviso (250 ms) antes de medir: ninguna animación dentro de la zona de avisos.
  await expect
    .poll(() =>
      toasts.evaluate(
        (region) =>
          document.getAnimations().filter((animation) => {
            const target = (animation.effect as KeyframeEffect | null)?.target;
            return target instanceof Node && region.contains(target);
          }).length,
      ),
    )
    .toBe(0);
}

test.describe('Avisos en móvil 375x812', () => {
  test.use({ viewport: { width: 375, height: 812 } });

  test('un error persistente queda DEBAJO de la barra (no la tapa), sube con ella al bajar y baja al volver', async ({
    page,
    user,
    signIn,
  }) => {
    await signIn(user);
    await showPersistentError(page);

    const bottom = await headerBottom(page);
    expect(bottom).toBeGreaterThan(50);
    expect((await pageToastsBox(page)).top).toBeGreaterThanOrEqual(bottom);

    // Cada control de la barra recibe el toque en su centro: no hay nada encima.
    const controls = [
      page.getByRole('link', { name: 'streambox' }),
      ...['Inicio', 'Películas', 'Series', 'Mi lista'].map((name) =>
        page.getByRole('navigation', { name: 'Principal' }).getByRole('link', { name, exact: true }),
      ),
      page.getByRole('button', { name: 'Buscar películas y series' }),
      page.getByRole('button', { name: 'Menú de usuario' }),
    ];
    for (const control of controls) {
      const hitsItself = await control.evaluate((element) => {
        const { left, top, width, height } = element.getBoundingClientRect();
        const hit = document.elementFromPoint(left + width / 2, top + height / 2);
        return !!hit && element.contains(hit);
      });
      expect(hitsItself, `${await control.textContent()} queda tapado`).toBe(true);
    }

    // Al bajar, la barra se aparta y el aviso sube con ella (sin dejar el hueco de la barra arriba).
    await page.mouse.wheel(0, 700);
    await expect(page.getByRole('banner')).toHaveAttribute('data-navbar-away', 'true');
    await expect.poll(async () => (await pageToastsBox(page)).top).toBeLessThan(20);

    // Al subir, la barra vuelve y el aviso baja otra vez por debajo de ella.
    await page.mouse.wheel(0, -200);
    await expect(page.getByRole('banner')).not.toHaveAttribute('data-navbar-away');
    await expect.poll(async () => (await pageToastsBox(page)).top - (await headerBottom(page))).toBeGreaterThanOrEqual(0);
  });
});

test.describe('Avisos en escritorio 1280x800', () => {
  test.use({ viewport: { width: 1280, height: 800 } });

  test('el error sigue abajo a la derecha, lejos de la barra', async ({ page, user, signIn }) => {
    await signIn(user);
    await showPersistentError(page);

    const box = await pageToastsBox(page);
    expect(box.bottom).toBeLessThanOrEqual(800);
    expect(box.top).toBeGreaterThan(400);
    expect(box.left).toBeGreaterThan(640);
  });
});

for (const viewport of [
  { name: 'móvil 375x560', width: 375, height: 560 },
  { name: 'escritorio 1280x560', width: 1280, height: 560 },
] as const) {
  test.describe(`Avisos dentro de un diálogo largo (${viewport.name})`, () => {
    test.use({ viewport: { width: viewport.width, height: viewport.height } });

    test('la zona de avisos del diálogo no se desplaza con su contenido (el diálogo no queda con escala)', async ({
      page,
      request,
      signIn,
    }) => {
      const adminToken = await loginAdmin(request);
      await signIn({ token: adminToken });
      await page.goto(`/admin/series/${await findSeriesIdAsAdmin(request, adminToken, SERIES_HERO.title)}/editar`);
      const episodes = page.getByRole('region', { name: 'Episodios', exact: true });
      await episodes.getByRole('button', { name: 'Añadir episodio' }).click();
      const dialog = page.getByRole('dialog', { name: 'Añadir episodio' });
      // Con los errores a la vista el formulario es más largo que la ventana: el diálogo hace scroll.
      await dialog.getByRole('button', { name: 'Añadir episodio' }).click();
      await expect(dialog.getByLabel('Título', { exact: true })).toHaveAccessibleDescription('El título es obligatorio');

      // Que termine la entrada (fundido y crecimiento de 0.96 a su tamaño) antes de medir.
      await expect.poll(() => dialog.evaluate((element) => element.getAnimations().length)).toBe(0);

      const region = dialog.getByRole('region', { name: 'Avisos' });
      const topBefore = await region.evaluate((element) => element.getBoundingClientRect().top);
      const scrolled = await dialog.evaluate((element) => {
        element.scrollTop = element.scrollHeight;
        return element.scrollTop;
      });
      expect(scrolled, 'el diálogo debería hacer scroll a este alto de ventana').toBeGreaterThan(50);
      const topAfter = await region.evaluate((element) => element.getBoundingClientRect().top);
      expect(Math.abs(topAfter - topBefore), 'la zona de avisos se ha movido con el contenido').toBeLessThanOrEqual(1);
      expect(topAfter).toBeGreaterThanOrEqual(0);
      // La causa: terminada la entrada, el diálogo se queda SIN escala (`scale: 1` ya lo haría bloque contenedor).
      expect(await dialog.evaluate((element) => getComputedStyle(element).scale)).toBe('none');

      await page.keyboard.press('Escape');
      await expect(dialog).toHaveCount(0);
    });
  });
}
