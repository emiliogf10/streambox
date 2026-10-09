/**
 * Tests de las claves de la caché (`lib/queryKeys.ts`).
 *
 * Protegen la jerarquía de la que depende la invalidación: si un listado
 * colgara de una raíz equivocada (p. ej. `/movies/search` fuera de `movies`),
 * editar una película desde el panel no lo pondría al día y seguiría enseñando
 * el título viejo hasta recargar. Se comprueba con un `QueryClient` de verdad:
 * qué entradas quedan invalidadas tras cada tipo de cambio.
 */
import { QueryClient } from '@tanstack/react-query';
import type { QueryKey } from '@tanstack/react-query';
import { describe, expect, it } from 'vitest';
import { keysAffectedBy, queryKeys } from './queryKeys';
import type { CatalogChange } from './queryKeys';

const params = { pageSize: 20, sort: 'createdAt', direction: 'desc' as const };

/** Todas las entradas que tiene la aplicación, con un nombre para leer el resultado. */
const ENTRIES: Record<string, QueryKey> = {
  portada: queryKeys.paged('/movies', params),
  filtros: queryKeys.paged('/movies/search', { ...params, genreId: 4 }),
  panelPeliculas: queryKeys.movies.admin('', 1),
  series: queryKeys.paged('/series', params),
  detalleSerie: queryKeys.series.detail(3),
  panelSeries: queryKeys.series.admin('dark', 2),
  generos: queryKeys.genres.all,
  miLista: queryKeys.favorites.all,
};

/** Nombres de las entradas que quedan invalidadas tras `change`. */
async function invalidatedBy(change: CatalogChange): Promise<string[]> {
  const client = new QueryClient();
  for (const key of Object.values(ENTRIES)) client.setQueryData(key, 'dato');
  await Promise.all(keysAffectedBy(change).map((queryKey) => client.invalidateQueries({ queryKey })));
  return Object.entries(ENTRIES)
    .filter(([, key]) => client.getQueryState(key)?.isInvalidated)
    .map(([name]) => name);
}

describe('keysAffectedBy', () => {
  it('una película cambia los listados de películas (con y sin filtros, y el del panel) y «Mi lista»', async () => {
    expect(await invalidatedBy('movies')).toEqual(['portada', 'filtros', 'panelPeliculas', 'miLista']);
  });

  it('una serie o un episodio cambian los listados de series, su detalle, el panel y «Mi lista»', async () => {
    expect(await invalidatedBy('series')).toEqual(['series', 'detalleSerie', 'panelSeries', 'miLista']);
  });

  it('un género cambia todo lo que lleva nombres de géneros, salvo su propia lista (ya la actualiza el panel)', async () => {
    expect(await invalidatedBy('genres')).toEqual([
      'portada',
      'filtros',
      'panelPeliculas',
      'series',
      'detalleSerie',
      'panelSeries',
      'miLista',
    ]);
  });
});

describe('queryKeys.paged', () => {
  it('cada combinación de endpoint, tamaño y filtros es una entrada distinta', () => {
    const client = new QueryClient();
    client.setQueryData(queryKeys.paged('/movies/search', { ...params, genreId: 4 }), 'drama');

    expect(client.getQueryData(queryKeys.paged('/movies/search', { ...params, genreId: 7 }))).toBeUndefined();
    expect(client.getQueryData(queryKeys.paged('/movies/search', { ...params, genreId: 4, pageSize: 1 }))).toBeUndefined();
    // El orden de las propiedades no importa: TanStack compara las claves por contenido.
    expect(
      client.getQueryData(queryKeys.paged('/movies/search', { genreId: 4, direction: 'desc', sort: 'createdAt', pageSize: 20 })),
    ).toBe('drama');
  });
});
