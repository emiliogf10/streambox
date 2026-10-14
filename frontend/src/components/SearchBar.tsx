import { useEffect, useId, useRef, useState } from 'react';
import type { KeyboardEvent, MouseEvent as ReactMouseEvent } from 'react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import { Search } from 'lucide-react';
import { ApiError, apiFetch, getErrorMessage } from '../lib/api';
import { moviePath, seriesPath } from '../lib/catalog';
import { pluralize, seriesCardMeta } from '../lib/series';
import { getTitleSearchError, normalizeTitleSearch, TITLE_SEARCH_MAX_LENGTH } from '../lib/titleSearch';
import type { Movie, PageResponse, Series } from '../lib/types';
import { MoviePoster } from './MoviePoster';

/** Milisegundos de espera tras la última tecla antes de buscar (evita una petición por letra). */
const DEBOUNCE_MS = 300;

/** Resultados que se piden (y se muestran como máximo) de CADA tipo. */
const RESULTS_SIZE = 10;

/** Un resultado del desplegable: película o serie. El tipo decide a qué página lleva. */
type SearchOption = { kind: 'movie'; item: Movie } | { kind: 'series'; item: Series };

/**
 * Resultados de la última búsqueda terminada, por tipo. `error` = mensaje si esa mitad falló;
 * `approximate` = esa mitad no tiene ningún título que contenga el texto y trae títulos parecidos.
 */
interface SearchResults {
  movies: Movie[];
  series: Series[];
  moviesError: string | null;
  seriesError: string | null;
  moviesApproximate: boolean;
  seriesApproximate: boolean;
}

const NO_RESULTS: SearchResults = {
  movies: [],
  series: [],
  moviesError: null,
  seriesError: null,
  moviesApproximate: false,
  seriesApproximate: false,
};

/**
 * Mensaje de una búsqueda que ha fallado. Si el servidor ha rechazado el texto (400
 * `VALIDATION_ERROR` en `title`: demasiado largo o con caracteres de control), su motivo
 * exacto («La búsqueda no puede superar los 150 caracteres.»), que dice qué corregir; si
 * no, el mensaje habitual del error.
 */
function searchErrorMessage(error: unknown, fallback?: string): string {
  return getTitleSearchError(error) ?? getErrorMessage(error, fallback);
}

/**
 * Qué decir cuando al menos un grupo trae títulos PARECIDOS en lugar de coincidencias.
 * - `lead`: frase que dice dónde no hay coincidencias (recibe el texto, entrecomillado o no).
 * - `mixed`: el otro grupo sí trae coincidencias de verdad, así que no se puede hablar de "títulos" en general.
 * - `kindPlural`: el tipo aproximado («películas»/«series»), para la frase del caso mixto.
 */
interface ApproximateSummary {
  lead: (quoted: string) => string;
  mixed: boolean;
  kindPlural: 'películas' | 'series';
}

/**
 * Resume la parte aproximada de una búsqueda, o `null` si todo lo que se ve son coincidencias.
 *
 * El texto tiene que ser verdad en cada combinación (el servidor decide `approximate` por
 * separado para películas y para series):
 * - Los dos aproximados, o uno aproximado y el otro sin nada: no hay NINGÚN título que lo
 *   contenga → «No hay títulos que contengan…».
 * - Uno aproximado y el otro con coincidencias (`mixed`): sí hay títulos que lo contienen, así
 *   que solo se puede decir «Ninguna película contiene…» (o serie).
 * - Uno aproximado y el otro con error: no se sabe qué habría en el otro, así que también se
 *   habla solo del tipo aproximado (el fallo ya se avisa aparte).
 */
