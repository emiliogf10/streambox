/**
 * Tests de `lib/avatars.ts`: que la lista de la interfaz coincide exactamente con
 * las 12 claves del contrato de la API (en ese orden), que cada una tiene icono,
 * nombre en español y colores propios, y que todo lo que no sea una clave
 * conocida (`null`, ausente, desconocida o del prototipo de `Object`) acaba en
 * la inicial, nunca en un error.
 */
import { describe, expect, it } from 'vitest';
import {
  AVATARS,
  AVATAR_KEYS,
  INITIAL_AVATAR_LABEL,
  avatarChangedMessage,
  avatarLabel,
  getAvatar,
  isAvatarKey,
} from './avatars';

describe('avatares predefinidos', () => {
  it('son exactamente las 12 claves del contrato de la API, en el orden del selector', () => {
    expect(AVATAR_KEYS).toEqual([
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
    ]);
    expect(AVATARS.map((avatar) => avatar.key)).toEqual(AVATAR_KEYS);
  });

  it('cada clave tiene su nombre accesible en español', () => {
    expect(Object.fromEntries(AVATARS.map((avatar) => [avatar.key, avatar.label]))).toEqual({
      rocket: 'Cohete',
      ghost: 'Fantasma',
      cat: 'Gato',
      dog: 'Perro',
      star: 'Estrella',
      flame: 'Llama',
      leaf: 'Hoja',
      moon: 'Luna',
      sun: 'Sol',
      zap: 'Rayo',
      music: 'Música',
      crown: 'Corona',
    });
  });

  it('cada avatar tiene un icono y un par fondo 900 / icono 200 de su propio color (no se repiten)', () => {
    for (const avatar of AVATARS) {
      expect(avatar.Icon).toBeTruthy();
      expect(avatar.className).toMatch(/^bg-([a-z]+)-900 text-\1-200$/);
    }
    expect(new Set(AVATARS.map((avatar) => avatar.className)).size).toBe(AVATARS.length);
    expect(new Set(AVATARS.map((avatar) => avatar.Icon)).size).toBe(AVATARS.length);
  });
});

describe('getAvatar / isAvatarKey / avatarLabel', () => {
  it('una clave conocida devuelve su avatar', () => {
    expect(getAvatar('cat')?.label).toBe('Gato');
    expect(isAvatarKey('cat')).toBe(true);
    expect(avatarLabel('crown')).toBe('Corona');
  });

  it.each([null, undefined, '', 'dragon', 'CAT', ' cat', 'constructor', '__proto__', 'toString'])(
    '%j no es un avatar: se usa la inicial («Tu inicial»)',
    (key) => {
      expect(getAvatar(key)).toBeNull();
      expect(isAvatarKey(key)).toBe(false);
      expect(avatarLabel(key)).toBe(INITIAL_AVATAR_LABEL);
    },
  );

  it('isAvatarKey rechaza lo que no es texto', () => {
    expect(isAvatarKey(1)).toBe(false);
    expect(isAvatarKey({})).toBe(false);
  });
});

describe('avatarChangedMessage', () => {
  it('nombra el avatar guardado o dice que vuelve la inicial', () => {
    expect(avatarChangedMessage('ghost')).toBe('Icono de perfil cambiado a «Fantasma».');
    expect(avatarChangedMessage(null)).toBe('Icono de perfil cambiado: ahora se muestra tu inicial.');
    // Una respuesta con una clave desconocida se pinta con la inicial: el aviso dice lo mismo que se ve.
    expect(avatarChangedMessage('dragon')).toBe('Icono de perfil cambiado: ahora se muestra tu inicial.');
  });
});
