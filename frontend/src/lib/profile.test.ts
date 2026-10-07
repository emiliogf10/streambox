/**
 * Tests de las funciones puras del perfil (`lib/profile.ts`).
 *
 * Protegen el recuento de géneros (agrupa por id, orden por frecuencia y luego
 * por nombre, sin contar dos veces un género repetido en un mismo título), la
 * elección de los títulos que enseña «De tu lista» (primero películas), la fecha
 * de alta en español y en UTC, la inicial del avatar y la etiqueta del rol.
 */
import { describe, expect, it } from 'vitest';
import { makeMovie, makeSeries } from '../test/helpers';
import { avatarInitial, countGenres, formatMemberSince, LIST_PREVIEW_SIZE, pickListPreview, roleLabel } from './profile';

const drama = { id: 1, name: 'Drama' };
const comedy = { id: 2, name: 'Comedia' };
const scifi = { id: 3, name: 'Ciencia ficción' };

describe('countGenres', () => {
  it('sin títulos o sin géneros devuelve una lista vacía', () => {
    expect(countGenres([])).toEqual([]);
    expect(countGenres([makeMovie({ genres: [] }), makeSeries({ genres: [] })])).toEqual([]);
  });

  it('cuenta los títulos de cada género y ordena de más a menos', () => {
    const result = countGenres([
      makeMovie({ id: 1, genres: [drama, scifi] }),
      makeMovie({ id: 2, genres: [drama] }),
      makeSeries({ id: 1, genres: [drama, comedy] }),
      makeSeries({ id: 2, genres: [comedy] }),
    ]);

    expect(result).toEqual([
      { id: 1, name: 'Drama', count: 3 },
      { id: 2, name: 'Comedia', count: 2 },
      { id: 3, name: 'Ciencia ficción', count: 1 },
    ]);
  });

  it('desempata por nombre (A–Z, con las reglas del español) y, si aun así empatan, por id', () => {
    const accented = { id: 10, name: 'Épico' };
    const western = { id: 11, name: 'Western' };
    const result = countGenres([makeMovie({ genres: [western, accented, drama] })]);

    // «Épico» va entre «Drama» y «Western»: con una comparación por código quedaría al final.
    expect(result.map((genre) => genre.name)).toEqual(['Drama', 'Épico', 'Western']);

    const twins = countGenres([makeMovie({ genres: [{ id: 21, name: 'Igual' }, { id: 20, name: 'Igual' }] })]);
    expect(twins.map((genre) => genre.id)).toEqual([20, 21]);
  });

  it('agrupa por id, no por nombre, y un género repetido dentro de un mismo título cuenta una vez', () => {
    const result = countGenres([
      makeMovie({ id: 1, genres: [drama, drama] }),
      makeSeries({ id: 1, genres: [{ id: 1, name: 'Drama (renombrado)' }] }),
    ]);

    expect(result).toEqual([{ id: 1, name: 'Drama', count: 2 }]);
  });
});

describe('pickListPreview', () => {
  const movies = [1, 2, 3, 4, 5, 6, 7].map((id) => makeMovie({ id }));
  const series = [1, 2, 3].map((id) => makeSeries({ id }));

  it('con muchas películas, solo las primeras (la más reciente primero) y ninguna serie', () => {
    const result = pickListPreview(movies, series);

    expect(result).toHaveLength(LIST_PREVIEW_SIZE);
    expect(result.every((entry) => entry.kind === 'movie')).toBe(true);
    expect(result.map((entry) => entry.item.id)).toEqual([1, 2, 3, 4, 5]);
  });

  it('si sobran huecos, completa con series (después de las películas)', () => {
    const result = pickListPreview(movies.slice(0, 2), series);

    expect(result.map((entry) => `${entry.kind}:${entry.item.id}`)).toEqual([
      'movie:1',
      'movie:2',
      'series:1',
      'series:2',
      'series:3',
    ]);
  });

  it('sin películas enseña solo series, y con las dos listas vacías no enseña nada', () => {
    expect(pickListPreview([], series).map((entry) => entry.kind)).toEqual(['series', 'series', 'series']);
    expect(pickListPreview([], [])).toEqual([]);
  });

  it('respeta un límite distinto', () => {
    expect(pickListPreview(movies, series, 2)).toHaveLength(2);
    expect(pickListPreview(movies, series, 0)).toEqual([]);
  });
});

describe('formatMemberSince', () => {
  it('da mes y año en español', () => {
    expect(formatMemberSince('2026-10-03T08:15:00Z')).toBe('octubre de 2026');
    expect(formatMemberSince('2026-01-01T10:00:00Z')).toBe('enero de 2026');
  });

  it('usa UTC: el último minuto de un mes no pasa al siguiente por el huso del navegador', () => {
    expect(formatMemberSince('2026-10-31T23:59:00Z')).toBe('octubre de 2026');
    expect(formatMemberSince('2026-11-01T00:00:00Z')).toBe('noviembre de 2026');
  });

  it('con una fecha inválida devuelve null (nunca «Invalid Date»)', () => {
    expect(formatMemberSince('no es una fecha')).toBeNull();
    expect(formatMemberSince('')).toBeNull();
  });
});

describe('avatarInitial', () => {
  it('es la primera letra en mayúscula', () => {
    expect(avatarInitial('ana')).toBe('A');
    expect(avatarInitial('Emilio')).toBe('E');
    expect(avatarInitial('émile')).toBe('É');
  });

  it('ignora los espacios iniciales y no parte un carácter de dos unidades (emoji)', () => {
    expect(avatarInitial('  luis')).toBe('L');
    expect(avatarInitial('🎬cine')).toBe('🎬');
  });

  it('con un nombre vacío devuelve «?»', () => {
    expect(avatarInitial('')).toBe('?');
    expect(avatarInitial('   ')).toBe('?');
  });
});

describe('roleLabel', () => {
  it('traduce los dos roles', () => {
    expect(roleLabel('ADMIN')).toBe('Administrador');
    expect(roleLabel('USER')).toBe('Usuario');
  });
});
