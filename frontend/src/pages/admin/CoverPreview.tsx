import { useId } from 'react';
import { MoviePoster } from '../../components/MoviePoster';
import { useDebouncedValue } from '../../hooks/useDebouncedValue';
import { getPreviewImageUrl } from '../../lib/movieValidation';

/** Espera tras la última tecla antes de intentar cargar la portada en la vista previa. */
const PREVIEW_DEBOUNCE_MS = 400;

/** Propiedades de {@link CoverPreview}. */
interface CoverPreviewProps {
  /** Título escrito en el formulario (lo usa el respaldo si la imagen no carga). */
  title: string;
  /** URL de la portada tal como está escrita. */
  imageUrl: string;
  /** Título del respaldo mientras el campo Título está vacío (p. ej. «Nueva serie»). */
  placeholderTitle: string;
}

/**
 * Vista previa de la portada de los formularios del panel (películas y
 * series), junto al formulario (debajo de los campos en móvil).
 *
 * - Se actualiza al escribir la URL, con una espera de 400 ms ({@link useDebouncedValue}):
 *   no se intenta descargar cada versión a medias de la dirección.
 * - Solo se carga una URL que cumple las reglas (`getPreviewImageUrl`): una
 *   `http://` o `javascript:` no llega nunca a una `<img>`. Mientras no sea
 *   válida se ve el respaldo de {@link MoviePoster}, el mismo que verían los
 *   usuarios si la imagen fallara.
 * - Usa `MoviePoster` con un título "borrador" (título + `imageUrl`), así que
 *   lo que se ve aquí es exactamente cómo se pintará en el catálogo (las series
 *   usan el mismo póster que las películas).
 */
export function CoverPreview({ title, imageUrl, placeholderTitle }: CoverPreviewProps) {
  const headingId = useId();
  const debouncedUrl = useDebouncedValue(imageUrl, PREVIEW_DEBOUNCE_MS);
  const draft = { title: title.trim() || placeholderTitle, imageUrl: getPreviewImageUrl(debouncedUrl) };

  const message = !debouncedUrl.trim()
    ? 'Escribe la URL de la portada para verla aquí.'
    : draft.imageUrl
      ? 'Así se verá en el catálogo. Si la imagen no carga, se mostrará este respaldo con el título.'
      : 'La URL aún no es válida, así que no se carga: se muestra el respaldo con el título.';

  return (
    <aside
      aria-labelledby={headingId}
      className="flex items-start gap-4 self-start rounded-xl border border-line bg-surface p-4 lg:sticky lg:top-20 lg:col-start-2 lg:row-span-2 lg:row-start-1 lg:flex-col"
    >
      <MoviePoster
        title={draft.title}
        src={draft.imageUrl}
        alt="Vista previa de la portada"
        className="aspect-2/3 w-28 shrink-0 rounded-lg lg:w-full"
      />
      <div className="min-w-0">
        <h3 id={headingId} className="text-sm font-semibold text-white">
          Vista previa de la portada
        </h3>
        <p className="mt-1 text-xs leading-relaxed text-muted">{message}</p>
      </div>
    </aside>
  );
}
