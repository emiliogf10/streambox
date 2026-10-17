/**
 * Avatares predefinidos del icono de perfil (`User.avatar`).
 *
 * El servidor guarda solo una CLAVE (`"cat"`, `"rocket"`...) o `null`, que
 * significa «enseña la inicial del nombre» (lo de siempre). Qué dibujo y qué
 * color corresponde a cada clave es cosa de la interfaz y vive aquí: así añadir
 * o retocar un avatar no exige tocar la base de datos (la lista blanca de claves
 * válidas está en el backend, que rechaza cualquier otra con 400).
 *
 * **Iconos.** Salen de `lucide-react`, que ya es dependencia del proyecto
 * (licencia ISC): son SVG en línea, heredan el color con `currentColor` y no
 * piden ninguna imagen a la red.
 *
 * **Contraste (WCAG 2.2 · 1.4.11, 3:1 para gráficos que transmiten información).**
 * Cada avatar es un icono del tono 200 de Tailwind sobre un círculo macizo del
 * tono 900 del mismo color. Calculado con la luminancia relativa del estándar a
 * partir de los `oklch` de Tailwind v4 convertidos a sRGB:
 *   blue 7.31 · violet 7.93 · pink 6.99 · teal 7.51 · yellow 7.47 · red 6.94
 *   green 7.49 · indigo 7.68 · orange 6.94 · cyan 7.32 · fuchsia 7.35 · amber 7.29
 * Todos superan de sobra el 3:1 (y hasta el 4.5:1 del texto). Como el fondo es
 * macizo, el resultado no depende de la superficie que haya debajo (barra,
 * tarjeta...). La inicial de respaldo usa el acento sobre `accent/10`: 7.38
 * encima de `canvas`, 6.61 de `surface` y 6.14 de `surface-raised`.
 *
 * Las clases van escritas enteras (no `bg-${color}-900`): Tailwind solo genera
 * las utilidades que encuentra literalmente en el código.
 */
import { Cat, Crown, Dog, Flame, Ghost, Leaf, Moon, Music, Rocket, Star, Sun, Zap } from 'lucide-react';
import type { LucideIcon } from 'lucide-react';

/** Claves de avatar que acepta el servidor (`PATCH /api/users/me` con `avatar`). */
export const AVATAR_KEYS = [
  'rocket',
  'ghost',
  'cat',
  'dog',
  'star',
  'flame',
  'leaf',
  'moon',
  'sun',
  'zap',
  'music',
  'crown',
] as const;

/** Una clave de avatar válida. */
export type AvatarKey = (typeof AVATAR_KEYS)[number];

/** Un avatar predefinido: su clave, el icono, el nombre que se lee y los colores. */
export interface AvatarOption {
  key: AvatarKey;
  /** Nombre en español: es el texto de la opción del selector y lo que lee un lector de pantalla. */
  label: string;
  Icon: LucideIcon;
  /** Fondo y color del icono (pares con contraste ≥3:1, ver arriba). */
  className: string;
}

/** Los 12 avatares, en el orden en que los ofrece el selector. */
export const AVATARS: readonly AvatarOption[] = [
  { key: 'rocket', label: 'Cohete', Icon: Rocket, className: 'bg-blue-900 text-blue-200' },
  { key: 'ghost', label: 'Fantasma', Icon: Ghost, className: 'bg-violet-900 text-violet-200' },
  { key: 'cat', label: 'Gato', Icon: Cat, className: 'bg-pink-900 text-pink-200' },
  { key: 'dog', label: 'Perro', Icon: Dog, className: 'bg-teal-900 text-teal-200' },
  { key: 'star', label: 'Estrella', Icon: Star, className: 'bg-yellow-900 text-yellow-200' },
  { key: 'flame', label: 'Llama', Icon: Flame, className: 'bg-red-900 text-red-200' },
  { key: 'leaf', label: 'Hoja', Icon: Leaf, className: 'bg-green-900 text-green-200' },
  { key: 'moon', label: 'Luna', Icon: Moon, className: 'bg-indigo-900 text-indigo-200' },
  { key: 'sun', label: 'Sol', Icon: Sun, className: 'bg-orange-900 text-orange-200' },
  { key: 'zap', label: 'Rayo', Icon: Zap, className: 'bg-cyan-900 text-cyan-200' },
  { key: 'music', label: 'Música', Icon: Music, className: 'bg-fuchsia-900 text-fuchsia-200' },
  { key: 'crown', label: 'Corona', Icon: Crown, className: 'bg-amber-900 text-amber-200' },
];

/**
 * Búsqueda por clave. Un `Map` y no un objeto literal: con `AVATARS_BY_KEY[clave]`
 * sobre un objeto, una clave como `"constructor"` o `"__proto__"` que llegara del
 * servidor devolvería algo del prototipo en lugar de `undefined`.
 */
const AVATARS_BY_KEY = new Map<string, AvatarOption>(AVATARS.map((avatar) => [avatar.key, avatar]));

/** Nombre de la opción «sin icono» (`avatar: null`): se enseña la inicial del nombre. */
export const INITIAL_AVATAR_LABEL = 'Tu inicial';

/**
 * El avatar de una clave, o `null` si no hay que dibujar un icono: tanto si el
 * usuario no eligió ninguno (`null`/ausente) como si la clave es desconocida.
 * Lo segundo puede pasar si el servidor añade un avatar nuevo antes de que esta
 * versión de la interfaz lo conozca: en ese caso se enseña la inicial, nunca un
 * hueco ni un error.
 */
export function getAvatar(key: string | null | undefined): AvatarOption | null {
  return key == null ? null : (AVATARS_BY_KEY.get(key) ?? null);
}

/** `true` si `key` es una de las claves que conoce la interfaz. */
export function isAvatarKey(key: unknown): key is AvatarKey {
  return typeof key === 'string' && AVATARS_BY_KEY.has(key);
}

/**
 * Nombre para mostrar del avatar de un usuario: el del icono o «Tu inicial»
 * (también para una clave desconocida, que se dibuja con la inicial).
 */
export function avatarLabel(key: string | null | undefined): string {
  return getAvatar(key)?.label ?? INITIAL_AVATAR_LABEL;
}

/**
 * Aviso tras cambiar el icono de perfil. Sale de la respuesta del servidor (lo
 * que de verdad se ha guardado), no de lo marcado en el selector.
 *
 * @param key avatar devuelto por el servidor (`null` = vuelve a la inicial)
 */
export function avatarChangedMessage(key: string | null | undefined): string {
  const avatar = getAvatar(key);
  return avatar
    ? `Icono de perfil cambiado a «${avatar.label}».`
    : 'Icono de perfil cambiado: ahora se muestra tu inicial.';
}