function summarizeApproximate(results: SearchResults): ApproximateSummary | null {
  const { moviesApproximate, seriesApproximate } = results;
  if (!moviesApproximate && !seriesApproximate) return null;
  const allTitles = (q: string) => `No hay títulos que contengan ${q}`;
  if (moviesApproximate && seriesApproximate) return { lead: allTitles, mixed: false, kindPlural: 'películas' };

  const otherError = moviesApproximate ? results.seriesError : results.moviesError;
  const otherCount = moviesApproximate ? results.series.length : results.movies.length;
  const kindPlural = moviesApproximate ? 'películas' : 'series';
  if (otherError === null && otherCount === 0) return { lead: allTitles, mixed: false, kindPlural };

  const lead = (q: string) => (moviesApproximate ? `Ninguna película contiene ${q}` : `Ninguna serie contiene ${q}`);
  return { lead, mixed: otherError === null, kindPlural };
}

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
 * **Títulos parecidos.** Si ningún título contiene el texto, el servidor puede
 * devolver los de título PARECIDO (`approximate: true`, tolerancia a erratas:
 * «interstelar» → «Interstellar»), cada tipo por su cuenta. Entonces se dice
 * encima de la lista («No hay títulos que contengan «interstelar». Quizá
 * buscabas:»), el grupo pasa a llamarse «Películas con título parecido» y la
 * región `aria-live` lo anuncia. Si solo un grupo es aproximado y el otro trae
 * coincidencias, el aviso habla solo de ese tipo ({@link summarizeApproximate}).
 *
 * **Fallo parcial.** Si una de las dos falla, se muestra la otra y se avisa
 * debajo («No se pudo buscar en series...»): media respuesta es más útil que
 * ninguna, pero hay que decir que falta algo para que "no hay series" no se
 * confunda con "no se pudo buscar". Si fallan las dos, se muestra el error.
 *
 * **Texto válido.** El campo admite como mucho {@link TITLE_SEARCH_MAX_LENGTH}
 * caracteres y lo que se envía pasa por `normalizeTitleSearch` (un tabulador o
 * salto de línea pegado se convierte en un espacio): son las reglas del
 * servidor, que si no responde 400. Si aun así llega ese 400, se enseña su
 * motivo bajo el campo (marcado con `aria-invalid`), no un error genérico; la
 * sesión sigue abierta y el buscador vuelve a buscar en cuanto se corrige el texto.
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
 *   primero) y lleva a su página (`/peliculas/:id` o `/series/:id`). Escape
 *   cierra la lista y vacía el campo.
 * - **Cada opción es un enlace de verdad** (`<a href>` con `role="option"`,
 *   uno de los roles que HTML permite en un enlace): para el lector de pantalla
 *   sigue siendo una opción de la lista, pero con el ratón se puede abrir en otra
 *   pestaña (clic central o con Ctrl/Cmd) o copiar su dirección. No entra en el
 *   orden de tabulación (`tabIndex={-1}`): en este patrón el foco se queda en el campo.
 * - Una región `aria-live` anuncia el total y el reparto ("3 resultados. 2
 *   películas y 1 serie"), "sin resultados", el error o el fallo parcial: el
 *   desplegable aparece sin mover el foco y, si no, un lector de pantalla no se enteraría.
 */
export function SearchBar() {
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<SearchResults>(NO_RESULTS);
  const [status, setStatus] = useState<'idle' | 'loading' | 'done' | 'error'>('idle');
  const [errorMessage, setErrorMessage] = useState('');
  // `true` si el servidor ha rechazado el TEXTO (400 de validación de `title`): el campo se marca como inválido.
  const [invalid, setInvalid] = useState(false);
  // Índice (en la lista completa: películas y luego series) de la opción resaltada; -1 = ninguna.
  const [activeIndex, setActiveIndex] = useState(-1);
  // Si el desplegable está visible. Escape y elegir un resultado lo cierran; escribir o enfocar lo abre.
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);
  const baseId = useId();
  const inputId = `${baseId}-input`;
  const listId = `${baseId}-list`;
  const messageId = `${baseId}-message`;
  // Aviso de títulos parecidos: describe la lista (`aria-describedby`).
  const noticeId = `${baseId}-approximate`;
  const optionId = (index: number) => `${baseId}-option-${index}`;

  useEffect(() => {
    // Lo que se envía va sin caracteres de control (un tabulador pegado): el servidor los rechaza con un 400.
    const title = normalizeTitleSearch(query);
    if (!title) return;
    const controller = new AbortController();
    const timer = setTimeout(() => {
      const options = {
        params: { title, size: RESULTS_SIZE, sort: 'title' },
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
          setErrorMessage(searchErrorMessage(moviesResult.reason, 'No se pudo realizar la búsqueda.'));
          setInvalid(getTitleSearchError(moviesResult.reason) !== null);
          setStatus('error');
          return;
        }
        setResults({
          movies: moviesResult.status === 'fulfilled' ? moviesResult.value.content : [],
          series: seriesResult.status === 'fulfilled' ? seriesResult.value.content : [],
          moviesError: moviesResult.status === 'rejected' ? searchErrorMessage(moviesResult.reason) : null,
          seriesError: seriesResult.status === 'rejected' ? searchErrorMessage(seriesResult.reason) : null,
          // Solo cuenta si de verdad trae títulos: un "aproximado" vacío no tendría nada que sugerir.
          moviesApproximate:
            moviesResult.status === 'fulfilled' && moviesResult.value.approximate && moviesResult.value.content.length > 0,
          seriesApproximate:
            seriesResult.status === 'fulfilled' && seriesResult.value.approximate && seriesResult.value.content.length > 0,
        });
        setInvalid(false);
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
    setInvalid(false);
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
    setInvalid(false);
    if (normalizeTitleSearch(value)) {
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
  // Texto tal y como se busca (sin caracteres de control): es el que citan los mensajes.
  const trimmed = normalizeTitleSearch(query);
  const hasResults = status === 'done' && options.length > 0;
  const expanded = open && hasResults;
  const showMessage = open && !!trimmed && (status === 'error' || (status === 'done' && options.length === 0));
  const showInvalid = showMessage && status === 'error' && invalid;

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

  /** Página de una opción: la de la película o la de la serie. */
  const pathOf = (option: SearchOption) => (option.kind === 'movie' ? moviePath(option.item.id) : seriesPath(option.item.id));

  /**
   * Intro sobre una opción: lleva a su página y cierra el buscador. Si es la
   * página que ya está abierta, **sustituye** la entrada del historial en vez de
   * añadir otra: si no, «Atrás» llevaría a la misma página. Es lo que ya hace
   * `Link` con un clic sobre la ruta actual.
   */
  const handleSelect = (option: SearchOption) => {
    const path = pathOf(option);
    navigate(path, { replace: path === pathname });
    resetSearch();
  };

  /**
   * Clic en una opción. El enlace ya navega solo; aquí solo se cierra el
   * buscador. Con Ctrl/Cmd/Mayús/Alt el navegador abre la página en otra
   * pestaña o ventana (y `Link` no navega aquí): el buscador se queda abierto
   * para poder abrir otro resultado.
   */
  const handleOptionClick = (event: ReactMouseEvent<HTMLAnchorElement>) => {
    if (event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
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

  const approximate = status === 'done' ? summarizeApproximate(results) : null;

  /** Aviso visible de títulos parecidos, encima de la lista (o `null` si todo son coincidencias). */
  const approximateNotice = approximate
    ? approximate.mixed
      ? `${approximate.lead(`«${trimmed}»`)}: las ${approximate.kindPlural} de la lista tienen un título parecido.`
      : `${approximate.lead(`«${trimmed}»`)}. Quizá buscabas:`
    : null;

  /** Anuncio de los resultados: el recuento y, si hay títulos parecidos, que no son coincidencias. */
  const resultsAnnouncement = (() => {
    const count = options.length;
    const tail = `Usa las flechas arriba y abajo para recorrerlos.${partialNotice ? ` ${partialNotice}` : ''}`;
    if (approximate && !approximate.mixed) {
      return `${approximate.lead(trimmed)}. ${count} ${count === 1 ? 'título parecido' : 'títulos parecidos'}: ${breakdown}. ${tail}`;
    }
    const mixedNote = approximate
      ? ` ${approximate.lead(trimmed)}: las ${approximate.kindPlural} de la lista tienen un título parecido.`
      : '';
    return `${count} ${count === 1 ? 'resultado' : 'resultados'}. ${breakdown}.${mixedNote} ${tail}`;
  })();

  /** Texto que anuncia la región `aria-live` (visualmente oculta). */
  const announcement =
    status === 'loading' && open
      ? 'Buscando...'
      : expanded
        ? resultsAnnouncement
        : showMessage
          ? status === 'error'
            ? errorMessage
            : emptyMessage(trimmed)
          : '';

  /** Grupos del desplegable, en orden; cada opción conserva su índice global (el de las flechas). */
  // Un grupo de títulos parecidos lo dice en su propio encabezado (que es su nombre accesible): así se
  // distingue también en el caso mixto, en el que el otro grupo sí son coincidencias.
  const groups = [
    { key: 'movies', label: results.moviesApproximate ? 'Películas con título parecido' : 'Películas', options: options.map((option, index) => ({ option, index })).filter(({ option }) => option.kind === 'movie') },
    { key: 'series', label: results.seriesApproximate ? 'Series con título parecido' : 'Series', options: options.map((option, index) => ({ option, index })).filter(({ option }) => option.kind === 'series') },
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
      <div className="flex min-h-11 items-center gap-2 rounded-full border border-field-border bg-surface-raised px-4 focus-within:outline-2 focus-within:outline-offset-2 focus-within:outline-accent">
        <Search aria-hidden="true" className="size-4 shrink-0 text-muted" />
        <input
          id={inputId}
          type="search"
          role="combobox"
          aria-expanded={expanded}
          aria-controls={expanded ? listId : undefined}
          aria-autocomplete="list"
          aria-activedescendant={expanded && activeIndex >= 0 ? optionId(activeIndex) : undefined}
          // Texto rechazado por el servidor: el campo se marca como inválido y se describe con el motivo.
          aria-invalid={showInvalid || undefined}
          aria-describedby={showInvalid ? messageId : undefined}
          autoComplete="off"
          spellCheck={false}
          enterKeyHint="search"
          placeholder="Buscar títulos..."
          maxLength={TITLE_SEARCH_MAX_LENGTH}
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
          {approximateNotice && (
            <p id={noticeId} className="border-b border-line px-3 py-2.5 text-xs leading-relaxed text-white/90">
              {approximateNotice}
            </p>
          )}
          <div
            id={listId}
            role="listbox"
            aria-label="Resultados de la búsqueda"
            aria-describedby={approximateNotice ? noticeId : undefined}
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
                    <li key={`${option.kind}-${option.item.id}`} role="none">
                      <Link
                        to={pathOf(option)}
                        id={optionId(index)}
                        role="option"
                        aria-selected={index === activeIndex}
                        tabIndex={-1}
                        // Evita que el clic robe el foco al campo (el foco debe seguir en el combobox).
                        onMouseDown={(event) => event.preventDefault()}
                        onClick={handleOptionClick}
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
                      </Link>
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
        <div id={messageId} className={`${panelClass} p-3 text-sm text-muted`}>
          {status === 'error' ? errorMessage : emptyMessage(`«${trimmed}»`)}
        </div>
      )}
    </div>
  );
}
