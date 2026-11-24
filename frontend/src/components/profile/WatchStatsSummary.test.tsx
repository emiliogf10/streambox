/**
 * Tests de las cifras de lo visto del perfil (`WatchStatsSummary`) con el hook
 * real `useWatchStats` y `fetch` simulado.
 *
 * Protegen que las cifras no mientan: ni un «0» mientras cargan o si fallan
 * (silueta anunciada; error con «Reintentar» que no pierde el foco), los ceros
 * solo cuando el servidor los da, singular y plural, y el tiempo como
 * estimación («aprox.», redondeado hacia abajo, con su forma entera para el
 * lector de pantalla).
 */
import { screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  WATCH_STATS,
  errorResponse,
  jsonResponse,
  makeWatchStats,
  renderWithProviders,
  routeFetch,
} from '../../test/helpers';
import { WatchStatsSummary } from './WatchStatsSummary';

const fetchMock = vi.fn<typeof fetch>();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

/** Región de las cifras. */
const region = () => screen.getByRole('region', { name: 'Resumen de lo que has visto' });

/**
 * Pares «etiqueta → valor» de la lista de descripción, como los lee un lector
 * de pantalla (el valor por su texto accesible: sin lo oculto con `aria-hidden`).
 */
function readStats(): Record<string, string> {
  const pairs: Record<string, string> = {};
  for (const term of within(region()).getAllByRole('term')) {
    const definition = term.nextElementSibling as HTMLElement;
    const hidden = definition.querySelector('[aria-hidden="true"]');
    const spoken = hidden ? definition.querySelector('.sr-only')?.textContent : definition.textContent;
    pairs[term.textContent ?? ''] = spoken ?? '';
  }
  return pairs;
}

describe('WatchStatsSummary', () => {
  it('mientras cargan: una silueta anunciada y ningún número', () => {
    routeFetch(fetchMock, { [WATCH_STATS]: () => new Promise<Response>(() => {}) });
    renderWithProviders(<WatchStatsSummary />, { session: true });

    expect(within(region()).getByRole('status')).toHaveTextContent('Cargando las cifras de lo que has visto...');
    expect(within(region()).queryByRole('term')).not.toBeInTheDocument();
    expect(region()).not.toHaveTextContent(/\d/);
  });

  it('con las cifras: películas y episodios vistos y el tiempo, en plural', async () => {
    routeFetch(fetchMock, {
      [WATCH_STATS]: () => jsonResponse(makeWatchStats({ finishedMovies: 3, finishedEpisodes: 12, watchedSeconds: 5400 })),
    });
    renderWithProviders(<WatchStatsSummary />, { session: true });

    await within(region()).findAllByRole('term');
    expect(readStats()).toEqual({
      'Películas vistas': '3',
      'Episodios vistos': '12',
      'Tiempo visto (aprox.)': '1,5 horas',
    });
    // A la vista, la forma corta.
    expect(within(region()).getByText('1,5 h')).toHaveAttribute('aria-hidden', 'true');
  });

  it('en singular con uno de cada', async () => {
    routeFetch(fetchMock, {
      [WATCH_STATS]: () => jsonResponse(makeWatchStats({ finishedMovies: 1, finishedEpisodes: 1, watchedSeconds: 3600 })),
    });
    renderWithProviders(<WatchStatsSummary />, { session: true });

    await within(region()).findAllByRole('term');
    expect(readStats()).toEqual({
      'Película vista': '1',
      'Episodio visto': '1',
      'Tiempo visto (aprox.)': '1 hora',
    });
  });

  it('quien no ha visto nada: ceros de verdad (los da el servidor), en plural', async () => {
    routeFetch(fetchMock, { [WATCH_STATS]: () => jsonResponse(makeWatchStats()) });
    renderWithProviders(<WatchStatsSummary />, { session: true });

    await within(region()).findAllByRole('term');
    expect(readStats()).toEqual({
      'Películas vistas': '0',
      'Episodios vistos': '0',
      'Tiempo visto (aprox.)': '0 minutos',
    });
  });

  it('menos de un minuto se ve «<1 min» y se lee «menos de 1 minuto»', async () => {
    routeFetch(fetchMock, { [WATCH_STATS]: () => jsonResponse(makeWatchStats({ watchedSeconds: 42 })) });
    renderWithProviders(<WatchStatsSummary />, { session: true });

    expect(await within(region()).findByText('<1 min')).toBeInTheDocument();
    expect(readStats()['Tiempo visto (aprox.)']).toBe('menos de 1 minuto');
  });

  it('si fallan: el motivo y «Reintentar» (sin números); al pulsarlo, el foco se queda en la sección y llegan las cifras', async () => {
    routeFetch(fetchMock, { [WATCH_STATS]: () => errorResponse(500, 'INTERNAL_ERROR', 'boom') });
    const user = userEvent.setup();
    renderWithProviders(<WatchStatsSummary />, { session: true });

    const alert = await within(region()).findByRole('alert');
    expect(alert).toHaveTextContent('No se pudieron cargar las cifras de lo que has visto.');
    expect(alert).toHaveTextContent('El servidor ha tenido un problema. Inténtalo de nuevo en unos minutos.');
    expect(within(region()).queryByRole('term')).not.toBeInTheDocument();

    routeFetch(fetchMock, {
      [WATCH_STATS]: () => jsonResponse(makeWatchStats({ finishedMovies: 2, watchedSeconds: 7200 })),
    });
    await user.click(within(region()).getByRole('button', { name: 'Reintentar' }));

    await within(region()).findAllByRole('term');
    expect(readStats()['Películas vistas']).toBe('2');
    expect(document.activeElement).not.toBe(document.body);
    expect(region()).toContainElement(document.activeElement as HTMLElement);
  });
});
