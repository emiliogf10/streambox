/**
 * Tests de `getReturnTo`: la lista blanca de destinos a los que vuelve un
 * formulario del panel. Protegen que solo se acepten el propio listado (con su
 * búsqueda) y la página de ESE título, nunca otra ruta ni otra web.
 */
import { describe, expect, it } from 'vitest';
import { ADMIN_MOVIES_PATH, ADMIN_SERIES_PATH, getReturnTo } from './adminPaths';

describe('getReturnTo', () => {
  it('acepta el listado y el listado con búsqueda y página', () => {
    expect(getReturnTo({ returnTo: '/admin/peliculas' })).toBe('/admin/peliculas');
    expect(getReturnTo({ returnTo: '/admin/peliculas?q=blade&page=2' })).toBe('/admin/peliculas?q=blade&page=2');
  });

  it('sin estado, o con un estado que no sirve, vuelve al listado indicado', () => {
    expect(getReturnTo(undefined)).toBe(ADMIN_MOVIES_PATH);
    expect(getReturnTo(null, ADMIN_SERIES_PATH)).toBe(ADMIN_SERIES_PATH);
    expect(getReturnTo({ returnTo: 42 })).toBe(ADMIN_MOVIES_PATH);
    expect(getReturnTo({ returnTo: 'https://malo.example/' })).toBe(ADMIN_MOVIES_PATH);
    expect(getReturnTo({ returnTo: '/admin/peliculas-falsas' })).toBe(ADMIN_MOVIES_PATH);
  });

  it('la página del título solo vale si es EXACTAMENTE la indicada', () => {
    expect(getReturnTo({ returnTo: '/peliculas/7' }, ADMIN_MOVIES_PATH, '/peliculas/7')).toBe('/peliculas/7');
    expect(getReturnTo({ returnTo: '/peliculas/8' }, ADMIN_MOVIES_PATH, '/peliculas/7')).toBe(ADMIN_MOVIES_PATH);
    expect(getReturnTo({ returnTo: '/peliculas/70' }, ADMIN_MOVIES_PATH, '/peliculas/7')).toBe(ADMIN_MOVIES_PATH);
    expect(getReturnTo({ returnTo: '/peliculas/7?x=//malo' }, ADMIN_MOVIES_PATH, '/peliculas/7')).toBe(ADMIN_MOVIES_PATH);
  });

  it('sin página del título (alta), la de una película no se acepta', () => {
    expect(getReturnTo({ returnTo: '/peliculas/7' })).toBe(ADMIN_MOVIES_PATH);
  });

  it('series: acepta su listado y la página EXACTA de esa serie, y nada más', () => {
    const at = (returnTo: unknown) => getReturnTo({ returnTo }, ADMIN_SERIES_PATH, '/series/4');
    expect(at('/series/4')).toBe('/series/4');
    expect(at('/admin/series?q=dark&page=2')).toBe('/admin/series?q=dark&page=2');
    // Otra serie, una que empieza igual, la misma con algo detrás, la de una película con el mismo id...
    for (const other of ['/series/5', '/series/40', '/series/4/', '/series/4?temporada=2', '/series/4#x', '/peliculas/4']) {
      expect(at(other), other).toBe(ADMIN_SERIES_PATH);
    }
    // ...ni URLs externas o que el navegador entendería como de otro sitio.
    for (const external of ['https://malo.example/series/4', '//malo.example/series/4', 'javascript:alert(1)']) {
      expect(at(external), external).toBe(ADMIN_SERIES_PATH);
    }
    // El listado de películas tampoco vale como vuelta del formulario de series.
    expect(at('/admin/peliculas')).toBe(ADMIN_SERIES_PATH);
  });

  it('películas no se afloja: la página de una serie no vale como vuelta del formulario de películas', () => {
    expect(getReturnTo({ returnTo: '/series/7' }, ADMIN_MOVIES_PATH, '/peliculas/7')).toBe(ADMIN_MOVIES_PATH);
  });

  it('sin página del título (alta de serie), la de una serie no se acepta', () => {
    expect(getReturnTo({ returnTo: '/series/4' }, ADMIN_SERIES_PATH)).toBe(ADMIN_SERIES_PATH);
  });
});
