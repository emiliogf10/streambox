import type { Genre } from '../lib/types';

/** Propiedades de {@link MetaTags}. */
interface Props {
  /** Datos en texto plano, en orden de lectura (año, duración, temporadas...). */
  facts: string[];
  genres: Genre[];
  /** Nombre de la lista para lectores de pantalla: "Datos de la película", "Datos de la serie". */
  label: string;
  /**
   * Etiqueta de acento opcional ("Estreno reciente", "Novedad") que abre la lista: por qué
   * se destaca este título. Es un dato más de la lista y no un elemento aparte; ver la nota
   * «Una sola lista» de {@link MetaTags}.
   */
  badge?: string;
}

/**
 * Datos de un título del catálogo: hechos en texto plano y géneros como etiquetas.
 * Es la base de `MovieMetaTags` (año y duración) y `SeriesMetaTags` (años,
 * temporadas y episodios), que solo deciden QUÉ datos van.
 *
 * Es una lista (`<ul>`) para que un lector de pantalla diga cuántos datos hay.
 * La clave incluye la posición porque el texto puede repetirse (p. ej. un
 * género llamado igual que el año).
 *
 * **Dos estilos para dos tipos de dato.** Antes todos eran etiquetas idénticas
 * con borde y ninguno destacaba: el ojo tenía que leerlas todas para separar
 * "cuándo/cuánto dura" de "de qué va". Ahora los hechos son texto plano con
 * cifras tabulares (`tabular-nums`: los dígitos ocupan lo mismo y no "bailan"
 * de un título a otro) y solo los géneros, que son categorías, llevan fondo de etiqueta.
 *
 * **Una sola lista («Una sola lista»).** La etiqueta de acento del banner es el
 * primer `<li>` y no un `<span>` hermano de la lista. Con el `<ul>` como bloque
 * aparte, en 375 px el navegador no cabía la lista entera (año + duración + todos
 * los géneros) junto a la etiqueta y la mandaba a la línea de debajo: la etiqueta
 * se quedaba sola en una fila. Como elementos de la MISMA lista con salto de línea,
 * cada dato se coloca donde cabe y la etiqueta comparte fila con el año.
 */
export function MetaTags({ facts, genres, label, badge }: Props) {
  return (
    <ul role="list" aria-label={label} className="flex flex-wrap items-center gap-x-3 gap-y-2">
      {badge && (
        <li className="rounded-md bg-accent px-2 py-0.5 text-xs font-bold text-black">{badge}</li>
      )}
      {facts.map((fact, index) => (
        <li key={`dato-${index}-${fact}`} className="text-sm font-semibold text-white/90 tabular-nums">
          {fact}
        </li>
      ))}
      {genres.map((genre, index) => (
        <li
          key={`genero-${index}-${genre.name}`}
          className="inline-flex items-center rounded-md bg-white/12 px-2 py-0.5 text-xs font-medium text-white/90"
        >
          {genre.name}
        </li>
      ))}
    </ul>
  );
}
