/**
 * Tests de `lib/catalogFilters.ts`: la traducción de los filtros de
 * `/peliculas` y `/series` entre la URL, el estado y la API.
 *
 * Protegen: que los valores inválidos de la URL se ignoren (sin romper ni mandar
 * basura al servidor), que la URL que se escribe sea siempre la mínima y la
 * misma para los mismos filtros, cuándo hay "filtros activos", que cada orden
 * llegue a la API con el campo y la dirección correctos y que `/series` no
 * ofrezca ni mande nunca un orden por duración (su servidor respondería 400).
 */
import { describe, expect, it } from 'vitest';
import {
  DEFAULT_SORT,
  NO_CATALOG_FILTERS,
  SORT_OPTIONS,
  catalogByGenrePath,
  catalogFiltersToQuery,
  catalogFiltersToSearchParams,
  hasActiveFilters,
  parseCatalogFilters,
  parseReleaseYear,
  sameCatalogFilters,
} from './catalogFilters';

const parse = (query: string) => parseCatalogFilters(new URLSearchParams(query), 'movies');
const parseSeries = (query: string) => parseCatalogFilters(new URLSearchParams(query), 'series');

describe('parseCatalogFilters', () => {
  it('lee género, año y orden de la URL', () => {
    expect(parse('genero=4&anio=2014&orden=titulo-asc')).toEqual({ genreId: 4, year: 2014, sort: 'titulo-asc' });
  });

  it('sin parámetros: sin filtros y el orden por defecto («Más recientes»)', () => {
    expect(parse('')).toEqual(NO_CATALOG_FILTERS);
    expect(NO_CATALOG_FILTERS.sort).toBe('recientes');
  });

  it.each([
    ['genero=abc', 'texto'],
    ['genero=0', 'cero'],
    ['genero=-3', 'negativo'],
    ['genero=4.5', 'decimal'],
    ['genero=', 'vacío'],
    ['genero=99999999999999999999', 'más allá de un entero seguro'],
  ])('ignora un género inválido (%s: %s)', (query) => {
    expect(parse(query).genreId).toBeNull();
  });

  it.each(['abc', '99', '1887', '2101', '20140', '2014.0', ' 2014', '2e3', ''])('ignora el año inválido «%s»', (year) => {
    expect(parse(`anio=${encodeURIComponent(year)}`).year).toBeNull();
  });

  it('admite los extremos del rango, 1888 y 2100 (los mismos que el backend)', () => {
    expect(parse('anio=1888').year).toBe(1888);
    expect(parse('anio=2100').year).toBe(2100);
  });

  it('un orden desconocido cuenta como el de por defecto, sin tocar los demás filtros', () => {
    expect(parse('orden=xyz&genero=2')).toEqual({ genreId: 2, year: null, sort: DEFAULT_SORT });
  });

  it('series: los mismos parámetros y valores que las películas', () => {
    expect(parseSeries('genero=4&anio=2014&orden=anio-desc')).toEqual({ genreId: 4, year: 2014, sort: 'anio-desc' });
    expect(parseSeries('genero=abc&anio=99&orden=xyz')).toEqual(NO_CATALOG_FILTERS);
  });

  it.each(['duracion-asc', 'duracion-desc'])(
    'series: un orden por duración (%s) se ignora (una serie no tiene duración); en películas, no',
    (key) => {
      expect(parseSeries(`orden=${key}&genero=3`)).toEqual({ genreId: 3, year: null, sort: DEFAULT_SORT });
      expect(parse(`orden=${key}`).sort).toBe(key);
    },
  );
});

describe('parseReleaseYear', () => {
  it('solo acepta cuatro cifras dentro del rango', () => {
    expect(parseReleaseYear('1999')).toBe(1999);
    expect(parseReleaseYear('199')).toBeNull();
    expect(parseReleaseYear(null)).toBeNull();
  });
});

describe('catalogFiltersToSearchParams', () => {
  it('escribe solo lo que se usa, siempre en el mismo orden', () => {
    expect(catalogFiltersToSearchParams({ genreId: 4, year: 2014, sort: 'anio-desc' }).toString()).toBe(
      'genero=4&anio=2014&orden=anio-desc',
    );
    expect(catalogFiltersToSearchParams({ genreId: null, year: 1999, sort: DEFAULT_SORT }).toString()).toBe('anio=1999');
  });

  it('sin filtros y con el orden por defecto la URL queda limpia', () => {
    expect(catalogFiltersToSearchParams(NO_CATALOG_FILTERS).toString()).toBe('');
  });

  it('ida y vuelta: leer lo escrito devuelve los mismos filtros', () => {
    const filters = { genreId: 12, year: 1985, sort: 'duracion-desc' } as const;
    expect(parse(catalogFiltersToSearchParams(filters).toString())).toEqual(filters);
    const seriesFilters = { genreId: 12, year: 1985, sort: 'titulo-desc' } as const;
    expect(parseSeries(catalogFiltersToSearchParams(seriesFilters).toString())).toEqual(seriesFilters);
  });
});

