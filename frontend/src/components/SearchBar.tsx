import { useEffect, useId, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { Search } from 'lucide-react';
import { ApiError, apiFetch, getErrorMessage } from '../lib/api';
import { pluralize, seriesCardMeta } from '../lib/series';
import type { Movie, PageResponse, Series } from '../lib/types';
import { MovieDetailsModal } from './MovieDetailsModal';
import { MoviePoster } from './MoviePoster';

/** Milisegundos de espera tras la última tecla antes de buscar (evita una petición por letra). */
const DEBOUNCE_MS = 300;

/** Resultados que se piden (y se muestran como máximo) de CADA tipo. */
const RESULTS_SIZE = 10;

/** Un resultado del desplegable: película o serie. El tipo decide qué pasa al elegirlo. */
type SearchOption = { kind: 'movie'; item: Movie } | { kind: 'series'; item: Series };

/** Resultados de la última búsqueda terminada, por tipo. `error` = mensaje si esa mitad falló. */
interface SearchResults {
  movies: Movie[];
  series: Series[];
  moviesError: string | null;
  seriesError: string | null;
}

const NO_RESULTS: SearchResults = { movies: [], series: [], moviesError: null, seriesError: null };

/**
 * Buscador de películas Y series por título con el patrón ARIA "combobox con
 * lista" y opciones agrupadas (WAI-ARIA APG, "Listbox with grouped options").
 *
 * **Datos.** Tras la pausa al escribir lanza EN PARALELO
 * `GET /api/movies/search` y `GET /api/series/search` (mismo texto, 10 de cada).
 * Las dos comparten un `AbortController`: cada tecla nueva cancela AMBAS, de
 * modo que una respuesta lenta de una búsqueda antigua nunca pisa a la nueva.
 * Se espera a que terminen las dos antes de pintar (`Promise.allSettled`): si
 * las series llegaran después, la lista crecería bajo el puntero y el índice
 * resaltado con el teclado cambiaría de opción.
 *
 * **Fallo parcial.** Si una de las dos falla, se muestra la otra y se avisa
 * debajo («No se pudo buscar en series...»): media respuesta es más útil que
 * ninguna, pero hay que decir que falta algo para que "no hay series" no se
 * confunda con "no se pudo buscar". Si fallan las dos, se muestra el error.
 *
 * **Accesibilidad.**
 * - El `<input>` es un `combobox` con su `<label>` (oculta visualmente, pero
 *   leída por los lectores de pantalla), `aria-expanded`, `aria-controls` y
 *   `aria-autocomplete="list"`.
 * - Los resultados son un `listbox` con dos grupos (`role="group"` nombrado por
 *   su encabezado «Películas» / «Series»). El foco real NUNCA sale del campo (se
 *   puede seguir escribiendo): la opción resaltada se señala con `aria-activedescendant`.
 * - Teclado: ↑/↓ recorren TODOS los resultados en el orden en que se ven
 *   (primero películas, luego series; circular), Intro abre el resaltado (o el
 *   primero): una película abre su diálogo de detalles, una serie lleva a su
 *   página. Escape cierra la lista y vacía el campo.
 * - Una región `aria-live` anuncia el total y el reparto ("3 resultados. 2
 *   películas y 1 serie"), "sin resultados", el error o el fallo parcial: el
 *   desplegable aparece sin mover el foco y, si no, un lector de pantalla no se enteraría.
 */
export function SearchBar() {
  const navigate = useNavigate();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchResults>(NO_RESULTS);
  const [status, setStatus] = useState<'idle' | 'loading' | 'done' | 'error'>('idle');
  const [errorMessage, setErrorMessage] = useState('');
  // Índice (en la lista completa: películas y luego series) de la opción resaltada; -1 = ninguna.
  const [activeIndex, setActiveIndex] = useState(-1);
  // Si el desplegable está visible. Escape y elegir un resultado lo cierran; escribir o enfocar lo abre.
  const [open, setOpen] = useState(false);
  const [selectedMovie, setSelectedMovie] = useState<Movie | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const baseId = useId();
  const inputId = `${baseId}-input`;
  const listId = `${baseId}-list`;
  const optionId = (index: number) => `${baseId}-option-${index}`;

  useEffect(() => {
    if (!query.trim()) return;
    const controller = new AbortController();
    const timer = setTimeout(() => {
      const options = {
        params: { title: query.trim(), size: RESULTS_SIZE, sort: 'title' },
        signal: controller.signal,
      };
      void Promise.allSettled([
        apiFetch<PageResponse<Movie>>('/movies/search', options),
        apiFetch<PageResponse<Series>>('/series/search', options),
      ]).then(([moviesResult, seriesResult]) => {
        // Búsqueda ya sustituida por otra (o el componente se fue): su resultado no interesa.
        if (controller.signal.aborted) return;
        const failures = [moviesResult, seriesResult].flatMap((result) =>
          result.status === 'rejected' ? [result.reason as unknown] : [],
        );
        // Con la sesión caducada ya hay un aviso y una redirección al login: no se añade otro mensaje.
        if (failures.some((error) => error instanceof ApiError && error.sessionExpired)) return;

        if (moviesResult.status === 'rejected' && seriesResult.status === 'rejected') {
          setResults(NO_RESULTS);
          setErrorMessage(getErrorMessage(moviesResult.reason, 'No se pudo realizar la búsqueda.'));
          setStatus('error');
          return;
        }
        setResults({
          movies: moviesResult.status === 'fulfilled' ? moviesResult.value.content : [],
          series: seriesResult.status === 'fulfilled' ? seriesResult.value.content : [],
          moviesError: moviesResult.status === 'rejected' ? getErrorMessage(moviesResult.reason) : null,
          seriesError: seriesResult.status === 'rejected' ? getErrorMessage(seriesResult.reason) : null,
        });
        setStatus('done');
      });
    }, DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [query]);

  /** Vacía el texto y los resultados y cierra el desplegable (Escape, elegir resultado, clic fuera). */
  const resetSearch = () => {
    setQuery('');
    setResults(NO_RESULTS);
    setStatus('idle');
    setActiveIndex(-1);
    setOpen(false);
  };

  /**
   * Actualiza el texto y el estado en el propio evento (no en un efecto). Con
   * texto nuevo pasa a "cargando" para no enseñar "sin resultados" de la búsqueda anterior.
   */
  const handleChange = (value: string) => {
    setQuery(value);
    setActiveIndex(-1);
    setOpen(true);
    if (value.trim()) {
      setStatus('loading');
    } else {
      setResults(NO_RESULTS);
      setStatus('idle');
    }
  };

  // Cerrar y vaciar al pulsar fuera del buscador.
  useEffect(() => {
    const handleOutside = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        resetSearch();
      }
    };
    document.addEventListener('mousedown', handleOutside);
    return () => document.removeEventListener('mousedown', handleOutside);
  }, []);

  // Todas las opciones en el orden en que se ven: es el orden que recorren las flechas.
  const options: SearchOption[] = [
    ...results.movies.map((item): SearchOption => ({ kind: 'movie', item })),
    ...results.series.map((item): SearchOption => ({ kind: 'series', item })),
  ];
  const trimmed = query.trim();
  const hasResults = status === 'done' && options.length > 0;
  const expanded = open && hasResults;
  const showMessage = open && !!trimmed && (status === 'error' || (status === 'done' && options.length === 0));

  /** Aviso de la mitad que falló (solo si la otra fue bien), o `null`. */
  const partialNotice =
    status !== 'done'
      ? null
      : results.seriesError !== null
        ? `No se pudo buscar en series. ${results.seriesError}`
        : results.moviesError !== null
          ? `No se pudo buscar en películas. ${results.moviesError}`
          : null;

  // Mantiene visible la opción resaltada cuando la lista tiene scroll.
  useEffect(() => {
    if (activeIndex >= 0) document.getElementById(`${baseId}-option-${activeIndex}`)?.scrollIntoView({ block: 'nearest' });
  }, [activeIndex, baseId]);

  /** Película → diálogo de detalles (como siempre). Serie → su página. En ambos casos se cierra el buscador. */
  const handleSelect = (option: SearchOption) => {
    if (option.kind === 'movie') setSelectedMovie(option.item);
    else navigate(`/series/${option.item.id}`);
    resetSearch();
  };

  const handleKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    switch (event.key) {
      case 'ArrowDown':
        if (!hasResults) return;
        event.preventDefault();
        if (!open) {
          setOpen(true);
        } else {
          setActiveIndex((index) => (index + 1) % options.length);
        }
        break;
      case 'ArrowUp':
        if (!hasResults) return;
        event.preventDefault();
        if (!open) {
          setOpen(true);
        } else {
          setActiveIndex((index) => (index <= 0 ? options.length - 1 : index - 1));
        }
        break;
      case 'Enter':
        if (!expanded) return;
        event.preventDefault();
        handleSelect(options[activeIndex >= 0 ? activeIndex : 0]);
        break;
      case 'Escape':
        if (!open && !query) return; // sin nada que cerrar: que Escape siga su curso
        event.preventDefault();
        resetSearch();
        break;
    }
  };

  /** Reparto por tipo para el anuncio: "2 películas y 1 serie" (sin mencionar el tipo que no tiene ninguno). */
  const breakdown = [
    results.movies.length > 0 ? pluralize(results.movies.length, 'película', 'películas') : null,
    results.series.length > 0 ? pluralize(results.series.length, 'serie', 'series') : null,
  ]
    .filter(Boolean)
    .join(' y ');

  /** Mensaje "sin resultados" (visible): si una mitad falló, lo dice, para no hacer creer que no hay nada. */
  const emptyMessage = (quoted: string) =>
    partialNotice
      ? `Sin resultados en ${results.moviesError === null ? 'películas' : 'series'} para ${quoted}. ${partialNotice}`
      : `Sin resultados para ${quoted}.`;

  /** Texto que anuncia la región `aria-live` (visualmente oculta). */
  const announcement =
    status === 'loading' && open
      ? 'Buscando...'
      : expanded
        ? `${options.length} ${options.length === 1 ? 'resultado' : 'resultados'}. ${breakdown}. Usa las flechas arriba y abajo para recorrerlos.${partialNotice ? ` ${partialNotice}` : ''}`
        : showMessage
          ? status === 'error'
            ? errorMessage
            : emptyMessage(trimmed)
          : '';

  /** Grupos del desplegable, en orden; cada opción conserva su índice global (el de las flechas). */
  const groups = [
    { key: 'movies', label: 'Películas', options: options.map((option, index) => ({ option, index })).filter(({ option }) => option.kind === 'movie') },
    { key: 'series', label: 'Series', options: options.map((option, index) => ({ option, index })).filter(({ option }) => option.kind === 'series') },
  ].filter((group) => group.options.length > 0);

  /** Panel flotante bajo el campo (lista o mensaje). */
  const panelClass =
    'absolute top-full right-0 left-0 z-50 mt-2 rounded-xl border border-line bg-surface shadow-2xl md:left-auto md:w-80';

  return (
    // En `md` (768–1023 px) mide 12rem para que la barra quepa en una fila también con el enlace
    // «Administrar»; desde `lg` hay sitio de sobra y vuelve a 15rem.
    <div ref={containerRef} role="search" className="relative w-full md:w-48 lg:w-60">
      <label htmlFor={inputId} className="sr-only">
        Buscar películas y series por título
      </label>
      <div className="flex min-h-11 items-center gap-2 rounded-full border border-line bg-surface-raised px-4 focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-accent">
        <Search aria-hidden="true" className="size-4 shrink-0 text-muted" />
        <input
          id={inputId}
          type="search"
          role="combobox"
          aria-expanded={expanded}
          aria-controls={expanded ? listId : undefined}
          aria-autocomplete="list"
          aria-activedescendant={expanded && activeIndex >= 0 ? optionId(activeIndex) : undefined}
          autoComplete="off"
          spellCheck={false}
          enterKeyHint="search"
          placeholder="Buscar títulos..."
          value={query}
          onChange={(e) => handleChange(e.target.value)}
          onFocus={() => setOpen(true)}
          onKeyDown={handleKeyDown}
          // El anillo de foco lo dibuja el contenedor (focus-within), así que aquí no se pierde el indicador.
          className="w-full min-w-0 bg-transparent py-2 text-base text-white outline-hidden placeholder:text-muted md:text-sm"
        />
      </div>

      <p role="status" className="sr-only">
        {announcement}
      </p>

      {expanded && (
        <div className={panelClass}>
          <div
            id={listId}
            role="listbox"
            aria-label="Resultados de la búsqueda"
            className="flex max-h-80 flex-col gap-1 overflow-y-auto p-1.5"
          >
            {groups.map((group) => {
              const headerId = `${baseId}-group-${group.key}`;
              return (
                <ul key={group.key} role="group" aria-labelledby={headerId} className="flex flex-col gap-0.5">
                  {/* Encabezado del grupo: da nombre al grupo, pero no es una opción (no se selecciona ni se recorre). */}
                  <li id={headerId} role="presentation" className="px-2.5 pt-1.5 pb-1 text-xs font-semibold text-muted">
                    {group.label}
                  </li>
                  {group.options.map(({ option, index }) => (
                    <li
                      key={`${option.kind}-${option.item.id}`}
                      id={optionId(index)}
                      role="option"
                      aria-selected={index === activeIndex}
                      // Evita que el clic robe el foco al campo (el foco debe seguir en el combobox).
                      onMouseDown={(event) => event.preventDefault()}
                      onClick={() => handleSelect(option)}
                      // Opción activa: fondo más claro + contorno de acento alrededor de TODA la fila (no solo una barra
                      // lateral de color): el cambio de forma se ve aunque no se distinga el color.
                      className="flex min-h-14 cursor-pointer items-center gap-3 rounded-lg px-2.5 py-2 hover:bg-white/5 aria-selected:bg-white/10 aria-selected:ring-1 aria-selected:ring-accent/70 aria-selected:ring-inset"
                    >
                      <MoviePoster
                        title={option.item.title}
                        src={option.item.imageUrl}
                        compact
                        className="h-12 w-9 shrink-0 rounded-md"
                      />
                      <div className="min-w-0">
                        <p className="truncate text-sm font-medium text-white">{option.item.title}</p>
                        <p className="text-xs text-muted tabular-nums">
                          {option.kind === 'movie' ? option.item.releaseYear : seriesCardMeta(option.item)}
                        </p>
                      </div>
                    </li>
                  ))}
                </ul>
              );
            })}
          </div>
          {partialNotice && (
            <p role="note" className="border-t border-line px-3 py-2.5 text-xs leading-relaxed text-muted">
              {partialNotice}
            </p>
          )}
        </div>
      )}

      {showMessage && (
        <div className={`${panelClass} p-3 text-sm text-muted`}>
          {status === 'error' ? errorMessage : emptyMessage(`«${trimmed}»`)}
        </div>
      )}

      {selectedMovie && <MovieDetailsModal movie={selectedMovie} onClose={() => setSelectedMovie(null)} />}
    </div>
  );
}
