import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { Button } from '../../components/Button';
import type { GenresStatus } from '../../hooks/useGenres';
import type { Genre } from '../../lib/types';
import { ADMIN_GENRES_PATH } from './adminPaths';

/** Propiedades de {@link GenreCheckboxes}. */
interface GenreCheckboxesProps {
  /** `id` del `fieldset`: el formulario lo usa para llevar el foco al grupo si tiene error. */
  id: string;
  /** Qué necesita al menos un género, para el aviso de "no hay géneros" (p. ej. «cada película»). */
  itemNoun: string;
  genres: Genre[];
  status: GenresStatus;
  loaded: boolean;
  errorMessage: string;
  onRetry: () => void;
  selected: number[];
  onToggle: (genreId: number) => void;
  error?: string;
}

/**
 * Grupo de casillas de géneros de los formularios del panel (películas y series).
 *
 * `fieldset` + `legend` agrupan las casillas bajo un nombre («Géneros») que el
 * lector de pantalla anuncia al entrar en el grupo; el requisito o el error van
 * enlazados con `aria-describedby`. Cada casilla está dentro de su `<label>`,
 * que ocupa toda la fila (objetivo táctil de 44 px) y se marca con borde y
 * fondo de acento cuando está elegida, además de la propia marca de la casilla.
 *
 * Mientras cargan, si fallan (con «Reintentar») o si no hay ninguno (con un
 * enlace a la pestaña Géneros), el grupo lo explica en su lugar.
 */
export function GenreCheckboxes({
  id,
  itemNoun,
  genres,
  status,
  loaded,
  errorMessage,
  onRetry,
  selected,
  onToggle,
  error,
}: GenreCheckboxesProps) {
  const hintId = `${id}-hint`;
  const errorId = `${id}-error`;

  let body: ReactNode;
  if (!loaded && status === 'loading') {
    body = (
      <p role="status" className="text-sm text-muted">
        Cargando géneros...
      </p>
    );
  } else if (!loaded && status === 'error') {
    body = (
      <div className="flex flex-wrap items-center gap-3 rounded-lg border border-red-500/30 bg-red-950/30 p-3">
        <p className="text-sm text-red-300">{errorMessage}</p>
        <Button variant="outline" onClick={onRetry}>
          Reintentar
        </Button>
      </div>
    );
  } else if (genres.length === 0) {
    body = (
      <p className="rounded-lg border border-dashed border-line p-4 text-sm leading-relaxed text-muted">
        Todavía no hay géneros y {itemNoun} necesita al menos uno.{' '}
        <Link to={ADMIN_GENRES_PATH} className="focus-ring rounded-sm font-semibold text-accent underline-offset-2 hover:underline">
          Crea uno en la pestaña Géneros
        </Link>{' '}
        y vuelve después a este formulario.
      </p>
    );
  } else {
    body = (
      <div className="grid gap-2 sm:grid-cols-2 xl:grid-cols-3">
        {genres.map((genre) => (
          <label
            key={genre.id}
            className="flex min-h-11 cursor-pointer items-center gap-3 rounded-lg border border-line bg-surface px-3 py-2 text-sm text-white transition-colors hover:border-muted has-checked:border-accent/60 has-checked:bg-accent/10"
          >
            <input
              type="checkbox"
              className="focus-ring size-4 shrink-0 accent-accent"
              checked={selected.includes(genre.id)}
              onChange={() => onToggle(genre.id)}
            />
            <span className="min-w-0 wrap-anywhere">{genre.name}</span>
          </label>
        ))}
      </div>
    );
  }

  return (
    <fieldset id={id} aria-describedby={error ? errorId : hintId} aria-invalid={error ? true : undefined} className="min-w-0">
      <legend className="mb-1.5 text-sm font-medium text-gray-300">Géneros</legend>
      {body}
      {error ? (
        <p id={errorId} role="alert" className="mt-1.5 text-xs font-medium text-danger">
          {error}
        </p>
      ) : (
        <p id={hintId} className="mt-1.5 text-xs text-muted">
          Elige al menos uno.
        </p>
      )}
    </fieldset>
  );
}
