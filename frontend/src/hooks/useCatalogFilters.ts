import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import type { CatalogFilterBarProps } from '../components/CatalogFilterBar';
import {
  DEFAULT_SORT,
  MAX_RELEASE_YEAR,
  MIN_RELEASE_YEAR,
  NO_CATALOG_FILTERS,
  catalogFiltersToSearchParams,
  hasActiveFilters,
  parseCatalogFilters,
  parseReleaseYear,
  sameCatalogFilters,
} from '../lib/catalogFilters';
import type { CatalogFilters, CatalogKind, CatalogSortKey } from '../lib/catalogFilters';
import { useDebouncedValue } from './useDebouncedValue';
import { useGenres } from './useGenres';

/** Espera tras el último cambio de un filtro antes de aplicarlo (la misma que los buscadores). */
export const CATALOG_FILTER_DEBOUNCE_MS = 300;

/** Mensaje del campo «Año» cuando lo escrito no es un año que se pueda buscar. */
export const YEAR_ERROR_MESSAGE = `Escribe un año entre ${MIN_RELEASE_YEAR} y ${MAX_RELEASE_YEAR}.`;

/**
 * Lo que muestran los controles en este momento, tal cual (texto del campo
 * «Año» incluido, aunque esté a medio escribir). `genre` es el `value` del
 * `<select>`: `''` = «Todos los géneros».
 */
export interface CatalogFilterDraft {
  genre: string;
  year: string;
  sort: CatalogSortKey;
}

/** Controles que reflejan unos filtros ya aplicados. */
function toDraft(filters: CatalogFilters): CatalogFilterDraft {
  return {
    genre: filters.genreId === null ? '' : String(filters.genreId),
    year: filters.year === null ? '' : String(filters.year),
    sort: filters.sort,
  };
}

/**
 * Filtros que resultan de los controles. Un año a medio escribir o fuera de
 * rango NO se aplica: se conserva el último válido (`fallbackYear`). Así, al
 * corregir «2014» a «2013», borrar la última cifra no lanza una búsqueda «sin
 * año» intermedia; el campo marca el error y la lista sigue siendo la de antes.
 */
function fromDraft(draft: CatalogFilterDraft, fallbackYear: number | null): CatalogFilters {
  const year = draft.year.trim();
  return {
    genreId: draft.genre === '' ? null : Number(draft.genre),
    year: year === '' ? null : (parseReleaseYear(year) ?? fallbackYear),
    sort: draft.sort,
  };
}

/** Lo que devuelve {@link useCatalogFilters}. */
export interface CatalogFiltersState {
  /** Filtros aplicados (los de la URL, validados): los que deciden qué se pide al servidor. */
  filters: CatalogFilters;
  /** Valor actual de cada control (puede ir por delante de `filters` mientras dura la espera). */
  draft: CatalogFilterDraft;
  setGenre: (value: string) => void;
  setYear: (value: string) => void;
  setSort: (value: CatalogSortKey) => void;
  /** El campo «Año» ha perdido el foco: a partir de ahora se enseña su error si lo tiene. */
  touchYear: () => void;
  /** Aplica ya lo que hay en los controles, sin esperar (Intro en el formulario). */
  applyNow: () => void;
  /** Vuelve a «sin filtros» al instante. */
  clear: () => void;
  /** Error del campo «Año», o `null` si no hay que enseñar ninguno. */
  yearError: string | null;
}

/**
 * Estado de la barra de filtros de `/peliculas` o `/series`: une los controles con la URL.
 *
 * **La URL es la fuente de verdad** de los filtros APLICADOS
 * (`?genero=4&anio=2014&orden=titulo-asc`, ver `lib/catalogFilters.ts`). Los
 * controles tienen su propio borrador, que pasa a la URL tras
 * {@link CATALOG_FILTER_DEBOUNCE_MS} sin cambios. Cada aplicación deja una entrada
 * en el historial (no `replace`): «Atrás» vuelve al filtro anterior.
 *
 * **Por qué con espera también en los desplegables.** En Windows, las flechas
 * sobre un `<select>` cerrado cambian el valor directamente (cada flecha es un
 * `change`). Aplicando al instante, recorrer los órdenes con el teclado dejaría
 * una entrada en el historial por orden y lanzaría una petición por cada uno
 * (todas canceladas menos la última). Con la espera solo cuenta donde se para
 * el usuario, y para el año evita buscar con cada cifra. Intro aplica sin
 * esperar ({@link CatalogFiltersState.applyNow}).
 *
 * **Cambios que vienen de fuera** (Atrás/Adelante, un enlace): los controles se
 * ponen al día. Para distinguirlos de los cambios propios se recuerda lo último
 * que este hook ha llevado a la URL (`applied`), el mismo patrón que el
 * buscador del panel (`useAdminSearchList`): sin él, si la navegación propia
 * llegase tarde mientras el usuario sigue tocando los controles, se le
 * deshacería lo último que ha cambiado.
 *
 * **Géneros desconocidos.** Si ya se conoce la lista de géneros y el de la URL
 * no está en ella (`?genero=999`, o se borró), se ignora como cualquier otro
 * valor inválido.
 *
 * @param kind catálogo de la página (decide qué órdenes de la URL son válidos)
 * @param knownGenreIds ids de los géneros existentes, o `null` mientras no se conocen
 *   (entonces se confía en el de la URL: el servidor simplemente no devolverá nada)
 */
