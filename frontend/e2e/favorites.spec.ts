/**
 * E2E de la página de una película (`/peliculas/:id`) y de "Mi lista": abrirla,
 * recargarla, volver con «Atrás», una dirección inexistente, añadir y quitar
 * favoritos (con aviso visible) y vaciar la lista con confirmación.
 *
 * Cada test registra su propio usuario, así que las listas no se mezclan aunque
 * los tests corran en paralelo.
 */
import { addFavoritesByTitle } from './support/api';
import { MOVIES, videoUrl } from './support/catalog';
import { expect, movieCard, test } from './support/fixtures';
import { expectCovers, expectWholePoster } from './support/images';

/** Una película con dos géneros y portada, que aparece en la fila "Novedades". */
const FILM = MOVIES.find((movie) => movie.title === 'Interstellar');
if (!FILM) throw new Error('El catálogo de ejemplo debe incluir «Interstellar»');

test.describe('Detalle y favoritos', () => {
  test('la página de una película muestra título, datos, sinopsis completa y un «Ver ahora» que abre en pestaña nueva', async ({
    page,
    user,
    signIn,
  }) => {
    await signIn(user);
    await page.goto('/');
    await movieCard(page.getByRole('region', { name: 'Novedades' }), FILM.title).click();

    await expect(page).toHaveURL(/\/peliculas\/\d+$/);
    await expect(page).toHaveTitle(`${FILM.title} — StreamBox`);
    const header = page.getByRole('region', { name: FILM.title });
    await expect(header.getByRole('heading', { level: 1, name: FILM.title })).toBeVisible();
    for (const genre of FILM.genres) {
      await expect(header.getByRole('listitem').filter({ hasText: genre })).toBeVisible();
    }
    // La sinopsis se lee entera: aquí no hay otro sitio al que mandarla.
    await expect(header.getByText(FILM.description)).toBeVisible();
    await expect(page.getByRole('dialog')).toHaveCount(0);

    // Cabecera: fondo desenfocado (1.ª imagen) + póster nítido (2.ª), con la misma URL, como el banner.
    const images = header.locator('img');
    await expect(images).toHaveCount(2);
    const [backdrop, poster] = [images.first(), images.last()];
    expect(await backdrop.getAttribute('src')).toBe(await poster.getAttribute('src'));
    await expectCovers(backdrop, header, 'fondo de la cabecera');
    await expectWholePoster(poster, header, 'póster de la cabecera');

    const watch = header.getByRole('link', { name: /^Ver ahora/ });
    await expect(watch).toHaveAttribute('target', '_blank');
    await expect(watch).toHaveAttribute('rel', /noopener/);
    await expect(watch).toHaveAttribute('href', videoUrl(FILM));
    // Un usuario normal no ve «Editar».
    await expect(page.getByRole('link', { name: /^Editar/ })).toHaveCount(0);
  });

  test('la dirección de una película sirve: recargar la mantiene y «Atrás» vuelve al catálogo', async ({
    page,
    user,
    signIn,
  }) => {
    await signIn(user);
    await page.goto('/peliculas');
    await movieCard(page.getByRole('region', { name: 'Novedades' }), FILM.title).click();
    await expect(page.getByRole('heading', { level: 1, name: FILM.title })).toBeVisible();
    const url = page.url();

    await page.reload();
    expect(page.url()).toBe(url);
    await expect(page.getByRole('heading', { level: 1, name: FILM.title })).toBeVisible();

    await page.goBack();
    await expect(page).toHaveURL(/\/peliculas$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Películas' })).toBeVisible();
  });

  test('una dirección de película que no existe dice «Película no encontrada» y lleva a todas las películas', async ({
    page,
    user,
    signIn,
  }) => {
    await signIn(user);
    for (const path of ['/peliculas/999999', '/peliculas/abc']) {
      await page.goto(path);
      await expect(page.getByRole('heading', { level: 1, name: 'Película no encontrada' })).toBeVisible();
      await expect(page).toHaveTitle('Película no encontrada — StreamBox');
    }
    await page.getByRole('link', { name: 'Ver todas las películas' }).click();
    await expect(page).toHaveURL(/\/peliculas$/);
    await expect(page.getByRole('region', { name: 'Novedades' })).toBeVisible();
  });

  test('añadir una película desde su página: aviso visible, aparece en Mi lista y se puede quitar', async ({
    page,
    user,
    signIn,
  }) => {
    await signIn(user);
    await page.goto('/');
    await movieCard(page.getByRole('region', { name: 'Novedades' }), FILM.title).click();

    const header = page.getByRole('region', { name: FILM.title });
    await header.getByRole('button', { name: /^Mi lista/ }).click();
    await expect(page.getByText(`«${FILM.title}» se ha añadido a tu lista.`)).toBeVisible();
    await expect(header.getByRole('button', { name: /^En mi lista/ })).toBeVisible();

    await page.getByRole('link', { name: 'Mi lista' }).click();
    await expect(page).toHaveURL(/\/favorites$/);
    await expect(page.getByRole('heading', { level: 1, name: 'Mi lista' })).toBeVisible();
    const card = movieCard(page.getByRole('main'), FILM.title);
    await expect(card).toBeVisible();

    // Persistencia real: sigue ahí tras recargar (viene del servidor, no solo del estado local).
    await page.reload();
    await expect(movieCard(page.getByRole('main'), FILM.title)).toBeVisible();

    // Quitarla desde su página y volver a la lista con «Atrás».
    await movieCard(page.getByRole('main'), FILM.title).click();
    await page.getByRole('region', { name: FILM.title }).getByRole('button', { name: /^En mi lista/ }).click();
    await expect(page.getByText(`«${FILM.title}» se ha quitado de tu lista.`)).toBeVisible();
    await page.goBack();

    await expect(page).toHaveURL(/\/favorites$/);
    await expect(page.getByRole('heading', { level: 2, name: 'Tu lista está vacía' })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Explorar catálogo' })).toBeVisible();
  });

  test('«Vaciar lista» pide confirmación: cancelar conserva las películas y confirmar vacía la lista', async ({
    page,
    request,
    user,
    signIn,
  }) => {
    await addFavoritesByTitle(request, user.token, ['Dredd', 'Mad Max', 'Interstellar']);
    await signIn(user);
    await page.goto('/favorites');

    const main = page.getByRole('main');
    await expect(main.getByRole('link', { name: /\d{4} ·/ })).toHaveCount(3);

    // Cancelar: el diálogo se cierra y NO se borra nada.
    await page.getByRole('button', { name: 'Vaciar lista' }).click();
    const confirm = page.getByRole('alertdialog', { name: '¿Vaciar tu lista?' });
    await expect(confirm).toBeVisible();
    await expect(confirm).toContainText('Se quitarán las 3 películas de Mi lista.');
    // El foco empieza en la opción SEGURA (Cancelar), no en la destructiva.
    await expect(confirm.getByRole('button', { name: 'Cancelar' })).toBeFocused();
    await confirm.getByRole('button', { name: 'Cancelar' }).click();
    await expect(confirm).toBeHidden();
    await expect(main.getByRole('link', { name: /\d{4} ·/ })).toHaveCount(3);

    // Confirmar: lista vacía, aviso y foco en el encabezado (el botón ha desaparecido).
    await page.getByRole('button', { name: 'Vaciar lista' }).click();
    await confirm.getByRole('button', { name: 'Sí, vaciar lista' }).click();
    await expect(confirm).toBeHidden();
    await expect(page.getByRole('heading', { level: 2, name: 'Tu lista está vacía' })).toBeVisible();
    await expect(page.getByText('Tu lista se ha vaciado.')).toBeVisible();
    await expect(page.getByRole('heading', { level: 1, name: 'Mi lista' })).toBeFocused();
    await expect(page.getByRole('button', { name: 'Vaciar lista' })).toHaveCount(0);

    // Y es real: el servidor tampoco tiene ya la lista.
    await page.reload();
    await expect(page.getByRole('heading', { level: 2, name: 'Tu lista está vacía' })).toBeVisible();
  });

  test('un usuario nuevo ve su lista vacía con una invitación a explorar el catálogo', async ({
    page,
    user,
    signIn,
  }) => {
    await signIn(user);
    await page.goto('/favorites');
    await expect(page.getByRole('heading', { level: 2, name: 'Tu lista está vacía' })).toBeVisible();
    await page.getByRole('link', { name: 'Explorar catálogo' }).click();
    await expect(page).toHaveURL(/\/$/);
  });

  test('si el servidor falla, "Mi lista" muestra el error y permite reintentar', async ({ page, user, signIn }) => {
    await signIn(user);

    // Mientras `serverDown` sea true, TODA lectura de la lista falla con un 500 (en desarrollo React
    // StrictMode lanza la petición dos veces, así que no vale "fallar solo la primera").
    let serverDown = true;
    await page.route('**/api/users/me/favorites', async (route) => {
      if (route.request().method() === 'GET' && serverDown) {
        await route.fulfill({
          status: 500,
          contentType: 'application/json',
          body: JSON.stringify({ status: 500, code: 'INTERNAL_ERROR', message: 'Error interno' }),
        });
      } else {
        await route.continue();
      }
    });

    await page.goto('/favorites');
    await expect(page.getByRole('heading', { level: 2, name: 'No se pudo cargar tu lista' })).toBeVisible();
    // El servidor "se recupera": el reintento llega ya al backend real.
    serverDown = false;
    await page.getByRole('button', { name: 'Reintentar' }).click();
    await expect(page.getByRole('heading', { level: 2, name: 'Tu lista está vacía' })).toBeVisible();
  });
});
