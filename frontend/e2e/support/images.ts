/**
 * Comprobaciones geométricas de imágenes para los E2E (banner y modal de detalle).
 *
 * Existen porque los dos defectos visuales reales de las portadas no se ven en
 * el DOM, solo en las cajas que pinta el navegador:
 *  1. Una imagen de FONDO que no cubre su caja: con `relative` + `absolute` en el
 *     mismo contenedor la portada salía a su tamaño intrínseco (600 px) y dejaba
 *     el resto del banner vacío. → {@link expectCovers}
 *  2. Un póster VERTICAL (2:3) metido en una caja horizontal con `object-cover`:
 *     solo se veía una franja ampliada del cartel. → {@link expectWholePoster}
 */
import { expect } from '@playwright/test';
import type { Locator } from '@playwright/test';

/** Proporción de los pósters (ancho / alto). */
const POSTER_RATIO = 2 / 3;

/**
 * La caja de `image` cubre por completo la de `container` (admite 1 px de
 * redondeo). Que la sobrepase es correcto: el fondo desenfocado se escala un
 * poco a propósito para ocultar el borde claro del desenfoque.
 */
export async function expectCovers(image: Locator, container: Locator, label: string): Promise<void> {
  const outer = await container.boundingBox();
  const inner = await image.boundingBox();
  expect(outer, `${label}: el contenedor no tiene caja`).not.toBeNull();
  expect(inner, `${label}: la imagen no tiene caja (¿oculta?)`).not.toBeNull();
  expect(inner!.x, `${label}: no llega al borde izquierdo`).toBeLessThanOrEqual(outer!.x + 1);
  expect(inner!.y, `${label}: no llega al borde superior`).toBeLessThanOrEqual(outer!.y + 1);
  expect(inner!.x + inner!.width, `${label}: no llega al borde derecho`).toBeGreaterThanOrEqual(outer!.x + outer!.width - 1);
  expect(inner!.y + inner!.height, `${label}: no llega al borde inferior`).toBeGreaterThanOrEqual(outer!.y + outer!.height - 1);
}

/**
 * El póster se ve ENTERO: su caja es 2:3, la imagen descargada tiene esa misma
 * proporción (así `object-cover` no recorta casi nada; se admite un 3 %, lo que
 * difieren las portadas reales de 600×894 de un 2:3 exacto) y la caja cabe
 * dentro de `container` (ningún `overflow: hidden` la corta).
 */
export async function expectWholePoster(poster: Locator, container: Locator, label: string): Promise<void> {
  await expect(poster, `${label}: debe ser visible`).toBeVisible();
  await expect
    .poll(() => poster.evaluate((img: HTMLImageElement) => img.complete && img.naturalWidth > 0), {
      message: `${label}: la imagen debe haberse descargado`,
    })
    .toBe(true);

  const box = await poster.boundingBox();
  const outer = await container.boundingBox();
  const natural = await poster.evaluate((img: HTMLImageElement) => img.naturalWidth / img.naturalHeight);
  const boxRatio = box!.width / box!.height;

  expect(Math.abs(boxRatio - POSTER_RATIO) / POSTER_RATIO, `${label}: la caja no es 2:3 (${boxRatio.toFixed(3)})`).toBeLessThan(0.02);
  expect(Math.abs(natural - boxRatio) / boxRatio, `${label}: la imagen se recorta (natural ${natural.toFixed(3)}, caja ${boxRatio.toFixed(3)})`).toBeLessThan(0.03);
  expect(box!.x, `${label}: se sale por la izquierda`).toBeGreaterThanOrEqual(outer!.x - 0.5);
  expect(box!.y, `${label}: se sale por arriba`).toBeGreaterThanOrEqual(outer!.y - 0.5);
  expect(box!.x + box!.width, `${label}: se sale por la derecha`).toBeLessThanOrEqual(outer!.x + outer!.width + 0.5);
  expect(box!.y + box!.height, `${label}: se sale por abajo`).toBeLessThanOrEqual(outer!.y + outer!.height + 0.5);
}
