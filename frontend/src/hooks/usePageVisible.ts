import { useSyncExternalStore } from 'react';

/**
 * Si la pestaña se está viendo ahora mismo: `false` cuando `document.visibilityState`
 * es `'hidden'` (otra pestaña delante, ventana minimizada, pantalla bloqueada).
 *
 * Solo se considera «oculta» el valor `'hidden'`: los demás (`'visible'` y el
 * antiguo `'prerender'`) cuentan como visibles, para que un navegador raro no deje
 * algo esperando para siempre.
 */
export function isPageVisible(): boolean {
  return document.visibilityState !== 'hidden';
}

/** Se suscribe al evento `visibilitychange` (contrato de `useSyncExternalStore`). */
function subscribe(onChange: () => void): () => void {
  document.addEventListener('visibilitychange', onChange);
  return () => document.removeEventListener('visibilitychange', onChange);
}

/**
 * Hook: devuelve si la pestaña está visible y vuelve a renderizar al cambiar.
 *
 * **Para qué.** Lo usan los avisos (`components/Toast`): un aviso que nace o
 * sigue contando en una pestaña que nadie mira caducaría sin que nadie lo lea.
 * El caso real son dos pestañas abiertas: se cierra la sesión en una y la otra,
 * en segundo plano, muestra «Se ha cerrado la sesión en otra pestaña…»; al
 * volver a ella, el aviso ya no estaba.
 *
 * Se usa `useSyncExternalStore` (y no `useState` + `useEffect`) porque es la
 * forma que da React para leer un valor externo al navegador sin que un render
 * vea un valor y el siguiente otro distinto.
 *
 * @returns `true` si la pestaña está visible
 */
export function usePageVisible(): boolean {
  // En servidor no hay `document`; esta aplicación no se renderiza allí, pero el tercer
  // argumento evita que React se queje si algún día lo hiciera.
  return useSyncExternalStore(subscribe, isPageVisible, () => true);
}
