import imgAlFilo from './assets/cover_al_filo_del_manana_1790799993531.jpg';
import imgBladeRunner from './assets/cover_blade_runner_1790800002835.jpg';
import imgBladeRunner2049 from './assets/cover_blade_runner_2049_1790800011897.jpg';
import imgDredd from './assets/cover_dredd_1790800025276.jpg';
import imgMarciano from './assets/cover_el_marciano_1790800044440.jpg';
import imgExMachina from './assets/cover_ex_machina_1790800058872.jpg';
import imgInterstellar from './assets/cover_interstellar_1790800078032.jpg';
import imgDune from './assets/cover_dune_parte_dos_v2_1790855956995.jpg';
import imgGuardianes from './assets/cover_guardianes_de_la_galaxia_v6_1790856598834.jpg';
import imgMadMax from './assets/cover_mad_max_v2_1790855982608.jpg';

/** Formatea duración en minutos → "1h 48m" */
export function formatDuration(mins: number): string {
  const h = Math.floor(mins / 60);
  const m = mins % 60;
  return h > 0 ? `${h}h ${m}m` : `${m}m`;
}

const imageMap: Record<string, string> = {
  'al filo del mañana': imgAlFilo,
  'blade runner': imgBladeRunner,
  'blade runner 2049': imgBladeRunner2049,
  'dredd': imgDredd,
  'el marciano': imgMarciano,
  'the martian': imgMarciano,
  'ex machina': imgExMachina,
  'interstellar': imgInterstellar,
  'dune: parte dos': imgDune,
  'dune parte dos': imgDune,
  'guardianes de la galaxia': imgGuardianes,
  'mad max': imgMadMax,
  'mad max: fury road': imgMadMax,
  'mad max: furia en la carretera': imgMadMax,
};

/** Devuelve la imagen local si existe, si no usa la de por defecto de la API */
export function getMovieImage(title: string, defaultUrl: string): string {
  if (!title) return defaultUrl;
  const normalizedTitle = title.toLowerCase().trim();
  if (imageMap[normalizedTitle]) {
    return imageMap[normalizedTitle];
  }
  return defaultUrl;
}
