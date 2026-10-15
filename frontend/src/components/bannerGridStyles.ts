/**
 * Rejilla interior del banner destacado: en móvil, póster pequeño a la
 * izquierda del título y el resto a todo el ancho debajo; desde `md`, una fila
 * con el póster junto a la columna de texto.
 *
 * Está en un archivo aparte (como `posterGridStyles.ts`) porque la usan DOS
 * piezas que tienen que medir lo mismo: `FeaturedBanner` y `CatalogSkeleton`,
 * su esqueleto de carga. Lo mismo vale para el resto de constantes de este
 * archivo (las de la página de un título), que comparten las páginas de
 * película y serie y su esqueleto, `DetailSkeleton`. Si cada una llevara su copia, un cambio de espaciado en
 * el banner dejaría al esqueleto desalineado y la página "saltaría" al llegar
 * los datos.
 */
export const BANNER_GRID_CLASS =
  'grid grid-cols-[6rem_minmax(0,1fr)] items-center gap-x-4 gap-y-5 px-4 pt-6 pb-8 sm:grid-cols-[7.5rem_minmax(0,1fr)] sm:gap-x-6 sm:px-6 md:flex md:gap-10 md:pt-10 md:pb-12 lg:gap-12';

/**
 * Contenedor de las páginas de un título (`/peliculas/:id`, `/series/:id`):
 * centrado y con ancho máximo, el mismo que usan `/perfil` y el panel de
 * administración (`max-w-6xl`).
 *
 * **Por qué centrado aquí y no en la portada.** En la portada, debajo del
 * banner vienen filas de tarjetas que llenan el ancho de la ventana, así que
 * alinear el banner a la izquierda con ellas tiene sentido. En la página de un
 * título no hay filas: a 1280 px el contenido se quedaba en una franja a la
 * izquierda y más de la mitad de la pantalla vacía. Centrar el contenido en un
 * ancho máximo equilibra la composición en cualquier pantalla ancha (1440,
 * 1920...) sin estirar las líneas de lectura; el fondo desenfocado del banner
 * sigue a sangre, de borde a borde.
 */
export const DETAIL_CONTAINER_CLASS = 'mx-auto w-full max-w-6xl';

/** Rejilla del banner en la página de un título: la del banner, centrada y con más aire en escritorio. */
export const DETAIL_BANNER_GRID_CLASS = `${BANNER_GRID_CLASS} ${DETAIL_CONTAINER_CLASS} lg:pt-14 lg:pb-16`;

/** Ancho del póster nítido del banner (portada y `/series`) desde `md`. */
export const BANNER_POSTER_SIZE_CLASS = 'md:w-48 lg:w-56';

/**
 * Ancho del póster en la página de un título: algo mayor que en el banner,
 * porque aquí el póster es la imagen principal de la página y no comparte
 * pantalla con filas de tarjetas.
 */
export const DETAIL_POSTER_SIZE_CLASS = 'md:w-52 lg:w-64 xl:w-72';

/**
 * Cuerpo de la página de una serie bajo el banner: en móvil y tableta, una
 * columna (episodios y después la ficha); desde `lg`, los episodios a la
 * izquierda y la ficha en una columna lateral estrecha. Así, en escritorio, la
 * lista de episodios no se estira a todo el ancho (con el «Ver» a un metro del
 * título) y la ficha ocupa el sitio que antes quedaba vacío.
 */
export const SERIES_BODY_GRID_CLASS =
  'grid gap-10 lg:grid-cols-[minmax(0,1fr)_18rem] lg:gap-12 xl:grid-cols-[minmax(0,1fr)_20rem]';

/**
 * Ficha de una película: banda bajo el banner, separada por una línea que la
 * ancla al ancho del contenedor (sin ella, a 1920 px parecía flotar suelta).
 * En móvil, rejilla de dos columnas; desde `sm`, los datos en línea.
 */
export const MOVIE_FACTS_CLASS = 'border-t border-line pt-6 md:pt-8';

/** Lista de la ficha de una película (ver {@link MOVIE_FACTS_CLASS}). */
export const MOVIE_FACTS_LIST_CLASS = 'grid grid-cols-2 gap-x-6 gap-y-5 sm:flex sm:flex-wrap sm:gap-x-12';

/** Ficha de una serie: desde `lg`, tarjeta en la columna lateral junto a los episodios. */
export const SERIES_FACTS_CLASS = 'lg:self-start lg:rounded-xl lg:border lg:border-line lg:bg-surface/60 lg:p-6';

/** Lista de la ficha de una serie: dos columnas en móvil y en la columna lateral; tres en tableta. */
export const SERIES_FACTS_LIST_CLASS = 'grid grid-cols-2 gap-x-6 gap-y-5 sm:grid-cols-3 lg:grid-cols-2';
