/**
 * Tests de `SeriesHeroBanner`: el botón «Ver tráiler» del banner de `/series`.
 *
 * El resto del banner (datos, «Mi lista») ya lo protege `SeriesPage.test.tsx`.
 * Aquí: que la acción principal sea «Más información» (un enlace a la serie,
 * nunca algo que prometa reproducir), que «Ver tráiler» solo aparezca con
 * tráiler, que sea secundario y que abra el diálogo.
 */
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { FavoritesProvider } from '../context/FavoritesContext';
import type { Series } from '../lib/types';
import { jsonResponse, makeSeries, renderWithProviders, routeFetch } from '../test/helpers';
import { SeriesHeroBanner } from './SeriesHeroBanner';

const fetchMock = vi.fn<typeof fetch>();
const TRAILER = 'https://www.youtube.com/watch?v=zSWdZVtXT7E';

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
  routeFetch(fetchMock, { 'GET /api/users/me/favorites': () => jsonResponse([]) });
});

function renderBanner(series: Series) {
  renderWithProviders(
    <FavoritesProvider>
      <SeriesHeroBanner series={series} />
    </FavoritesProvider>,
    { session: true },
  );
  return screen.getByRole('region', { name: series.title });
}

describe('SeriesHeroBanner: tráiler', () => {
  it('sin tráiler: solo «Más información» (principal, enlace a la serie) y «Mi lista»', () => {
    const banner = renderBanner(makeSeries({ id: 3, title: 'Dark', trailerUrl: null }));

    const moreInfo = within(banner).getByRole('link', { name: /^Más información\s*sobre Dark$/ });
    expect(moreInfo).toHaveAttribute('href', '/series/3');
    expect(moreInfo).toHaveClass('bg-white');
    expect(within(banner).getByRole('button', { name: /^Mi lista/ })).toBeInTheDocument();
    expect(within(banner).queryByRole('button', { name: /Ver tráiler/ })).not.toBeInTheDocument();
    // Nada promete reproducir: ni «Ver episodios» (se leía como «reproducir») ni «Ver ahora».
    expect(within(banner).queryByRole('link', { name: /Ver episodios|Ver ahora/ })).not.toBeInTheDocument();
    expect(within(banner).queryByRole('button', { name: /Ver ahora/ })).not.toBeInTheDocument();
  });

  it('con tráiler: «Ver tráiler» secundaria tras «Más información», y abre el diálogo', async () => {
    const user = userEvent.setup();
    const banner = renderBanner(makeSeries({ id: 3, title: 'Dark', trailerUrl: TRAILER }));

    const moreInfo = within(banner).getByRole('link', { name: /^Más información\s*sobre Dark$/ });
    const trailer = within(banner).getByRole('button', { name: 'Ver tráiler de Dark' });
    expect(moreInfo).toHaveClass('bg-white');
    expect(trailer).toHaveClass('border');
    expect(moreInfo.compareDocumentPosition(trailer) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();

    await user.click(trailer);
    expect(screen.getByRole('dialog', { name: 'Tráiler de Dark' })).toBeInTheDocument();
  });
});
