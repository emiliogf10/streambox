/**
 * Tests de `lib/movieFilters.ts`: la traducción de los filtros de `/peliculas`
 * entre la URL, el estado y la API.
 *
 * Protegen: que los valores inválidos de la URL se ignoren (sin romper ni mandar
 * basura al servidor), que la URL que se escribe sea siempre la mínima y la
 * misma para los mismos filtros, cuándo hay "filtros activos" y que cada orden
 * llegue a la API con el campo y la dirección correctos.
 */
import { describe, expect, it } from 'vitest';
import {
  DEFAULT_MOVIE_SORT,
  MOVIE_SORT_OPTIONS,
  NO_MOVIE_FILTERS,
  hasActiveFilters,
  movieFiltersToQuery,
  movieFiltersToSearchParams,
  parseMovieFilters,
  parseReleaseYear,
  sameMovieFilters,
} from './movieFilters';

const parse = (query: string) => parseMovieFilters(new URLSearchParams(query));

describe('parseMovieFilters', () => {
  it('lee género, año y orden de la URL', () => {
    expect(parse('genero=4&anio=2014&orden=titulo-asc')).toEqual({ genreId: 4, year: 2014, sort: 'titulo-asc' });
  });

  it('sin parámetros: sin filtros y el orden por defecto («Más recientes»)', () => {
    expect(parse('')).toEqual(NO_MOVIE_FILTERS);
    expect(NO_MOVIE_FILTERS.sort).toBe('recientes');
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
    expect(parse('orden=xyz&genero=2')).toEqual({ genreId: 2, year: null, sort: DEFAULT_MOVIE_SORT });
  });
});

describe('parseReleaseYear', () => {
  it('solo acepta cuatro cifras dentro del rango', () => {
    expect(parseReleaseYear('1999')).toBe(1999);
    expect(parseReleaseYear('199')).toBeNull();
    expect(parseReleaseYear(null)).toBeNull();
  });
});

describe('movieFiltersToSearchParams', () => {
  it('escribe solo lo que se usa, siempre en el mismo orden', () => {
    expect(movieFiltersToSearchParams({ genreId: 4, year: 2014, sort: 'anio-desc' }).toString()).toBe(
      'genero=4&anio=2014&orden=anio-desc',
    );
    expect(movieFiltersToSearchParams({ genreId: null, year: 1999, sort: DEFAULT_MOVIE_SORT }).toString()).toBe('anio=1999');
  });

  it('sin filtros y con el orden por defecto la URL queda limpia', () => {
    expect(movieFiltersToSearchParams(NO_MOVIE_FILTERS).toString()).toBe('');
  });

  it('ida y vuelta: leer lo escrito devuelve los mismos filtros', () => {
    const filters = { genreId: 12, year: 1985, sort: 'duracion-desc' } as const;
    expect(parseMovieFilters(movieFiltersToSearchParams(filters))).toEqual(filters);
  });
});

describe('hasActiveFilters y sameMovieFilters', () => {
  it('cualquier filtro, o un orden distinto del de por defecto, cuenta como activo', () => {
    expect(hasActiveFilters(NO_MOVIE_FILTERS)).toBe(false);
    expect(hasActiveFilters({ ...NO_MOVIE_FILTERS, genreId: 1 })).toBe(true);
    expect(hasActiveFilters({ ...NO_MOVIE_FILTERS, year: 2000 })).toBe(true);
    expect(hasActiveFilters({ ...NO_MOVIE_FILTERS, sort: 'titulo-asc' })).toBe(true);
  });

  it('compara por valor', () => {
    expect(sameMovieFilters({ ...NO_MOVIE_FILTERS }, NO_MOVIE_FILTERS)).toBe(true);
    expect(sameMovieFilters({ ...NO_MOVIE_FILTERS, year: 2000 }, NO_MOVIE_FILTERS)).toBe(false);
  });
});

describe('movieFiltersToQuery', () => {
  it.each([
    ['recientes', 'createdAt', 'desc'],
    ['titulo-asc', 'title', 'asc'],
    ['titulo-desc', 'title', 'desc'],
    ['anio-desc', 'releaseYear', 'desc'],
    ['anio-asc', 'releaseYear', 'asc'],
    ['duracion-asc', 'duration', 'asc'],
    ['duracion-desc', 'duration', 'desc'],
  ] as const)('«%s» se pide como sort=%s&direction=%s', (key, sort, direction) => {
    expect(movieFiltersToQuery({ ...NO_MOVIE_FILTERS, sort: key })).toEqual({ sort, direction });
  });

  it('pasa el género y el año con los nombres de la API (genreId, releaseYear)', () => {
    expect(movieFiltersToQuery({ genreId: 3, year: 2010, sort: 'titulo-asc' })).toEqual({
      sort: 'title',
      direction: 'asc',
      genreId: 3,
      releaseYear: 2010,
    });
  });

  it('el desplegable ofrece los siete órdenes con sus textos, «Más recientes» primero', () => {
    expect(MOVIE_SORT_OPTIONS.map((option) => option.label)).toEqual([
      'Más recientes',
      'Título A–Z',
      'Título Z–A',
      'Año: más nuevas',
      'Año: más antiguas',
      'Duración: más cortas',
      'Duración: más largas',
    ]);
  });
});
