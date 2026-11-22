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
  detallePelicula: queryKeys.movies.detail(5),
  panelPeliculas: queryKeys.movies.admin('', 1),
  similares: queryKeys.movies.similar(5, 12),
  series: queryKeys.paged('/series', params),
  filtrosSeries: queryKeys.paged('/series/search', { ...params, releaseYear: 2016 }),
  detalleSerie: queryKeys.series.detail(3),
  panelSeries: queryKeys.series.admin('dark', 2),
  generos: queryKeys.genres.all,
  generosConPeliculas: queryKeys.genres.withTitles('movies'),
  generosConSeries: queryKeys.genres.withTitles('series'),
  panelGeneros: queryKeys.genres.admin,
  miLista: queryKeys.favorites.all,
  meGusta: queryKeys.likes.all,
  misGeneros: queryKeys.favoriteGenres.mine,
  generosElegibles: queryKeys.favoriteGenres.options,
  recomendaciones: queryKeys.recommendations.list(12),
  progresoPelicula: queryKeys.progress.movie(5),
  progresoEpisodio: queryKeys.progress.episode(9),
  progresoSerie: queryKeys.progress.series(3),
  seguirViendo: queryKeys.progress.continueWatching(10),
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
  it('una película cambia los listados de películas (con y sin filtros, y el del panel), su página, sus similares, los géneros con películas, los recuentos del panel, los datos personales con copias de títulos y el progreso («Seguir viendo»)', async () => {
    expect(await invalidatedBy('movies')).toEqual([
      'portada',
      'filtros',
      'detallePelicula',
      'panelPeliculas',
      'similares',
      'generosConPeliculas',
      'panelGeneros',
      'miLista',
      'meGusta',
      'misGeneros',
      'generosElegibles',
      'recomendaciones',
      'progresoPelicula',
      'progresoEpisodio',
      'progresoSerie',
      'seguirViendo',
    ]);
  });

  it('una serie o un episodio cambian los listados de series (con y sin filtros), su detalle, el panel, los géneros con series, los recuentos del panel, los datos personales y el progreso (un episodio que pierde el vídeo sale de «Seguir viendo»)', async () => {
    expect(await invalidatedBy('series')).toEqual([
      'series',
      'filtrosSeries',
      'detalleSerie',
      'panelSeries',
      'generosConSeries',
      'panelGeneros',
      'miLista',
      'meGusta',
      'misGeneros',
      'generosElegibles',
      'recomendaciones',
      'progresoPelicula',
      'progresoEpisodio',
      'progresoSerie',
      'seguirViendo',
    ]);
  });

  it('un género cambia todo lo que lleva nombres de géneros (los filtros incluidos), salvo la lista completa y la del panel (ya las actualiza el panel)', async () => {
    expect(await invalidatedBy('genres')).toEqual([
      'portada',
      'filtros',
      'detallePelicula',
      'panelPeliculas',
      'similares',
      'series',
      'filtrosSeries',
      'detalleSerie',
      'panelSeries',
      'generosConPeliculas',
      'generosConSeries',
      'miLista',
      'meGusta',
      'misGeneros',
      'generosElegibles',
      'recomendaciones',
    ]);
  });
});

describe('queryKeys.progress', () => {
  it('todo el progreso cuelga de una raíz propia: cada título, cada serie y «Seguir viendo» son entradas distintas', () => {
    for (const key of [
      queryKeys.progress.movie(1),
      queryKeys.progress.episode(1),
      queryKeys.progress.series(1),
      queryKeys.progress.continueWatching(10),
    ]) {
      expect(key.slice(0, 1)).toEqual(queryKeys.progress.all);
    }
    expect(queryKeys.progress.episode(1).slice(0, 2)).toEqual(queryKeys.progress.episodesAll);
    expect(queryKeys.progress.continueWatching(10).slice(0, 2)).toEqual(queryKeys.progress.continueWatchingAll);
    expect(queryKeys.progress.movie(1)).not.toEqual(queryKeys.progress.episode(1));
  });
});

describe('queryKeys.favoriteGenres', () => {
  it('los géneros favoritos y sus opciones cuelgan de su propia raíz, fuera de la de los géneros del catálogo', () => {
    expect(queryKeys.favoriteGenres.mine.slice(0, 1)).toEqual(queryKeys.favoriteGenres.all);
    expect(queryKeys.favoriteGenres.options.slice(0, 1)).toEqual(queryKeys.favoriteGenres.all);
    expect(queryKeys.favoriteGenres.all).not.toEqual(queryKeys.genres.all);
  });
});

describe('queryKeys.genres', () => {
  it('cada lista de géneros es una entrada distinta: la completa, la de cada catálogo y la del panel', () => {
    const client = new QueryClient();
    client.setQueryData(queryKeys.genres.withTitles('movies'), 'con películas');

    expect(client.getQueryData(queryKeys.genres.withTitles('series'))).toBeUndefined();
    expect(client.getQueryData(queryKeys.genres.all)).toBeUndefined();
    expect(client.getQueryData(queryKeys.genres.admin)).toBeUndefined();
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
