/**
 * Rejilla interior del banner destacado: en móvil, póster pequeño a la
 * izquierda del título y el resto a todo el ancho debajo; desde `md`, una fila
 * con el póster junto a la columna de texto.
 *
 * Está en un archivo aparte (como `posterGridStyles.ts`) porque la usan DOS
 * piezas que tienen que medir lo mismo: `FeaturedBanner` y `CatalogSkeleton`,
 * su esqueleto de carga. Si cada una llevara su copia, un cambio de espaciado en
 * el banner dejaría al esqueleto desalineado y la página "saltaría" al llegar
 * los datos.
 */
export const BANNER_GRID_CLASS =
  'grid grid-cols-[6rem_minmax(0,1fr)] items-center gap-x-4 gap-y-5 px-4 pt-6 pb-8 sm:grid-cols-[7.5rem_minmax(0,1fr)] sm:gap-x-6 sm:px-6 md:flex md:gap-10 md:pt-10 md:pb-12 lg:gap-12';
