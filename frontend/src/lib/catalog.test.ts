/**
 * Tests de las funciones puras de la portada (`mergeById`, `buildCatalogRows`).
 *
 * Protegen que "Cargar más" nunca duplique películas ni reordene lo ya mostrado,
 * y que las filas por género sigan las reglas de la portada (mínimo de 3,
 * orden por tamaño y luego por nombre).
 */
import { describe, expect, it } from 'vitest';
import { MIN_MOVIES_PER_GENRE_ROW, NEWS_ROW_SIZE, buildCatalogRows, mergeById } from './catalog';
import type { Genre } from './types';
import { makeMovie } from '../test/helpers';

const accion: Genre = { id: 1, name: 'Acción' };
const drama: Genre = { id: 2, name: 'Drama' };
const comedia: Genre = { id: 3, name: 'Comedia' };

/** Crea `count` películas con ids consecutivos desde `startId`, todas del género dado. */
function moviesOf(genre: Genre[], count: number, startId: number) {
  return Array.from({ length: count }, (_, i) => makeMovie({ id: startId + i, genres: genre }));
}

describe('mergeById', () => {
  it('añade al final solo las películas nuevas', () => {
    const merged = mergeById([makeMovie({ id: 1 }), makeMovie({ id: 2 })], [makeMovie({ id: 3 }), makeMovie({ id: 4 })]);

    expect(merged.map((m) => m.id)).toEqual([1, 2, 3, 4]);
  });

  it('descarta duplicados con la lista actual y dentro de la propia entrada, conservando la primera aparición', () => {
    const original = makeMovie({ id: 2, title: 'Original' });
    const merged = mergeById(
      [makeMovie({ id: 1 }), original],
      [makeMovie({ id: 2, title: 'Copia' }), makeMovie({ id: 3, title: 'A' }), makeMovie({ id: 3, title: 'B' })],
    );

    expect(merged.map((m) => m.id)).toEqual([1, 2, 3]);
    expect(merged[1]).toBe(original);
    expect(merged[2].title).toBe('A');
  });

  it('no muta ninguna de las listas de entrada', () => {
    const current = [makeMovie({ id: 1 })];
    const incoming = [makeMovie({ id: 2 })];

    const merged = mergeById(current, incoming);

    expect(current).toHaveLength(1);
    expect(incoming).toHaveLength(1);
    expect(merged).not.toBe(current);
  });

  it('si no hay nada nuevo devuelve la MISMA lista (React no vuelve a renderizar)', () => {
    const current = [makeMovie({ id: 1 })];

    expect(mergeById(current, [makeMovie({ id: 1 })])).toBe(current);
    expect(mergeById(current, [])).toBe(current);
  });

  it('desde una lista vacía deduplica la primera página', () => {
    expect(mergeById([], [makeMovie({ id: 5 }), makeMovie({ id: 5 })])).toHaveLength(1);
  });
});

describe('buildCatalogRows', () => {
  it('sin películas no hay filas', () => {
    expect(buildCatalogRows([])).toEqual([]);
  });

  it('la primera fila es "Novedades" y respeta el orden recibido', () => {
    const movies = [makeMovie({ id: 9 }), makeMovie({ id: 8 }), makeMovie({ id: 7 })];

    const [news] = buildCatalogRows(movies);

    expect(news).toMatchObject({ id: 'news', title: 'Novedades' });
    expect(news.items.map((m) => m.id)).toEqual([9, 8, 7]);
  });

  it('"Novedades" se limita a NEWS_ROW_SIZE películas', () => {
    const rows = buildCatalogRows(moviesOf([], NEWS_ROW_SIZE + 5, 1));

    expect(rows[0].items).toHaveLength(NEWS_ROW_SIZE);
  });

  it('un género con menos de 3 películas no tiene fila; con 3 sí', () => {
    expect(MIN_MOVIES_PER_GENRE_ROW).toBe(3);

    const withTwo = buildCatalogRows(moviesOf([accion], 2, 1));
    expect(withTwo.map((r) => r.id)).toEqual(['news']);

    const withThree = buildCatalogRows(moviesOf([accion], 3, 1));
    expect(withThree.map((r) => r.id)).toEqual(['news', 'genre-1']);
    expect(withThree[1]).toMatchObject({ title: 'Acción' });
    expect(withThree[1].items).toHaveLength(3);
  });

  it('una película con varios géneros aparece en la fila de cada uno', () => {
    const movies = [
      ...moviesOf([accion, drama], 3, 1),
    ];

    const rows = buildCatalogRows(movies);

    expect(rows.map((r) => r.title)).toEqual(['Novedades', 'Acción', 'Drama']);
    expect(rows[1].items.map((m) => m.id)).toEqual([1, 2, 3]);
    expect(rows[2].items.map((m) => m.id)).toEqual([1, 2, 3]);
  });

  it('ordena los géneros de más a menos películas y desempata por nombre', () => {
    const movies = [
      ...moviesOf([drama], 3, 1), // Drama: 3
      ...moviesOf([comedia], 5, 10), // Comedia: 5
      ...moviesOf([accion], 3, 20), // Acción: 3 (empata con Drama)
    ];

    const genreTitles = buildCatalogRows(movies)
      .slice(1)
      .map((row) => row.title);

    expect(genreTitles).toEqual(['Comedia', 'Acción', 'Drama']);
  });

  it('el desempate por nombre usa el orden alfabético español (Ñ después de N, acentos ignorados)', () => {
    const enie: Genre = { id: 10, name: 'Ñandú' };
    const ene: Genre = { id: 11, name: 'Nómada' };
    const aguila: Genre = { id: 12, name: 'Águila' };
    const movies = [...moviesOf([enie], 3, 1), ...moviesOf([ene], 3, 10), ...moviesOf([aguila], 3, 20)];

    const genreTitles = buildCatalogRows(movies)
      .slice(1)
      .map((row) => row.title);

    expect(genreTitles).toEqual(['Águila', 'Nómada', 'Ñandú']);
  });

  it('el id de cada fila de género es estable y deriva del id del género', () => {
    const rows = buildCatalogRows(moviesOf([drama], 3, 1));

    expect(rows[1].id).toBe('genre-2');
  });
});
