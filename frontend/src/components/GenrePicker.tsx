import { useId } from 'react';
import type { ReactNode, Ref } from 'react';
import { Check } from 'lucide-react';
import { MAX_FAVORITE_GENRES, genreCounterMessage } from '../lib/favoriteGenres';
import type { Genre } from '../lib/types';

/** Propiedades de {@link GenrePicker}. */
interface GenrePickerProps {
  /** Géneros que se pueden elegir (los de `GET /api/users/me/genres/options`), ya ordenados por nombre. */
  options: Genre[];
  /** Ids marcados ahora (el padre guarda el estado: así decide cuándo se puede guardar). */
  selected: number[];
  /** Cambia la selección (nunca recibe más de {@link MAX_FAVORITE_GENRES}). */
  onChange: (selected: number[]) => void;
  /** Nombre del grupo (su `<legend>`): lo que el lector de pantalla anuncia al entrar. */
  legend: string;
  /** Texto bajo el título del grupo, si hace falta explicar algo más. */
  description?: ReactNode;
  /** Error del servidor sobre la selección (`validationErrors.genreIds`), junto al grupo. */
  error?: string;
  /** `true` mientras se guarda: no se puede cambiar la selección a medias de una petición. */
  disabled?: boolean;
  /**
   * `id` del contador («Has elegido N de 5...»), para que el padre lo use como
   * descripción de su botón «Guardar». Si no se pasa, se genera uno.
   */
  counterId?: string;
  /** `ref` del `fieldset` (en React 19 es una propiedad más): para llevar el foco al grupo tras un error. */
  ref?: Ref<HTMLFieldSetElement>;
}

/**
 * Selector de géneros favoritos: el MISMO en la bienvenida (`/bienvenida`) y en
 * «Tus gustos» del perfil, para que las reglas y los textos no se separen.
 *
 * **Casillas nativas en un `fieldset`.** Cada género es un
 * `<input type="checkbox">` oculto a la vista (`sr-only`) pero no al teclado ni
 * al lector de pantalla, con su etiqueta como «botón» visible, el mismo patrón
 * que el selector del icono de perfil (`AvatarForm`). El navegador da gratis el
 * nombre del grupo (`<legend>`), «Acción, casilla, marcada» y el Espacio para
 * marcar. Lo marcado no se distingue solo por el color: lleva fondo propio, una
 * marca ✓ y el nombre en negrita. El foco se dibuja en la etiqueta
 * (`has-focus-visible`), con el mismo contorno que `focus-ring`.
 *
 * **Contador verdadero.** Debajo, «Has elegido N de 5» con lo que falta o lo que
 * se puede hacer ({@link genreCounterMessage}), en una región `role="status"`
 * (cortés) que existe siempre: cada cambio se anuncia sin interrumpir. El grupo
 * la tiene como descripción, y el padre la usa como descripción de «Guardar».
 *
 * **Al llegar a 5** las casillas sin marcar no se pueden marcar: llevan
 * `aria-disabled` (no `disabled`, que las sacaría del orden de tabulación y del
 * lector, y no se sabría por qué no responden) y el contador dice que es el
 * máximo y que hay que quitar uno para elegir otro. Se ven más apagadas y con el
 * cursor de «no permitido», además del texto.
 *
 * **Responsive.** Rejilla de 2 columnas a 375 px que crece hasta 5 en escritorio:
 * aprovecha el ancho sin estirar cada casilla de borde a borde. Cada casilla
 * ocupa toda la altura de su fila (`h-full`): un género de nombre largo ocupa dos
 * líneas y, sin eso, sus vecinas se quedarían más bajas.
 *
 * El `ref` apunta al `fieldset` (para llevar el foco al grupo tras un error).
 */
export function GenrePicker({
  options,
  selected,
  onChange,
  legend,
  description,
  error,
  disabled = false,
  counterId: givenCounterId,
  ref,
}: GenrePickerProps) {
  const id = useId();
  const counterId = givenCounterId ?? `${id}-counter`;
  const errorId = `${id}-error`;
  const atLimit = selected.length >= MAX_FAVORITE_GENRES;

  const toggle = (genreId: number) => {
    if (disabled) return;
    if (selected.includes(genreId)) {
      onChange(selected.filter((current) => current !== genreId));
    } else if (!atLimit) {
      onChange([...selected, genreId]);
    }
  };

  return (
    <fieldset ref={ref} tabIndex={-1} aria-describedby={counterId} className="min-w-0 outline-hidden">
      <legend className="mb-1 text-base font-semibold text-white">{legend}</legend>
      {description && <div className="mb-3 text-sm leading-relaxed text-muted">{description}</div>}
      {error && (
        <p id={errorId} role="alert" className="mb-3 text-sm font-medium text-danger">
          {error}
        </p>
      )}
      <ul role="list" className="mt-3 grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
        {options.map((genre) => {
          const checked = selected.includes(genre.id);
          const blocked = !checked && atLimit;
          return (
            <li key={genre.id} className="min-w-0">
              <label
                className={`relative flex h-full min-h-11 items-center gap-2 rounded-lg border-2 py-2 pr-8 pl-3 text-sm transition-colors has-focus-visible:outline-2 has-focus-visible:outline-offset-2 has-focus-visible:outline-accent ${
                  checked
                    ? 'cursor-pointer border-accent bg-surface-raised font-semibold text-white'
                    : blocked
                      ? 'cursor-not-allowed border-line text-muted'
                      : 'cursor-pointer border-line text-gray-300 hover:border-field-border hover:text-white'
                }`}
              >
                <input
                  type="checkbox"
                  checked={checked}
                  onChange={() => toggle(genre.id)}
                  aria-disabled={blocked || disabled || undefined}
                  aria-describedby={[blocked ? counterId : '', error ? errorId : ''].filter(Boolean).join(' ') || undefined}
                  className="sr-only"
                />
                <span className="min-w-0 break-words">{genre.name}</span>
                {checked && (
                  <span
                    aria-hidden="true"
                    className="absolute top-1/2 right-2 inline-flex size-5 -translate-y-1/2 items-center justify-center rounded-full bg-accent text-black"
                  >
                    <Check className="size-3.5" strokeWidth={3} />
                  </span>
                )}
              </label>
            </li>
          );
        })}
      </ul>
      <p id={counterId} role="status" className="mt-3 text-sm text-muted">
        {genreCounterMessage(selected.length)}
      </p>
    </fieldset>
  );
}
