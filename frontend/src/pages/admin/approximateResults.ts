/** Tipo de título de un listado del panel: decide el sustantivo del aviso de títulos parecidos. */
export type ApproximateKind = 'movies' | 'series';

/**
 * Texto del aviso de títulos parecidos de un listado del panel (`approximate: true`). Lo usan el
 * aviso visible (`ApproximateResultsNotice`) y la región `role="status"` de la página, que
 * lo anuncia (el aviso aparece sin mover el foco, y un lector de pantalla no se enteraría).
 *
 * Dice dos verdades: que NINGÚN título contiene lo buscado (lo que se ve no son coincidencias) y
 * que el orden es por parecido. Lo segundo importa porque, con texto, el panel ordena por título
 * (A–Z) y el administrador esperaría ese orden; con resultados aproximados el servidor lo ignora.
 */
export function approximateResultsText(kind: ApproximateKind, query: string): string {
  const subject = kind === 'movies' ? 'Ninguna película contiene' : 'Ninguna serie contiene';
  return `${subject} «${query}». Estas tienen un título parecido, de la más parecida a la menos (no van por orden alfabético).`;
}
