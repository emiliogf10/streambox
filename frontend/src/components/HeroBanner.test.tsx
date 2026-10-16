/**
 * Tests de `HeroBanner`.
 *
 * Protegen las decisiones del diseño "fondo desenfocado + póster nítido": que
 * ambos salgan de la MISMA URL (una sola descarga), que el fondo sea decorativo
 * para los lectores de pantalla, que la sinopsis aparezca recortada (la completa
 * está en la página de la película, a la que lleva "Más información") y que sin
 * portada no haya ninguna imagen rota.
 */
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FavoritesProvider } from '../context/FavoritesContext';
import { CURRENT_USER, jsonResponse, makeMovie, makeUser, renderWithProviders, routeFetch } from '../test/helpers';
import type { Movie } from '../lib/types';
import { BANNER_GRID_CLASS } from './bannerGridStyles';
import { HeroBanner } from './HeroBanner';

const fetchMock = vi.fn<typeof fetch>();

/** Tráiler con la forma normalizada que devuelve la API. */
const TRAILER = 'https://www.youtube.com/watch?v=dQw4w9WgXcQ';

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  routeFetch(fetchMock, { 'GET /api/users/me/favorites': () => jsonResponse([]) });
});

function renderBanner(movie: Movie) {
  renderWithProviders(
    <FavoritesProvider>
      <HeroBanner movie={movie} />
    </FavoritesProvider>,
    { session: true },
  );
  return screen.getByRole('region', { name: movie.title });
}