export function useCatalogFilters(kind: CatalogKind, knownGenreIds: readonly number[] | null): CatalogFiltersState {
  const [searchParams, setSearchParams] = useSearchParams();
  const urlFilters = useMemo(() => parseCatalogFilters(searchParams, kind), [searchParams, kind]);
  const filters = useMemo(
    () =>
      knownGenreIds !== null && urlFilters.genreId !== null && !knownGenreIds.includes(urlFilters.genreId)
        ? { ...urlFilters, genreId: null }
        : urlFilters,
    [knownGenreIds, urlFilters],
  );

  const [draft, setDraft] = useState<CatalogFilterDraft>(() => toDraft(filters));
  const [yearTouched, setYearTouched] = useState(false);
  const debouncedDraft = useDebouncedValue(draft, CATALOG_FILTER_DEBOUNCE_MS);
  /** Últimos filtros que este hook ha llevado a la URL (o ha recibido de ella). */
  const applied = useRef(filters);

  /** Lleva unos filtros a la URL (con entrada en el historial) si son distintos de los aplicados. */
  const commit = useCallback(
    (next: CatalogFilters) => {
      if (sameCatalogFilters(next, applied.current)) return;
      applied.current = next;
      setSearchParams(catalogFiltersToSearchParams(next));
    },
    [setSearchParams],
  );

  /**
   * Último borrador "asentado" que ya se ha tratado. El efecto de abajo también
   * se repite cuando cambia `commit` (`setSearchParams` de React Router cambia
   * con cada URL); sin esta marca, tras aplicar con Intro se volvería a aplicar
   * el borrador asentado ANTERIOR (aún sin la última cifra) y se desharía el filtro.
   */
  const handledDraft = useRef(debouncedDraft);

  // Lo que el usuario deja quieto durante la espera se aplica (solo cuando ESO cambia).
  useEffect(() => {
    if (debouncedDraft === handledDraft.current) return;
    handledDraft.current = debouncedDraft;
    commit(fromDraft(debouncedDraft, applied.current.year));
  }, [debouncedDraft, commit]);

  // Los filtros han cambiado desde fuera: los controles se ponen al día.
  useEffect(() => {
    if (sameCatalogFilters(filters, applied.current)) return;
    applied.current = filters;
    setDraft(toDraft(filters));
    setYearTouched(false);
  }, [filters]);

  const setGenre = useCallback((genre: string) => setDraft((current) => ({ ...current, genre })), []);
  const setYear = useCallback((year: string) => setDraft((current) => ({ ...current, year })), []);
  const setSort = useCallback((sort: CatalogSortKey) => setDraft((current) => ({ ...current, sort })), []);
  const touchYear = useCallback(() => setYearTouched(true), []);

  const applyNow = useCallback(() => {
    setYearTouched(true);
    commit(fromDraft(draft, applied.current.year));
  }, [commit, draft]);

  const clear = useCallback(() => {
    setDraft(toDraft(NO_CATALOG_FILTERS));
    setYearTouched(false);
    commit(NO_CATALOG_FILTERS);
  }, [commit]);

  // El error no aparece mientras se escribe «20…»: solo con 4 caracteres o al salir del campo.
  const yearText = draft.year.trim();
  const yearInvalid = yearText !== '' && parseReleaseYear(yearText) === null;
  const yearError = yearInvalid && (yearTouched || yearText.length >= 4) ? YEAR_ERROR_MESSAGE : null;

  return { filters, draft, setGenre, setYear, setSort, touchYear, applyNow, clear, yearError };
}

/** Lo que devuelve {@link useCatalogFilterBar}. */
export interface CatalogFilterBarState {
  /** Filtros aplicados: los que la página pasa a su hook de resultados. */
  filters: CatalogFilters;
  /** Propiedades listas para `<CatalogFilterBar {...barProps} />`. */
  barProps: CatalogFilterBarProps;
  /** Quita los filtros y lleva el foco al primer control (también para el «Quitar filtros» del estado vacío). */
  clearFilters: () => void;
}

/**
 * Todo lo que necesita una página de catálogo para su barra de filtros: los
 * géneros (`GET /api/genres`), el estado de {@link useCatalogFilters} y la
 * acción «Quitar filtros». Lo comparten `/peliculas` y `/series`, que así solo
 * deciden qué pintan con los filtros, no cómo se conectan con la URL.
 *
 * **Foco al quitar los filtros.** El botón pulsado desaparece (sin filtros no
 * hay nada que quitar) y, sin moverlo, el foco caería al `<body>` y el teclado
 * tendría que empezar de nuevo: se lleva al desplegable de género, el primer
 * control de la barra.
 *
 * @param kind catálogo de la página
 */
export function useCatalogFilterBar(kind: CatalogKind): CatalogFilterBarState {
  const { genres, status: genresStatus, loaded: genresLoaded, reload: reloadGenres } = useGenres();
  // Hasta conocer los géneros no se puede saber si el de la URL existe: mientras tanto se confía en él.
  const knownGenreIds = useMemo(() => (genresLoaded ? genres.map((genre) => genre.id) : null), [genres, genresLoaded]);
  const state = useCatalogFilters(kind, knownGenreIds);
  const { filters, draft, clear } = state;
  const genreSelectRef = useRef<HTMLSelectElement>(null);

  const clearFilters = useCallback(() => {
    clear();
    genreSelectRef.current?.focus();
  }, [clear]);

  // «Quitar filtros» se ofrece también mientras un cambio espera para aplicarse.
  const draftActive = draft.genre !== '' || draft.year.trim() !== '' || draft.sort !== DEFAULT_SORT;

  return {
    filters,
    clearFilters,
    barProps: {
      kind,
      draft,
      genres,
      genresStatus,
      onRetryGenres: reloadGenres,
      onGenreChange: state.setGenre,
      onYearChange: state.setYear,
      onYearBlur: state.touchYear,
      onSortChange: state.setSort,
      onSubmit: state.applyNow,
      yearError: state.yearError,
      showClear: hasActiveFilters(filters) || draftActive,
      onClear: clearFilters,
      genreSelectRef,
    },
  };
}
