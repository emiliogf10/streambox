import { useId } from 'react';
import type { ComponentProps, FormEvent, ReactNode, Ref } from 'react';
import { ChevronDown, X } from 'lucide-react';
import type { CatalogFilterDraft } from '../hooks/useCatalogFilters';
import type { GenresStatus } from '../hooks/useGenres';
import { SORT_OPTIONS } from '../lib/catalogFilters';
import type { CatalogKind, CatalogSortKey } from '../lib/catalogFilters';
import type { Genre } from '../lib/types';
import { useRetryFocus } from '../hooks/useRetryFocus';
import { Button } from './Button';
import { FormField } from './FormField';

/** Propiedades de {@link SelectField}: las de un `<select>` más su etiqueta visible. */
interface SelectFieldProps extends ComponentProps<'select'> {
  id: string;
  label: string;
  /** Clases del desplegable en sí (p. ej. su ancho), cuando no debe ocupar todo el campo. */
  controlClassName?: string;
  /**
   * Elemento que va JUNTO al desplegable, en su misma línea desde `sm` y
   * debajo en móvil (p. ej. «Quitar filtros»). Comparte la etiqueta del campo
   * como cabecera visual, pero no su `<label>`: tiene su propio nombre.
   */
  trailing?: ReactNode;
}

/**
 * Desplegable nativo con etiqueta, con el mismo aspecto que `FormField`
 * (borde `field-border` de 3.9:1, 44 px de alto, `focus-ring`).
 *
 * Es un `<select>` NATIVO, no uno hecho a mano: el teclado, el lector de
 * pantalla y la lista táctil del móvil funcionan sin código propio. Solo se
 * cambia la flecha (`appearance-none` + un icono decorativo) para que encaje
 * con el tema oscuro; la lista que se despliega la pinta el sistema en oscuro
 * gracias al `color-scheme: dark` de `index.css`.
 */
function SelectField({
  id,
  label,
  className = '',
  controlClassName = '',
  trailing,
  children,
  ...selectProps
}: SelectFieldProps) {
  return (
    <div className={className}>
      <label htmlFor={id} className="mb-1.5 block text-sm font-medium text-gray-300">
        {label}
      </label>
      {/* Desde `sm`, `items-center` centra lo que acompañe al desplegable con su caja (los dos miden 44 px). */}
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <div className={`relative min-w-0 flex-1 ${controlClassName}`.trim()}>
          <select
            id={id}
            className="focus-ring min-h-11 w-full cursor-pointer appearance-none truncate rounded-lg border border-field-border bg-surface py-2.5 pr-10 pl-4 text-sm text-white transition-colors hover:border-muted"
            {...selectProps}
          >
            {children}
          </select>
          <ChevronDown
            aria-hidden="true"
            className="pointer-events-none absolute top-1/2 right-3 size-4 -translate-y-1/2 text-muted"
          />
        </div>
        {trailing}
      </div>
    </div>
  );
}

/** Nombre accesible del formulario de cada catálogo. */
const FORM_LABEL: Record<CatalogKind, string> = { movies: 'Filtrar películas', series: 'Filtrar series' };

/** Propiedades de {@link CatalogFilterBar} (las prepara `useCatalogFilterBar`). */
export interface CatalogFilterBarProps {
  /** Catálogo que se filtra: decide el nombre del formulario y qué órdenes se ofrecen. */
  kind: CatalogKind;
  /** Valor actual de cada control (ver `useCatalogFilters`). */
  draft: CatalogFilterDraft;
  /** Géneros de `GET /api/genres`, ya ordenados por nombre. */
  genres: Genre[];
  genresStatus: GenresStatus;
  onRetryGenres: () => void;
  onGenreChange: (value: string) => void;
  onYearChange: (value: string) => void;
  onYearBlur: () => void;
  onSortChange: (value: CatalogSortKey) => void;
  /** Intro dentro del formulario: aplica sin esperar. */
  onSubmit: () => void;
  /** Error del campo «Año» (o `null`). */
  yearError: string | null;
  /** Si se ofrece «Quitar filtros» (hay algún filtro aplicado o a punto de aplicarse). */
  showClear: boolean;
  onClear: () => void;
  /**
   * Referencia al desplegable de género, el primer control. La página lleva
   * ahí el foco al quitar los filtros: el botón pulsado desaparece y, sin
   * moverlo, el foco caería al `<body>` y el teclado tendría que empezar de nuevo.
   */
  genreSelectRef?: Ref<HTMLSelectElement>;
}

