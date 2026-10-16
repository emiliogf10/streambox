import { useAuth } from '../context/AuthContext';

/** Texto para un usuario (y para cualquiera mientras se confirma el rol). */
const USER_TEXT = 'Todavía no está disponible para ver.';

/** Propiedades de {@link NothingToWatchNote}. */
interface NothingToWatchNoteProps {
  /**
   * Qué puede hacer un administrador, en el contexto de cada sitio: en la
   * página de un título, «desde «Editar»» (el botón está al lado); en el
   * banner de la portada, que no tiene «Editar», desde la página del título.
   */
  adminText: string;
}

/**
 * Aviso breve bajo las acciones de un título que **no tiene vídeo ni tráiler**
 * (incidencia QA trailer-5).
 *
 * **Por qué hace falta.** Sin vídeo no hay «Ver ahora» y sin tráiler no hay
 * «Ver tráiler» (ningún botón promete algo que no existe), pero entonces la
 * cabecera se quedaba solo con «Mi lista» y nada explicaba por qué no se puede
 * ver. Un usuario podía pensar que la página había fallado.
 *
 * **Texto según el rol** (cap. 20.9 del manual):
 * - **Usuario**, y también mientras carga el rol (se trata como usuario: falla
 *   cerrado): «Todavía no está disponible para ver.». Es verdad y no promete
 *   fecha ni le manda a ningún sitio donde tampoco podría hacer nada.
 * - **Administrador** (rol confirmado por el servidor): `adminText`, que le
 *   dice qué falta y dónde añadirlo, porque él sí puede arreglarlo.
 *
 * **Estilo.** Discreto (texto pequeño, sin icono ni caja) para no competir con
 * los botones. Va FUERA de la rejilla de acciones (`FeaturedBanner`, prop
 * `actionsNote`): dentro contaría como una acción más y descuadraría la regla
 * de «la última, si queda sola, a todo el ancho». Color `text-white/75` y no el
 * token `muted`: va sobre el fondo desenfocado del póster, y en el peor caso
 * calculado para el banner (zona blanca bajo el velo, gris ≈85) `muted` da
 * 2,4:1, por debajo del 4,5:1 de WCAG AA; `white/75` da 5,1:1 (el mismo
 * criterio que `BackLink`).
 */
export function NothingToWatchNote({ adminText }: NothingToWatchNoteProps) {
  const { isAdmin } = useAuth();
  return <p className="text-sm leading-snug text-pretty text-white/75">{isAdmin ? adminText : USER_TEXT}</p>;
}
