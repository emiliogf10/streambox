/**
 * Tests de la imagen de fondo nítida de `FeaturedBanner` (`backdropUrl`).
 *
 * Protegen las decisiones de «Imagen de fondo nítida» del componente:
 * - sin imagen de fondo, y SIEMPRE en móvil, el banner es exactamente el de
 *   antes (portada desenfocada + póster, una sola URL) y la imagen de fondo ni
 *   se pide;
 * - desde `md`, con imagen de fondo, hay UNA sola imagen con prioridad alta (la
 *   de fondo, sin desenfoque ni zoom) y ningún póster: ni oculto, porque se
 *   descargaría igual;
 * - el banner mide lo mismo que con póster (lo que reserva su esqueleto);
 * - si la imagen de fondo falla, vuelve a la composición de siempre.
 *
 * Las imágenes son decorativas (`alt=""`, sin rol accesible), así que se buscan
 * por etiqueta `img` dentro de la región del banner, como en `HeroBanner.test`.
 */
import { act, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MEDIUM_SCREEN_QUERY } from '../hooks/useMediaQuery';
import {
  BANNER_BACKDROP_MIN_HEIGHT_CLASS,
  BANNER_POSTER_SIZE_CLASS,
  DETAIL_BACKDROP_MIN_HEIGHT_CLASS,
  DETAIL_POSTER_SIZE_CLASS,
} from './bannerGridStyles';
import { FeaturedBanner } from './FeaturedBanner';

const POSTER = 'https://img.example/sintel.webp';
const BACKDROP = '/covers/sintel-fondo.webp';

/** Simula una ventana de tableta o escritorio (`md` o más) o de móvil. */
function setWideScreen(wide: boolean) {
  const original = window.matchMedia;
  vi.spyOn(window, 'matchMedia').mockImplementation((query: string) => ({
    ...original(query),
    matches: wide && query === MEDIUM_SCREEN_QUERY,
  }));
}

afterEach(() => vi.restoreAllMocks());

function renderBanner(props: { backdropUrl?: string | null; layout?: 'banner' | 'page' } = {}) {
  render(
    <FeaturedBanner
      title="Sintel"
      imageUrl={POSTER}
      description="Una joven busca a su dragón."
      meta={<p>2010</p>}
      actions={<button type="button">Ver ahora</button>}
      {...props}
    />,
  );
  const region = screen.getByRole('region', { name: 'Sintel' });
  return { region, images: () => [...region.querySelectorAll('img')] };
}

/** Comprueba la composición de siempre: fondo desenfocado y póster, los dos con la portada. */
function expectClassicBanner(images: HTMLImageElement[]) {
  expect(images).toHaveLength(2);
  for (const img of images) expect(img).toHaveAttribute('src', POSTER);
  expect(images[0]).toHaveClass('blur-2xl', 'scale-110');
}

describe('FeaturedBanner: imagen de fondo nítida', () => {
  it('sin imagen de fondo, en escritorio: la composición de siempre', () => {
    setWideScreen(true);
    const { images } = renderBanner({ backdropUrl: null });
    expectClassicBanner(images());
  });

  it('en móvil, aunque la haya: la composición de siempre y la imagen de fondo ni se pide', () => {
    setWideScreen(false);
    const { images } = renderBanner({ backdropUrl: BACKDROP });
    expectClassicBanner(images());
    expect(images().some((img) => img.getAttribute('src') === BACKDROP)).toBe(false);
  });

  it('desde md: una sola imagen, la de fondo, nítida, decorativa y con prioridad alta; sin póster', () => {
    setWideScreen(true);
    const { region, images } = renderBanner({ backdropUrl: BACKDROP });

    expect(images()).toHaveLength(1);
    const [backdrop] = images();
    expect(backdrop).toHaveAttribute('src', BACKDROP);
    expect(backdrop).toHaveAttribute('alt', '');
    expect(backdrop).toHaveAttribute('fetchpriority', 'high');
    expect(backdrop).toHaveAttribute('loading', 'eager');
    expect(backdrop).not.toHaveClass('blur-2xl');
    expect(backdrop).not.toHaveClass('scale-110');
    // Las dimensiones declaradas son las de una imagen 16:9, no las de una portada.
    expect(backdrop).toHaveAttribute('width', '1280');
    expect(backdrop).toHaveAttribute('height', '720');
    expect(backdrop.closest('[aria-hidden="true"]')).not.toBeNull();
    // Mismo contenido de siempre: título y acciones.
    expect(screen.getByRole('heading', { level: 2, name: 'Sintel' })).toBeInTheDocument();
    expect(region).toContainElement(screen.getByRole('button', { name: 'Ver ahora' }));
  });

  it.each([
    ['banner', BANNER_BACKDROP_MIN_HEIGHT_CLASS],
    ['page', DETAIL_BACKDROP_MIN_HEIGHT_CLASS],
  ] as const)('sin póster, el banner (%s) reserva el alto que tendría con él', (layout, minHeightClass) => {
    setWideScreen(true);
    renderBanner({ backdropUrl: BACKDROP, layout });
    const heading = screen.getByRole('heading', { name: 'Sintel' });
    // El alto va en la columna de texto (padre del título), que ocupa el sitio del póster y centra su contenido.
    const column = heading.parentElement;
    expect(column).toHaveClass(...minHeightClass.split(' '), 'md:justify-center');
    // ...y NUNCA en la rejilla: lleva el relleno vertical y, con `border-box`, su `min-height` lo incluiría, así que
    // el banner quedaría unos 6rem más bajo que con póster (el esqueleto) y la página saltaría al llegar los datos.
    const grid = column?.parentElement;
    for (const item of minHeightClass.split(' ')) expect(grid).not.toHaveClass(item);
  });

  it('sin imagen de fondo no se reserva ningún alto extra (el póster ya lo da)', () => {
    setWideScreen(true);
    renderBanner({ backdropUrl: null });
    const column = screen.getByRole('heading', { name: 'Sintel' }).parentElement;
    expect(column).not.toHaveClass('md:min-h-72');
    expect(column?.parentElement).not.toHaveClass('md:min-h-72');
  });

  it('si la imagen de fondo falla, vuelve a la portada desenfocada con el póster (nunca un fondo liso)', () => {
    setWideScreen(true);
    const { images } = renderBanner({ backdropUrl: BACKDROP });

    fireEvent.error(images()[0]);

    expectClassicBanner(images());
  });
});

