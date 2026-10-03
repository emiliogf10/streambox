import { useEffect, useId, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import { Search } from 'lucide-react';
import { ApiError, apiFetch, getErrorMessage, isAbortError } from '../lib/api';
import type { Movie, PageResponse } from '../lib/types';
import { MovieDetailsModal } from './MovieDetailsModal';
import { MoviePoster } from './MoviePoster';

/** Milisegundos de espera tras la última tecla antes de buscar (evita una petición por letra). */
const DEBOUNCE_MS = 300;

/** Resultados que se piden y se muestran en el desplegable. */
const RESULTS_SIZE = 10;

/**
 * Buscador de películas por título con el patrón ARIA "combobox con lista" (WAI-ARIA APG).
 *
 * **Datos.** Usa `GET /api/movies/search?title=...`. Cada vez que cambia el
 * texto se cancela la petición anterior (`AbortController`), de modo que una
 * respuesta lenta de una búsqueda antigua nunca pisa a la más reciente. Si la
 * búsqueda falla o no hay coincidencias, el desplegable lo dice en lugar de
 * quedarse mudo.
 *
 * **Accesibilidad.**
 * - El `<input>` es un `combobox` con su `<label>` (oculta visualmente, pero
 *   leída por los lectores de pantalla), `aria-expanded`, `aria-controls` y
 *   `aria-autocomplete="list"`.
 * - Los resultados son un `listbox` de `option`. El foco real NUNCA sale del
 *   campo (se puede seguir escribiendo): la opción resaltada se señala con
 *   `aria-activedescendant`.
 * - Teclado: ↑/↓ recorren los resultados (circular), Intro abre la película
 *   resaltada (o la primera si no hay ninguna), Escape cierra la lista y
 *   vacía el campo.
 * - Una región `aria-live` anuncia cuántos resultados hay, "sin resultados" o
 *   el error: el desplegable aparece sin mover el foco y, si no, un lector de
 *   pantalla no se enteraría.
 */
export function SearchBar() {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<Movie[]>([]);
  const [status, setStatus] = useState<'idle' | 'loading' | 'done' | 'error'>('idle');
  const [errorMessage, setErrorMessage] = useState('');
  // Índice de la opción resaltada con el teclado; -1 = ninguna.
  const [activeIndex, setActiveIndex] = useState(-1);
  // Si el desplegable está visible. Escape y elegir una película lo cierran; escribir o enfocar lo abre.
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
      apiFetch<PageResponse<Movie>>('/movies/search', {
        params: { title: query.trim(), size: RESULTS_SIZE, sort: 'title' },
        signal: controller.signal,
      })
        .then((page) => {
          setResults(page.content);
          setStatus('done');
        })
        .catch((error: unknown) => {
          if (isAbortError(error)) return;
          if (error instanceof ApiError && error.sessionExpired) return;
          setResults([]);
          setErrorMessage(getErrorMessage(error, 'No se pudo realizar la búsqueda.'));
          setStatus('error');
        });
    }, DEBOUNCE_MS);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [query]);

  /** Vacía el texto y los resultados y cierra el desplegable (Escape, elegir película, clic fuera). */
  const resetSearch = () => {
    setQuery('');
    setResults([]);
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
      setResults([]);
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

  const hasResults = status === 'done' && results.length > 0;
  const expanded = open && hasResults;
  const showMessage = open && !!query.trim() && (status === 'error' || (status === 'done' && results.length === 0));

  // Mantiene visible la opción resaltada cuando la lista tiene scroll.
  useEffect(() => {
    if (activeIndex >= 0) document.getElementById(`${baseId}-option-${activeIndex}`)?.scrollIntoView({ block: 'nearest' });
  }, [activeIndex, baseId]);

  const handleSelect = (movie: Movie) => {
    setSelectedMovie(movie);
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
          setActiveIndex((index) => (index + 1) % results.length);
        }
        break;
      case 'ArrowUp':
        if (!hasResults) return;
        event.preventDefault();
        if (!open) {
          setOpen(true);
        } else {
          setActiveIndex((index) => (index <= 0 ? results.length - 1 : index - 1));
        }
        break;
      case 'Enter':
        if (!expanded) return;
        event.preventDefault();
        handleSelect(results[activeIndex >= 0 ? activeIndex : 0]);
        break;
      case 'Escape':
        if (!open && !query) return; // sin nada que cerrar: que Escape siga su curso
        event.preventDefault();
        resetSearch();
        break;
    }
  };

  /** Texto que anuncia la región `aria-live` (visualmente oculta). */
  const announcement =
    status === 'loading' && open
      ? 'Buscando...'
      : expanded
        ? `${results.length} ${results.length === 1 ? 'resultado' : 'resultados'}. Usa las flechas arriba y abajo para recorrerlos.`
        : showMessage
          ? status === 'error'
            ? errorMessage
            : `Sin resultados para ${query.trim()}.`
          : '';

  return (
    <div ref={containerRef} role="search" className="relative w-full md:w-60">
      <label htmlFor={inputId} className="sr-only">
        Buscar películas por título
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
        <ul
          id={listId}
          role="listbox"
          aria-label="Resultados de la búsqueda"
          className="absolute top-full right-0 left-0 z-50 mt-2 flex max-h-80 flex-col gap-0.5 overflow-y-auto rounded-xl border border-line bg-surface p-1.5 shadow-2xl md:left-auto md:w-80"
        >
          {results.map((movie, index) => (
            <li
              key={movie.id}
              id={optionId(index)}
              role="option"
              aria-selected={index === activeIndex}
              // Evita que el clic robe el foco al campo (el foco debe seguir en el combobox).
              onMouseDown={(event) => event.preventDefault()}
              onClick={() => handleSelect(movie)}
              // Opción activa: fondo más claro + contorno de acento alrededor de TODA la fila (no solo una barra
              // lateral de color): el cambio de forma se ve aunque no se distinga el color.
              className="flex min-h-14 cursor-pointer items-center gap-3 rounded-lg px-2.5 py-2 hover:bg-white/5 aria-selected:bg-white/10 aria-selected:ring-1 aria-selected:ring-accent/70 aria-selected:ring-inset"
            >
              <MoviePoster title={movie.title} src={movie.imageUrl} compact className="h-12 w-9 shrink-0 rounded-md" />
              <div className="min-w-0">
                <p className="truncate text-sm font-medium text-white">{movie.title}</p>
                <p className="text-xs text-muted">{movie.releaseYear}</p>
              </div>
            </li>
          ))}
        </ul>
      )}

      {showMessage && (
        <div className="absolute top-full right-0 left-0 z-50 mt-2 rounded-xl border border-line bg-surface p-3 text-sm text-muted shadow-2xl md:left-auto md:w-80">
          {status === 'error' ? errorMessage : `Sin resultados para «${query.trim()}».`}
        </div>
      )}

      {selectedMovie && <MovieDetailsModal movie={selectedMovie} onClose={() => setSelectedMovie(null)} />}
    </div>
  );
}
