/**
 * Tests de `HeroBanner`.
 *
 * Protegen las decisiones del diseño "fondo desenfocado + póster nítido": que
 * ambos salgan de la MISMA URL (una sola descarga), que el fondo sea decorativo
 * para los lectores de pantalla, que la sinopsis aparezca recortada (la completa
 * está en "Más información") y que sin portada no haya ninguna imagen rota.
 */
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FavoritesProvider } from '../context/FavoritesContext';
import { jsonResponse, makeMovie, renderWithProviders, routeFetch } from '../test/helpers';
import type { Movie } from '../lib/types';
import { BANNER_GRID_CLASS } from './bannerGridStyles';
import { HeroBanner } from './HeroBanner';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  routeFetch(fetchMock, { 'GET /api/users/me/favorites': () => jsonResponse([]) });
});

function renderBanner(movie: Movie, onDetails = vi.fn()) {
  renderWithProviders(
    <FavoritesProvider>
      <HeroBanner movie={movie} onDetails={onDetails} />
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
    expect(within(banner).getByRole('button', { name: /^Más información/ })).not.toHaveClass('border');
  });

  it('«Más información» abre los detalles de la película', async () => {
    const onDetails = vi.fn();
    const movie = makeMovie({ id: 6, title: 'Tenet' });
    const banner = renderBanner(movie, onDetails);

    await userEvent.setup().click(within(banner).getByRole('button', { name: /^Más información\s*sobre Tenet$/ }));

    expect(onDetails).toHaveBeenCalledWith(movie);
  });
});