/**
 * `matchMedia` falso cuyo resultado se puede cambiar en caliente (girar la
 * tableta, estrechar la ventana) y que avisa a quien escucha.
 */
function controllableScreen(initialWide: boolean) {
  let wide = initialWide;
  const listeners = new Set<() => void>();
  vi.spyOn(window, 'matchMedia').mockImplementation(
    (query: string) =>
      ({
        matches: wide && query === MEDIUM_SCREEN_QUERY,
        media: query,
        onchange: null,
        addEventListener: (_type: string, listener: () => void) => listeners.add(listener),
        removeEventListener: (_type: string, listener: () => void) => listeners.delete(listener),
        addListener: () => {},
        removeListener: () => {},
        dispatchEvent: () => false,
      }) as unknown as MediaQueryList,
  );
  return {
    setWide(next: boolean) {
      wide = next;
      for (const listener of listeners) listener();
    },
  };
}

/**
 * Revisión de QA (V16): el banner sigue a la ventana SIN recargar, y lo
 * aprendido de una imagen de fondo rota no se olvida al cambiar de tamaño ni
 * impide probar otra.
 */
describe('FeaturedBanner: cambio de tamaño en caliente (QA V16)', () => {
  it('de escritorio a móvil vuelve a montar el póster; de móvil a escritorio lo quita y no lo descarga', () => {
    const screenSize = controllableScreen(true);
    const { images } = renderBanner({ backdropUrl: BACKDROP });
    expect(images().map((img) => img.getAttribute('src'))).toEqual([BACKDROP]);

    act(() => screenSize.setWide(false));
    expectClassicBanner(images());

    act(() => screenSize.setWide(true));
    expect(images().map((img) => img.getAttribute('src'))).toEqual([BACKDROP]);
  });

  it('una imagen de fondo que falló no se vuelve a pedir al ir a móvil y volver', () => {
    const screenSize = controllableScreen(true);
    const { images } = renderBanner({ backdropUrl: BACKDROP });
    fireEvent.error(images()[0]);
    expectClassicBanner(images());

    act(() => screenSize.setWide(false));
    act(() => screenSize.setWide(true));

    expectClassicBanner(images());
  });

  it('si el título pasa a tener OTRA imagen de fondo, se vuelve a intentar', () => {
    controllableScreen(true);
    const props = {
      title: 'Sintel',
      imageUrl: POSTER,
      description: 'Una joven busca a su dragón.',
      meta: <p>2010</p>,
      actions: <button type="button">Ver ahora</button>,
    };
    const { rerender } = render(<FeaturedBanner {...props} backdropUrl={BACKDROP} />);
    const region = () => screen.getByRole('region', { name: 'Sintel' });
    fireEvent.error(region().querySelector('img')!);
    expect(region().querySelectorAll('img')).toHaveLength(2);

    rerender(<FeaturedBanner {...props} backdropUrl="/covers/sintel-fondo-2.webp" />);

    const srcs = [...region().querySelectorAll('img')].map((img) => img.getAttribute('src'));
    expect(srcs).toEqual(['/covers/sintel-fondo-2.webp']);
  });

  it('una cadena vacía (o ausente) no cuenta como imagen de fondo: composición de siempre', () => {
    controllableScreen(true);
    const { images } = renderBanner({ backdropUrl: '' });
    expectClassicBanner(images());
  });
});

describe('alto mínimo con imagen de fondo = alto del póster que sustituye', () => {
  /**
   * El póster es 2:3: su alto es 1,5 veces su ancho. Si alguien cambia el ancho
   * del póster sin el alto mínimo, el banner con imagen de fondo dejaría de
   * medir lo mismo que su esqueleto y las filas de debajo saltarían.
   */
  const widths = (classes: string) =>
    Object.fromEntries(classes.split(' ').map((item) => item.match(/^(\w+):w-(\d+)$/)!.slice(1).map(String)));
  const minHeights = (classes: string) =>
    Object.fromEntries(classes.split(' ').map((item) => item.match(/^(\w+):min-h-(\d+)$/)!.slice(1).map(String)));

  it.each([
    ['portada y /series', BANNER_POSTER_SIZE_CLASS, BANNER_BACKDROP_MIN_HEIGHT_CLASS],
    ['página de un título', DETAIL_POSTER_SIZE_CLASS, DETAIL_BACKDROP_MIN_HEIGHT_CLASS],
  ])('%s', (_caso, posterClasses, minHeightClasses) => {
    const posterWidths = widths(posterClasses);
    const heights = minHeights(minHeightClasses);
    expect(Object.keys(heights)).toEqual(Object.keys(posterWidths));
    for (const [breakpoint, width] of Object.entries(posterWidths)) {
      expect(Number(heights[breakpoint])).toBe(Number(width) * 1.5);
    }
  });
});
