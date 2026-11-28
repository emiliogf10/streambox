import { useState } from 'react';
import { MoviePoster } from '../../components/MoviePoster';
import { useDebouncedValue } from '../../hooks/useDebouncedValue';
import { getPreviewOptionalImageUrl } from '../../lib/movieValidation';

/** Espera tras la última tecla antes de intentar cargar la imagen (la misma que la vista previa de la portada). */
const PREVIEW_DEBOUNCE_MS = 400;

/**
 * Texto de la vista previa cuando la URL es válida pero la imagen no carga (no
 * existe, el servidor la rechaza...). Antes se decía «Así se verá a 16:9» junto
 * al respaldo, y no era verdad: guardada así, la imagen no aparece en ningún
 * sitio (el banner vuelve a la portada, la lista de episodios pinta el
 * respaldo y el reproductor no muestra nada mientras carga). Vale para los tres
 * campos que usan esta vista previa.
 */
export const WIDE_IMAGE_FAILED_MESSAGE =
  'No se ha podido cargar la imagen: revisa la dirección. Si la guardas así, no se verá en ningún sitio.';

/** Propiedades de {@link WideImagePreview}. */
interface WideImagePreviewProps {
  /** Nombre de la imagen para el texto alternativo: «Vista previa de <label>». */
  label: string;
  /** Título escrito en el formulario: elige el color del respaldo (como en el catálogo). */
  title: string;
  /** URL tal como está escrita en el campo. */
  imageUrl: string;
  /** Qué pasa si el campo se deja vacío (el campo es opcional). */
  emptyMessage: string;
  /** Dónde se verá la imagen cuando sea válida. */
  validMessage: string;
}

/**
 * Vista previa APAISADA (16:9) de una imagen opcional del panel: la imagen de
 * fondo de una película o serie y la imagen de un episodio. Va justo debajo de
 * su campo, a diferencia de {@link CoverPreview} (columna lateral), porque es
 * una imagen secundaria y así se ve a la vez qué se escribe y qué sale.
 *
 * Mismas reglas que la de la portada: espera 400 ms tras la última tecla, solo
 * carga una URL que el servidor aceptaría (`getPreviewOptionalImageUrl`: nunca
 * una `http://` o `javascript:`) y, mientras no la hay, enseña el respaldo de
 * {@link MoviePoster}. El texto de al lado dice siempre la verdad del estado:
 * vacío (qué se usará en su lugar), válida (dónde se verá), válida pero sin
 * cargar ({@link WIDE_IMAGE_FAILED_MESSAGE}) o aún no válida.
 */
export function WideImagePreview({ label, title, imageUrl, emptyMessage, validMessage }: WideImagePreviewProps) {
  const debouncedUrl = useDebouncedValue(imageUrl, PREVIEW_DEBOUNCE_MS);
  const src = getPreviewOptionalImageUrl(debouncedUrl);
  // Qué URL falló al cargar (como en MoviePoster): al escribir otra, se vuelve a intentar y el texto cambia con ella.
  const [failedSrc, setFailedSrc] = useState<string | null>(null);

  const message = !debouncedUrl.trim()
    ? emptyMessage
    : !src
      ? 'La URL aún no es válida, así que no se carga.'
      : failedSrc === src
        ? WIDE_IMAGE_FAILED_MESSAGE
        : validMessage;

  return (
    <div className="flex flex-col gap-3 rounded-xl border border-line bg-surface p-3 sm:flex-row sm:items-center sm:gap-4">
      <MoviePoster
        title={title}
        src={src}
        alt={`Vista previa de ${label}`}
        wide
        compact
        onLoadError={setFailedSrc}
        className="aspect-video w-full shrink-0 rounded-lg sm:w-48"
      />
      <p className="text-xs leading-relaxed text-muted">{message}</p>
    </div>
  );
}
