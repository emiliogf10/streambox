# Catálogo de demostración: películas libres de la Blender Foundation

StreamBox no aloja vídeos: cada título apunta a una URL. Para que el reproductor integrado, «Seguir viendo», el historial del perfil y los subtítulos se puedan ver funcionando, el script opcional [`docs/catalogo-demostracion.sql`](../catalogo-demostracion.sql) añade tres películas libres con vídeo reproducible:

| Película | Año | Duración | Subtítulos en español |
| :--- | :--- | :--- | :--- |
| Big Buck Bunny | 2008 | 10:35 | No hacen falta: no tiene diálogos |
| Sintel | 2010 | 14:48 | Sí (`subtitulos/sintel.es.vtt`) |
| Tears of Steel | 2012 | 12:14 | Sí (`subtitulos/tears-of-steel.es.vtt`) |

## Cómo usarlo

1. **Sube primero los subtítulos a GitHub** (commit y push de `docs/demo/subtitulos/`). El script los enlaza por **jsDelivr** (`https://cdn.jsdelivr.net/gh/emiliogf10/streambox@main/docs/demo/subtitulos/…`), que solo los encuentra cuando están en el repositorio público. Si aún no están, las películas se ven igual y el reproductor avisa de que no se han podido cargar los subtítulos.
2. **Arranca antes una vez la aplicación con la versión que trae la V16** (columna `backdrop_url`, la imagen de fondo): Flyway la aplica al arrancar. Sin ella, el script falla y, como va en una transacción, no guarda nada.
3. Ejecuta el script en tu base (editor SQL de Supabase o `psql`). Es idempotente: ejecutarlo dos veces no duplica nada, y no toca ningún título existente salvo una excepción: si ya lo ejecutaste antes de la V16, las tres películas no tienen fondo y el script se lo pone, **solo si siguen sin fondo** (nunca pisa uno puesto a mano), con el mismo título, año y vídeo, y con el cartel del script (si cambiaste el cartel, no se pone: con fondo, el banner deja de enseñar el cartel en escritorio).
4. Abre una de las películas y pulsa «Ver ahora». Los subtítulos se activan en el menú de los controles del vídeo.

## Por qué estas fuentes (comprobado el 2026-11-25)

El reproductor necesita dos cosas del servidor del vídeo:

- **Peticiones `Range`** (respuesta 206): sin ellas no se puede saltar a un punto ni reanudar.
- **CORS** (`Access-Control-Allow-Origin`): un `<track>` de otro origen solo se carga si el `<video>` lleva `crossorigin`, y entonces el vídeo también lo exige (manual, cap. 15 ter.5).

| Fuente | `Range` | CORS | Resultado |
| :--- | :--- | :--- | :--- |
| download.blender.org (oficial) | Sí | No | Descartada: vídeos en `.zip`/`.mkv`/`.mov` y subtítulos en `.srt` sin CORS |
| Bucket de muestras de Google (`gtv-videos-bucket`) | — | — | Descartada: responde 403 (ya no es público) |
| archive.org | Sí | Solo en la redirección | Descartada: el servidor final no envía CORS |
| **Wikimedia Commons** (`upload.wikimedia.org`) | **Sí** | **Sí** | **Elegida**: versiones oficiales en WebM (VP9) a 480p |

Los subtítulos no podían enlazarse desde su origen: los de Sintel en Commons salen de una URL de la API (`…/api.php?action=timedtext…`) que no termina en `.vtt` (la API de StreamBox la rechaza, a propósito), y los de Tears of Steel están en `.srt`, que los navegadores no admiten en `<track>`. Por eso se han convertido a WebVTT y se sirven desde este repositorio por jsDelivr, que envía `Content-Type: text/vtt` y CORS.

**Límites conocidos:**

- Wikimedia permite enlazar sus archivos, pero no garantiza que las rutas no cambien, y limita el ritmo de peticiones si alguien abusa (al comprobarlo con muchas peticiones seguidas respondió 429). Ver una película es tráfico normal.
- `@main` en jsDelivr se cachea unas horas y sigue a la rama. Para fijar una versión, cambia `@main` por un commit o una etiqueta (`@<hash>` o `@v1.0`).
- Solo hay WebM (VP9): en iPhone necesita una versión reciente de iOS. Pendiente de probar a mano.

## Créditos y licencias