/**
 * Barra de filtros de `/peliculas` y de `/series`: género, año de estreno y orden.
 *
 * **La misma barra para los dos catálogos** (`kind`): cambian solo el nombre
 * del formulario («Filtrar películas» / «Filtrar series») y los órdenes, porque
 * una serie no tiene duración (ver `SORT_OPTIONS` en `lib/catalogFilters.ts`).
 *
 * **Formulario sin botón «Aplicar».** Los cambios se aplican solos tras una
 * breve espera (`useCatalogFilters`) y los resultados se anuncian en una región
 * `role="status"` de la página, sin mover el foco. Aun así es un `<form>` con
 * nombre accesible («Filtrar películas» o «Filtrar series»): los lectores de pantalla lo ofrecen
 * como zona de la página, e Intro en el campo «Año» aplica al momento.
 *
 * **Año: campo de texto numérico, no un desplegable.** La lista tendría 213
 * años (1888–2100) y la API no ofrece "años con títulos" para acortarla. El
 * campo es `inputmode="numeric"` (teclado numérico en el móvil) y no
 * `type="number"`: ese tipo cambia el valor con la rueda del ratón al
 * desplazar la página y acepta `e` o decimales. Se valida en el cliente con el
 * mismo rango que el backend; el error (texto, no solo color) aparece al salir
 * del campo o con 4 caracteres, no mientras se escribe «20…».
 *
 * **Responsive.** En móvil, rejilla de dos columnas: género y año en la primera
 * fila (el año, estrecho), orden y «Quitar filtros» a todo el ancho debajo.
 * Desde `sm`, todo en una fila que puede partirse, alineada por arriba: así un
 * error bajo el año crece hacia abajo sin descolocar los demás controles.
 * Todos los controles miden 44 px de alto.
 *
 * **«Quitar filtros» va pegado al desplegable de orden** (`trailing` de
 * {@link SelectField}), no como un elemento más de la fila. Antes era un hijo
 * suelto del `flex-wrap` con un margen superior fijo (la altura de una
 * etiqueta) para quedar a la altura de los controles; pero cuando no cabía (a
 * 768 px) bajaba solo a otra línea con ese margen y dejaba un hueco grande.
 * Ni `items-end` lo arregla: con el error del año visible, el botón se
 * alinearía con el final del error y no con los controles. Unido al orden, el
 * botón se centra siempre con su caja y, si falta sitio, baja con él, así que
 * ningún margen depende de cuántos elementos quepan en la línea.
 */
export function CatalogFilterBar({
  kind,
  draft,
  genres,
  genresStatus,
  onRetryGenres,
  onGenreChange,
  onYearChange,
  onYearBlur,
  onSortChange,
  onSubmit,
  yearError,
  showClear,
  onClear,
  genreSelectRef,
}: CatalogFilterBarProps) {
  const genreId = useId();
  const yearId = useId();
  const sortId = useId();
  // Al reintentar, el aviso de los géneros desaparece: el foco va al desplegable de género, que sigue ahí.
  const { retryRef, handleRetry } = useRetryFocus(onRetryGenres, () => document.getElementById(genreId));

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault();
    onSubmit();
  };

  return (
    <div className="w-full sm:w-auto">
      <form
        aria-label={FORM_LABEL[kind]}
        onSubmit={handleSubmit}
        noValidate
        className="grid grid-cols-[minmax(0,1fr)_8rem] items-start gap-3 sm:flex sm:flex-wrap"
      >
        <SelectField
          id={genreId}
          label="Género"
          ref={genreSelectRef}
          value={draft.genre}
          onChange={(event) => onGenreChange(event.target.value)}
          className="sm:w-48"
        >
          <option value="">Todos los géneros</option>
          {genres.map((genre) => (
            <option key={genre.id} value={String(genre.id)}>
              {genre.name}
            </option>
          ))}
        </SelectField>

        <div className="sm:w-32">
          <FormField
            id={yearId}
            label="Año"
            value={draft.year}
            onChange={(event) => onYearChange(event.target.value)}
            onBlur={onYearBlur}
            error={yearError ?? undefined}
            inputMode="numeric"
            maxLength={4}
            autoComplete="off"
            placeholder="Ej.: 2014"
          />
        </div>

        <SelectField
          id={sortId}
          label="Ordenar por"
          value={draft.sort}
          onChange={(event) => onSortChange(event.target.value as CatalogSortKey)}
          className="col-span-2"
          controlClassName="sm:w-52 sm:flex-none"
          trailing={
            showClear && (
              <Button variant="ghost" onClick={onClear} className="shrink-0">
                <X aria-hidden="true" className="size-4" />
                Quitar filtros
              </Button>
            )
          }
        >
          {SORT_OPTIONS[kind].map((option) => (
            <option key={option.key} value={option.key}>
              {option.label}
            </option>
          ))}
        </SelectField>
      </form>

      {/* Sin géneros se puede seguir filtrando por año y orden: el fallo se explica aquí, sin tapar la página. */}
      {genresStatus === 'error' && (
        <p className="mt-2 flex flex-wrap items-center gap-x-2 text-sm text-muted">
          No se pudieron cargar los géneros.
          <Button ref={retryRef} variant="ghost" onClick={handleRetry} className="px-2">
            Reintentar<span className="sr-only"> la carga de géneros</span>
          </Button>
        </p>
      )}
    </div>
  );
}