describe('HeroBanner', () => {
  it('fondo y póster usan la misma URL, con prioridad alta, y ninguno repite el título a un lector de pantalla', () => {
    const banner = renderBanner(makeMovie({ id: 9, title: 'Dune', imageUrl: 'https://img.example/dune.webp' }));

    const images = banner.querySelectorAll('img');
    expect(images).toHaveLength(2);
    for (const img of images) {
      expect(img).toHaveAttribute('src', 'https://img.example/dune.webp');
      expect(img).toHaveAttribute('alt', '');
      expect(img).toHaveAttribute('fetchpriority', 'high');
    }
    // La capa de fondo entera (imagen + degradados) está fuera del árbol de accesibilidad.
    expect(images[0].closest('[aria-hidden="true"]')).not.toBeNull();
    expect(within(banner).queryByRole('img')).not.toBeInTheDocument();
  });

  it('muestra título (h2), metadatos y la sinopsis recortada a 3 líneas', () => {
    const description = 'Una sinopsis larguísima. '.repeat(30).trim();
    const banner = renderBanner(makeMovie({ id: 3, title: 'Arrival', description, releaseYear: 2016 }));

    expect(within(banner).getByRole('heading', { level: 2, name: 'Arrival' })).toBeInTheDocument();
    expect(within(banner).getByRole('list', { name: 'Datos de la película' })).toHaveTextContent('2016');
    expect(within(banner).getByText(description)).toHaveClass('line-clamp-3');
    // Ya no hay paneles con subtítulos (Sinopsis/Reparto/Ficha) dentro del banner.
    expect(within(banner).queryByRole('heading', { level: 3 })).not.toBeInTheDocument();
  });

  it('sin sinopsis no pinta un párrafo vacío', () => {
    const banner = renderBanner(makeMovie({ id: 4, title: 'Sin texto', description: '' }));

    expect(banner.querySelector('p.line-clamp-3')).toBeNull();
  });

  it('sin portada no hay imágenes rotas: el fondo queda en degradado y el póster muestra el hueco con el título', () => {
    const banner = renderBanner(makeMovie({ id: 5, title: 'Puerto Seco', imageUrl: '' }));

    expect(banner.querySelector('img')).toBeNull();
    // Una sola vez el título en el hueco (el fondo no lo repite) además del h2.
    expect(within(banner).getAllByText('Puerto Seco')).toHaveLength(2);
    expect(within(banner).getByRole('heading', { level: 2, name: 'Puerto Seco' })).toBeInTheDocument();
  });

  it('«Estreno reciente» va con los datos de la película, no como antetítulo encima del título', () => {
    const banner = renderBanner(makeMovie({ id: 7, title: 'Arrival', releaseYear: 2016 }));

    const badge = within(banner).getByText('Estreno reciente');
    const dataList = within(banner).getByRole('list', { name: 'Datos de la película' });
    // Es el primer elemento de la MISMA lista de datos (así comparte fila con el año en 375 px, en vez de
    // quedarse sola en una línea porque la lista entera no cabía a su lado)...
    expect(badge.tagName).toBe('LI');
    expect(badge.parentElement).toBe(dataList);
    expect(dataList.firstElementChild).toBe(badge);
    // ...y el título va ANTES en el orden de lectura: lo primero que oye un lector de pantalla es la película.
    const heading = within(banner).getByRole('heading', { level: 2, name: 'Arrival' });
    expect(heading.compareDocumentPosition(badge) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('su rejilla interior es la constante compartida con el esqueleto de carga', () => {
    const banner = renderBanner(makeMovie({ id: 9, title: 'Arrival' }));

    const grid = banner.querySelector('section > div:not([aria-hidden])');
    expect(grid).toHaveClass(...BANNER_GRID_CLASS.split(' '));
  });

  it('las tres acciones tienen jerarquía: «Más información» es terciaria (sin borde) y las otras no', () => {
    const banner = renderBanner(makeMovie({ id: 8, title: 'Tenet' }));

    expect(within(banner).getByRole('link', { name: /^Ver ahora Tenet/ })).toHaveClass('bg-white');
    expect(within(banner).getByRole('button', { name: /^Mi lista/ })).toHaveClass('border');
    expect(within(banner).getByRole('link', { name: /^Más información/ })).not.toHaveClass('border');
  });

  it('con vídeo y tráiler: «Ver ahora» principal, «Ver tráiler» secundaria (borde), en ese orden', () => {
    const banner = renderBanner(makeMovie({ id: 8, title: 'Tenet', trailerUrl: TRAILER }));

    const watch = within(banner).getByRole('link', { name: /^Ver ahora Tenet/ });
    const trailer = within(banner).getByRole('button', { name: 'Ver tráiler de Tenet' });
    expect(watch).toHaveClass('bg-white');
    expect(trailer).toHaveClass('border');
    expect(trailer).not.toHaveClass('bg-white');
    expect(watch.compareDocumentPosition(trailer) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it('sin vídeo y con tráiler: no hay «Ver ahora» y «Ver tráiler» pasa a ser la acción principal', () => {
    const banner = renderBanner(makeMovie({ id: 8, title: 'Tenet', videoUrl: null, trailerUrl: TRAILER }));

    expect(within(banner).queryByText(/Ver ahora/)).not.toBeInTheDocument();
    expect(within(banner).getByRole('button', { name: 'Ver tráiler de Tenet' })).toHaveClass('bg-white');
    expect(within(banner).getByRole('link', { name: /^Más información/ })).not.toHaveClass('bg-white');
  });

  it('sin vídeo ni tráiler: nada promete «ver»; «Más información» es la principal y va primero', () => {
    const banner = renderBanner(makeMovie({ id: 8, title: 'Tenet', videoUrl: null, trailerUrl: null }));

    expect(within(banner).queryByText(/Ver ahora|Ver tráiler/)).not.toBeInTheDocument();
    const more = within(banner).getByRole('link', { name: /^Más información/ });
    expect(more).toHaveClass('bg-white');
    const favorite = within(banner).getByRole('button', { name: /^Mi lista/ });
    expect(more.compareDocumentPosition(favorite) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  // Incidencia QA trailer-5: algo debe explicar por qué no hay nada que ver.
  it('sin vídeo ni tráiler: un USER lee «Todavía no está disponible para ver.», fuera de la rejilla de acciones', () => {
    const banner = renderBanner(makeMovie({ id: 8, title: 'Tenet', videoUrl: null, trailerUrl: null }));

    const note = within(banner).getByText('Todavía no está disponible para ver.');
    expect(within(banner).getByRole('button', { name: /^Mi lista/ }).parentElement!.contains(note)).toBe(false);
  });

  it('sin vídeo ni tráiler: un ADMIN lee que se añaden desde su página, con «Editar» (el banner no lo tiene)', async () => {
    routeFetch(fetchMock, {
      'GET /api/users/me/favorites': () => jsonResponse([]),
      [CURRENT_USER]: () => jsonResponse(makeUser({ role: 'ADMIN' })),
    });
    const banner = renderBanner(makeMovie({ id: 8, title: 'Tenet', videoUrl: null, trailerUrl: null }));

    expect(
      await within(banner).findByText('No tiene vídeo ni tráiler: puedes añadirlos desde su página, con «Editar».'),
    ).toBeInTheDocument();
  });

  it('con vídeo o con tráiler no hay aviso', () => {
    const banner = renderBanner(makeMovie({ id: 8, title: 'Tenet', videoUrl: null, trailerUrl: TRAILER }));

    expect(within(banner).queryByText(/disponible para ver|No tiene vídeo/)).not.toBeInTheDocument();
  });

  it('un tráiler que no es la forma normalizada de YouTube no pinta «Ver tráiler»', () => {
    const banner = renderBanner(makeMovie({ id: 8, title: 'Tenet', trailerUrl: 'https://vimeo.com/123456' }));

    expect(within(banner).queryByRole('button', { name: /Ver tráiler/ })).not.toBeInTheDocument();
  });

  it('«Más información» es un enlace a la página de la película (no abre un diálogo) y lleva a ella', async () => {
    const banner = renderBanner(makeMovie({ id: 6, title: 'Tenet' }));

    const more = within(banner).getByRole('link', { name: /^Más información\s*sobre Tenet$/ });
    expect(more).toHaveAttribute('href', '/peliculas/6');
    expect(more).not.toHaveAttribute('aria-haspopup');
    await userEvent.setup().click(more);

    expect(screen.getByText('ruta:/peliculas/6')).toBeInTheDocument();
  });
});