- **Big Buck Bunny** (2008): © Blender Foundation | [peach.blender.org](https://peach.blender.org). Licencia [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/).
- **Sintel** (2010): © Blender Foundation | [durian.blender.org](https://durian.blender.org). Licencia [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/).
- **Tears of Steel** (2012): (CC) Blender Foundation | [mango.blender.org](https://mango.blender.org). Licencia [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/).
- **Vídeos**: copias oficiales alojadas en [Wikimedia Commons](https://commons.wikimedia.org) (`File:Big Buck Bunny 4K.webm`, `File:Sintel movie 4K.webm`, `File:Tears of Steel in 4k - Official Blender Foundation release.webm`), con la misma licencia CC BY 3.0.
- **Carteles** (`frontend/public/covers/big-buck-bunny.webp`, `sintel.webp`, `tears-of-steel.webp`): conversiones a WebP (500 px de ancho) de `File:Big buck bunny poster big.jpg`, `File:Sintel poster.jpg` y `File:Tos-poster.png` de Wikimedia Commons, CC BY 3.0, © Blender Foundation.
- **Fondos** (`frontend/public/covers/*-fondo.webp`), los tres con licencia [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/). **Cambios** (los exige la licencia): recortados a 16:9, reducidos a 1280×720 y convertidos a WebP (calidad 0,82).
  - `big-buck-bunny-fondo.webp`: de [`File:Big.Buck.Bunny.-.Landscape.png`](https://commons.wikimedia.org/wiki/File:Big.Buck.Bunny.-.Landscape.png) de Wikimedia Commons. «Film: Blender Foundation / www.bigbuckbunny.org; Screenshot: User:Church of emacs».
  - `sintel-fondo.webp`: fotograma de [`File:Sintel trailer-1080p.ogv`](https://commons.wikimedia.org/wiki/File:Sintel_trailer-1080p.ogv) de Wikimedia Commons. «Durian Open Movie project» (Blender Foundation).
  - `tears-of-steel-fondo.webp`: fotograma (segundo 420) de [`File:Tears of Steel 1080p.webm`](https://commons.wikimedia.org/wiki/File:Tears_of_Steel_1080p.webm) de Wikimedia Commons. «(CC) Blender Foundation | mango.blender.org».
- **Subtítulos de Sintel**: colaboradores de Wikimedia Commons ([TimedText:Sintel_movie_4K.webm.es.srt](https://commons.wikimedia.org/wiki/TimedText:Sintel_movie_4K.webm.es.srt)), licencia [CC BY-SA](https://creativecommons.org/licenses/by-sa/4.0/); convertidos a WebVTT.
- **Subtítulos de Tears of Steel**: traducción al español que la Blender Foundation distribuye con la película ([TOS-es.srt](https://download.blender.org/demo/movies/ToS/subtitles/TOS-es.srt)); convertidos de SubRip a WebVTT.

Las sinopsis de las tres películas están escritas para StreamBox.

## Por qué los carteles y los fondos son locales

El cartel de Tears of Steel en Commons solo existe en PNG: su miniatura de 500 px pesaba 674 KB, y como esa película es la más reciente del script, su cartel acaba en el banner del inicio, lo primero que se pinta (LCP). Convertidos a WebP pesan 44, 79 y 74 KB, se sirven desde la propia app (sin depender de Wikimedia) y pasan la validación de la API (`/covers/<archivo>`, como las demás portadas locales). Se convirtieron con el Chromium de Playwright que ya usa el proyecto (dibujar la imagen en un `<canvas>` y exportarla con `toDataURL('image/webp', 0.82)`), sin añadir dependencias.

Los fondos (`backdrop_url`, V16) son lo que pinta el banner desde 768 px de ancho, en lugar de ampliar y difuminar el cartel vertical, así que en escritorio son ellos lo primero que se pinta (LCP): por eso también son locales y en WebP de 1280×720, con el mismo método. Pesan 137 KB (Big Buck Bunny), 24 KB (Sintel) y 55 KB (Tears of Steel).

## Cómo se convirtieron los subtítulos

Conversión con un script de Python (no versionado): se normalizan los saltos de línea, los tiempos de SubRip (`00:00:23,000`) pasan a WebVTT (`00:00:23.000`), se comprueba que cada subtítulo termina después de empezar y que van en orden, y se añade un bloque `NOTE` con el crédito y la licencia al principio de cada archivo. Sintel tiene 26 subtítulos y Tears of Steel, 76.
