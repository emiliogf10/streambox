/**
 * Tests de la validación del formulario de series (`seriesValidation.ts`).
 *
 * Los mensajes se comprueban LITERALMENTE porque son el contrato con el backend
 * (`SeriesRequest`, `ValidSeriesYears`): si el cliente dijera otra cosa, el
 * administrador vería dos textos distintos para el mismo error según quién lo
 * detecte.
 */
import { describe, expect, it } from 'vitest';
import { EMPTY_SERIES_FORM, toSeriesRequest, validateSeriesForm, validateSeriesImageUrl } from './seriesValidation';
import type { SeriesFormValues } from './seriesValidation';

/** Formulario válido; cada test cambia solo lo que prueba. */
function valid(overrides: Partial<SeriesFormValues> = {}): SeriesFormValues {
  return {
    title: 'Dark',
    description: 'Un pueblo alemán y sus secretos.',
    releaseYear: '2017',
    endYear: '2020',
    imageUrl: '/covers/dark.webp',
    genreIds: [1],
    ...overrides,
  };
}

describe('validateSeriesForm', () => {
  it('un formulario válido no tiene errores; el año de fin es opcional', () => {
    expect(validateSeriesForm(valid())).toEqual({});
    expect(validateSeriesForm(valid({ endYear: '' }))).toEqual({});
    expect(validateSeriesForm(valid({ endYear: '   ' }))).toEqual({});
  });

  it('el formulario vacío marca cada campo obligatorio con el mensaje exacto del servidor', () => {
    expect(validateSeriesForm(EMPTY_SERIES_FORM)).toEqual({
      title: 'El título es obligatorio',
      description: 'La descripción es obligatoria',
      releaseYear: 'El año de estreno es obligatorio',
      imageUrl: 'La URL de la imagen es obligatoria',
      genreIds: 'Indica al menos un género',
    });
  });

  it('un título o una sinopsis de solo espacios cuentan como vacíos', () => {
    const errors = validateSeriesForm(valid({ title: '   ', description: '\n\t' }));
    expect(errors.title).toBe('El título es obligatorio');
    expect(errors.description).toBe('La descripción es obligatoria');
  });

  it('longitudes máximas: 150 caracteres de título y 1000 de sinopsis (el límite exacto vale)', () => {
    expect(validateSeriesForm(valid({ title: 't'.repeat(150), description: 'd'.repeat(1000) }))).toEqual({});
    const errors = validateSeriesForm(valid({ title: 't'.repeat(151), description: 'd'.repeat(1001) }));
    expect(errors.title).toBe('El título no puede superar los 150 caracteres');
    expect(errors.description).toBe('La descripción no puede superar los 1000 caracteres');
  });

  it.each(['1887', '2101', 'abc', '20.5', '-2000', '99999999999'])('año de estreno «%s» fuera de rango', (year) => {
    expect(validateSeriesForm(valid({ releaseYear: year, endYear: '' })).releaseYear).toBe(
      'El año de estreno debe estar entre 1888 y 2100',
    );
  });

  it('los extremos 1888 y 2100 son válidos para los dos años', () => {
    expect(validateSeriesForm(valid({ releaseYear: '1888', endYear: '2100' }))).toEqual({});
  });

  it.each(['1887', '2101', 'dos mil'])('año de fin «%s» fuera de rango', (year) => {
    expect(validateSeriesForm(valid({ endYear: year })).endYear).toBe('El año de finalización debe estar entre 1888 y 2100');
  });

  it('un año de fin anterior al de estreno es un error del año de fin; el mismo año vale', () => {
    expect(validateSeriesForm(valid({ releaseYear: '2019', endYear: '2018' }))).toEqual({
      endYear: 'El año de finalización no puede ser anterior al año de estreno',
    });
    expect(validateSeriesForm(valid({ releaseYear: '2019', endYear: '2019' }))).toEqual({});
  });

  it('con el estreno vacío o inválido no se compara: un solo error por campo, como en el servidor', () => {
    expect(validateSeriesForm(valid({ releaseYear: '', endYear: '1990' }))).toEqual({
      releaseYear: 'El año de estreno es obligatorio',
    });
    expect(validateSeriesForm(valid({ releaseYear: '3000', endYear: '1990' }))).toEqual({
      releaseYear: 'El año de estreno debe estar entre 1888 y 2100',
    });
  });

  it('géneros: 0 es un error, 20 es el máximo y 21 se rechaza con el mensaje de series', () => {
    expect(validateSeriesForm(valid({ genreIds: [] })).genreIds).toBe('Indica al menos un género');
    const twenty = Array.from({ length: 20 }, (_, index) => index + 1);
    expect(validateSeriesForm(valid({ genreIds: twenty }))).toEqual({});
    expect(validateSeriesForm(valid({ genreIds: [...twenty, 21] })).genreIds).toBe(
      'Una serie puede tener como máximo 20 géneros',
    );
  });
});

describe('validateSeriesImageUrl', () => {
  const FORMAT = 'La URL de la imagen debe empezar por https:// o ser una portada propia (/covers/archivo)';

  it('vacía: mensaje propio de series', () => {
    expect(validateSeriesImageUrl('')).toBe('La URL de la imagen es obligatoria');
  });

  it.each(['https://img.example.com/a.webp', '/covers/dark.webp'])('acepta %s', (url) => {
    expect(validateSeriesImageUrl(url)).toBeUndefined();
  });

  it.each([
    'http://img.example.com/a.webp',
    'javascript:alert(1)',
    'https://user:pw@img.example.com/a.webp',
    '/covers/../secreto',
    'https:///sin-host',
  ])('rechaza %s con el mismo mensaje que películas', (url) => {
    expect(validateSeriesImageUrl(url)).toBe(FORMAT);
  });

  it('más de 500 caracteres: solo avisa de la longitud', () => {
    expect(validateSeriesImageUrl(`https://img.example.com/${'a'.repeat(480)}`)).toBe(
      'La URL de la imagen no puede superar los 500 caracteres',
    );
  });
});

describe('toSeriesRequest', () => {
  it('recorta los textos, pasa los años a número y conserva los géneros', () => {
    expect(
      toSeriesRequest(valid({ title: '  Dark ', description: ' Sinopsis ', imageUrl: ' /covers/dark.webp ', genreIds: [3, 1] })),
    ).toEqual({
      title: 'Dark',
      description: 'Sinopsis',
      releaseYear: 2017,
      endYear: 2020,
      imageUrl: '/covers/dark.webp',
      genreIds: [3, 1],
    });
  });

  it('un año de fin vacío se envía como null (sigue en emisión)', () => {
    expect(toSeriesRequest(valid({ endYear: '  ' })).endYear).toBeNull();
  });
});