describe('hasActiveFilters y sameCatalogFilters', () => {
  it('cualquier filtro, o un orden distinto del de por defecto, cuenta como activo', () => {
    expect(hasActiveFilters(NO_CATALOG_FILTERS)).toBe(false);
    expect(hasActiveFilters({ ...NO_CATALOG_FILTERS, genreId: 1 })).toBe(true);
    expect(hasActiveFilters({ ...NO_CATALOG_FILTERS, year: 2000 })).toBe(true);
    expect(hasActiveFilters({ ...NO_CATALOG_FILTERS, sort: 'titulo-asc' })).toBe(true);
  });

  it('compara por valor', () => {
    expect(sameCatalogFilters({ ...NO_CATALOG_FILTERS }, NO_CATALOG_FILTERS)).toBe(true);
    expect(sameCatalogFilters({ ...NO_CATALOG_FILTERS, year: 2000 }, NO_CATALOG_FILTERS)).toBe(false);
  });
});

describe('catalogFiltersToQuery', () => {
  it.each([
    ['recientes', 'createdAt', 'desc'],
    ['titulo-asc', 'title', 'asc'],
    ['titulo-desc', 'title', 'desc'],
    ['anio-desc', 'releaseYear', 'desc'],
    ['anio-asc', 'releaseYear', 'asc'],
    ['duracion-asc', 'duration', 'asc'],
    ['duracion-desc', 'duration', 'desc'],
  ] as const)('películas: «%s» se pide como sort=%s&direction=%s', (key, sort, direction) => {
    expect(catalogFiltersToQuery({ ...NO_CATALOG_FILTERS, sort: key }, 'movies')).toEqual({ sort, direction });
  });

  it('pasa el género y el año con los nombres de la API (genreId, releaseYear)', () => {
    expect(catalogFiltersToQuery({ genreId: 3, year: 2010, sort: 'titulo-asc' }, 'series')).toEqual({
      sort: 'title',
      direction: 'asc',
      genreId: 3,
      releaseYear: 2010,
    });
  });

  it('series: nunca manda sort=duration (400 en el servidor); un orden por duración se pide como el de por defecto', () => {
    expect(catalogFiltersToQuery({ ...NO_CATALOG_FILTERS, sort: 'duracion-desc' }, 'series')).toEqual({
      sort: 'createdAt',
      direction: 'desc',
    });
  });

  it('cada catálogo solo ofrece órdenes de la lista blanca de su servidor', () => {
    // `MovieController.SORTABLE_FIELDS` y `SeriesController.SORTABLE_FIELDS` (sin `id`, que no se ofrece).
    expect(new Set(SORT_OPTIONS.movies.map((option) => option.sort))).toEqual(
      new Set(['createdAt', 'title', 'releaseYear', 'duration']),
    );
    expect(new Set(SORT_OPTIONS.series.map((option) => option.sort))).toEqual(new Set(['createdAt', 'title', 'releaseYear']));
  });

  it('el desplegable de películas ofrece los siete órdenes y el de series los cinco sin duración, «Más recientes» primero', () => {
    expect(SORT_OPTIONS.movies.map((option) => option.label)).toEqual([
      'Más recientes',
      'Título A–Z',
      'Título Z–A',
      'Año: más nuevas',
      'Año: más antiguas',
      'Duración: más cortas',
      'Duración: más largas',
    ]);
    expect(SORT_OPTIONS.series.map((option) => option.label)).toEqual([
      'Más recientes',
      'Título A–Z',
      'Título Z–A',
      'Año: más nuevas',
      'Año: más antiguas',
    ]);
  });
});

describe('catalogByGenrePath', () => {
  it('es la misma URL que escribe la barra de filtros al elegir ese género', () => {
    expect(catalogByGenrePath('movies', 4)).toBe('/peliculas?genero=4');
    expect(catalogByGenrePath('series', 4)).toBe('/series?genero=4');
    expect(catalogByGenrePath('series', 4)).toBe(
      `/series?${catalogFiltersToSearchParams({ ...NO_CATALOG_FILTERS, genreId: 4 }).toString()}`,
    );
  });

  it('se lee de vuelta como ese género y sin más filtros', () => {
    expect(parse(catalogByGenrePath('movies', 12).split('?')[1])).toEqual({ ...NO_CATALOG_FILTERS, genreId: 12 });
    expect(parseSeries(catalogByGenrePath('series', 12).split('?')[1])).toEqual({ ...NO_CATALOG_FILTERS, genreId: 12 });
  });
});
