#!/usr/bin/env bash
# ===================================================================
# Escaneo con OSV-Scanner que FALLA CERRADO (job "osv" de seguridad.yml).
#
# Uso: OSV_IMAGEN=<imagen@sha256:...> osv-escanear.sh <dir-codigo> <dir-salida>
#
#   dir-codigo  la raíz del repositorio. Se monta en SOLO LECTURA: el
#               escáner no necesita escribir en el código.
#   dir-salida  donde se dejan results.json (escaneo), results.sarif
#               (informe para la pestaña Security) y escaneo.log.
#
# Sale con 0 si el escaneo terminó y no hay vulnerabilidades sin
# excepción, y con 1 si hay vulnerabilidades O si el escaneo no terminó.
# Si se ejecuta en GitHub Actions, publica la salida "sarif" (ruta del
# informe) SOLO cuando el escaneo terminó: es lo que decide si se sube.
#
# Por qué no el workflow reutilizable oficial de OSV: falla ABIERTO.
# Comprobado: con Maven Central inalcanzable, osv-scanner sale con 127
# ("failed resolution for streambox/pom.xml") pero escribe un results.json
# con "results": []; el reutilizable solo mira que el archivo exista y no
# esté vacío, el informe dice "No issues found" y el job sale en verde sin
# haber analizado nada. Peor: en main, ese SARIF vacío CERRARÍA las alertas
# abiertas de la pestaña Security. Además, el punto de entrada de la imagen
# (exit_code_redirect.sh) convierte el 128 ("no encontré ningún
# manifiesto") en 0; por eso aquí se llama al binario directamente.
#
# Códigos de salida de osv-scanner: 0 = sin vulnerabilidades, 1 = hay
# vulnerabilidades (de eso se encarga el informe), 127 = error, 128 =
# ningún paquete encontrado. Cualquier cosa que no sea 0 o 1 es un
# escaneo incompleto.
#
# Un escaneo se da por completo solo si pasa CUATRO comprobaciones: el
# código de salida, ningún texto de error conocido en el log (lista negra),
# results.json escrito y, EN POSITIVO, que results.json contenga los
# paquetes de cada manifiesto exigido (ver MANIFIESTOS_EXIGIDOS). La lista
# negra sola no basta: si una versión futura cambia esos mensajes, o un
# manifiesto deja de detectarse, el escaneo saldría con 0 sin haber
# analizado Maven y, en main, el SARIF vacío cerraría las alertas.
#
# AL SUBIR LA IMAGEN (OSV_IMAGEN en seguridad.yml) hay que volver a
# comprobar a mano, con una ejecución real, que siguen valiendo: los
# textos de error de la lista negra (bloqueando Maven Central, ver el
# manual) y el formato de results.json con --all-packages ("results" →
# "source.path" con la ruta bajo /src y "packages", con las transitivas
# de Maven incluidas).
#
# Para probarlo en local, ver el manual (cap. 4.6, "Escaneo de
# seguridad").
# ===================================================================
set -euo pipefail

codigo="${1:?Falta el directorio del código}"
salida="${2:?Falta el directorio de salida}"
imagen="${OSV_IMAGEN:?Falta la variable OSV_IMAGEN (imagen fijada por digest)}"

# Manifiestos que el escaneo TIENE que haber analizado, con el mínimo de
# paquetes que debe encontrar en cada uno ("ruta=mínimo", ruta desde la
# raíz del repositorio). La lista se mantiene A MANO: un manifiesto nuevo
# se escanea igualmente (--recursive), pero no se exige hasta que se
# añada aquí; y si uno de estos se mueve o se borra, el job falla hasta
# que se actualice la ruta.
#   - streambox/pom.xml: hoy 176 paquetes (18 directas + 158 transitivas
#     que OSV resuelve desde Maven Central). 100 queda lejos de las 18
#     directas, que es lo que saldría si la resolución de las transitivas
#     fallara en silencio, y deja margen para que Spring Boot cambie su
#     árbol de dependencias sin falsas alarmas.
#   - frontend/package-lock.json: hoy 222 paquetes (con los de
#     desarrollo). 150 detecta un lockfile vacío o truncado y deja margen
#     para quitar alguna dependencia.
MANIFIESTOS_EXIGIDOS=(
  "streambox/pom.xml=100"
  "frontend/package-lock.json=150"
)

# Token aleatorio para ::stop-commands:: (ver el paso 1). Si no se puede
# generar, el script se detiene (set -e): sin token no hay protección.
nuevo_token() {
  local token
  token=$(openssl rand -hex 16)
  if [[ ! $token =~ ^[0-9a-f]{32}$ ]]; then
    echo "::error::No se pudo generar un token aleatorio con openssl." >&2
    return 1
  fi
  printf '%s' "$token"
}

mkdir -p "$salida"
# Nada de restos de una ejecución anterior: un SARIF viejo se subiría como
# si fuera de este escaneo.
rm -f "$salida/results.json" "$salida/results.sarif" "$salida/escaneo.log"

# --- 1. Escaneo -----------------------------------------------------
# --recursive ./: encuentra streambox/pom.xml y frontend/package-lock.json
# (respeta el .gitignore) y cualquier manifiesto que se añada mañana.
# --config: el único osv-scanner.toml de la raíz, con las excepciones; sin
# esta opción, OSV buscaría uno junto a cada manifiesto.
# --all-packages: results.json lista TODOS los paquetes analizados, no
# solo los vulnerables. Sin ella, un escaneo limpio y uno que no analizó
# nada dan el mismo "results": []; con ella se puede comprobar en
# positivo qué se analizó (ver más abajo). No cambia el informe: el SARIF
# solo recoge vulnerabilidades (comprobado: idéntico con y sin la opción).
# La salida (stdout y stderr) se ve en el log Y se guarda para buscar en
# ella errores parciales. PIPESTATUS[0] es el código de docker run (el de
# osv-scanner), no el de tee.
#
# ::stop-commands:: alrededor de la salida de la herramienta: el runner de
# GitHub interpreta como comando cualquier línea que empiece por "::"
# (::error::, ::add-mask::, ::set-output::...), y el log de OSV repite
# textos del repositorio, que controla quien abre un pull request.
# Comprobado con v2.6.0: escapa los saltos de línea de los NOMBRES de
# archivo (%0A), pero no los del CONTENIDO de un pom.xml; un groupId con
# "&#10;::set-output name=sarif::/ruta" sale tal cual en el error de
# resolución. Sin esta protección, eso inyecta anotaciones ::error::
# falsas y, mientras el runner acepte el obsoleto ::set-output::, da valor
# a la salida "sarif" de un escaneo fallido (el paso de subida se
# ejecutaría). Entre "::stop-commands::<token>" y "::<token>::" el runner
# no interpreta nada; el token es aleatorio para que el texto de la
# herramienta no pueda reactivarlos. Los ::error:: y ::group:: de este
# script van FUERA de ese bloque.
echo "::group::Escaneo (osv-scanner)"
token=$(nuevo_token)
echo "::stop-commands::$token"
set +e
docker run --rm \
  --volume "$codigo:/src:ro" \
  --volume "$salida:/out" \
  --workdir /src \
  --entrypoint /root/osv-scanner \
  "$imagen" \
  scan source \
  --format=json \
  --all-packages \
  --output-file=/out/results.json \
  --config=./osv-scanner.toml \
  --recursive \
  ./ 2>&1 | tee "$salida/escaneo.log"
codigo_escaneo=${PIPESTATUS[0]}
set -e
# El salto de línea delante, por si la última línea de la herramienta no
# terminaba en uno: el token quedaría pegado a ella, el runner no lo
# reconocería y los comandos seguirían desactivados el resto del paso.
printf '\n::%s::\n' "$token"
echo "::endgroup::"
echo "osv-scanner terminó con el código $codigo_escaneo."

if [[ $codigo_escaneo -ne 0 && $codigo_escaneo -ne 1 ]]; then
  echo "::error::El escaneo de OSV-Scanner NO se completó (código $codigo_escaneo; 127 = error, 128 = no encontró ningún manifiesto). No se publica ningún informe. Causa probable: Maven Central u osv.dev inalcanzables; mira el grupo «Escaneo» del log."
  exit 1
fi
# Un error en un manifiesto puede convivir con un 1 por las vulnerabilidades
# de otro: el código solo no basta.
if grep -qE 'Error during extraction|failed resolution' "$salida/escaneo.log"; then
  echo "::error::OSV-Scanner no pudo analizar algún manifiesto (\"Error during extraction\" o \"failed resolution\" en el log): el escaneo está incompleto. No se publica ningún informe."
  exit 1
fi
if [[ ! -s "$salida/results.json" ]]; then
  echo "::error::OSV-Scanner no escribió results.json: no hay resultados que informar."
  exit 1
fi

# Comprobación EN POSITIVO: cada manifiesto exigido aparece en
# results.json con al menos su mínimo de paquetes. Se cuenta en
# results.json y no en las líneas "Scanned ... found N packages" del log
# por dos motivos: esa línea solo cuenta las dependencias DIRECTAS del
# pom.xml (18; las transitivas las añade después otra fase de OSV), así
# que no detectaría una resolución incompleta; y el log se puede falsear
# con un nombre de archivo que contenga un salto de línea, mientras que
# en el JSON las rutas van escapadas. Python solo imprime las rutas de
# MANIFIESTOS_EXIGIDOS, nunca textos sacados del archivo. "-I": Python
# aislado; "-X utf8": que las tildes salgan bien también al probarlo en
# Windows.
echo "Paquetes analizados en cada manifiesto exigido:"
if ! python3 -I -X utf8 - "$salida/results.json" "${MANIFIESTOS_EXIGIDOS[@]}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as archivo:
    resultados = json.load(archivo).get("results") or []
# Un mismo manifiesto puede salir en varias entradas (en el pom.xml, las
# directas y las transitivas van por separado): se suman.
paquetes = {}
for resultado in resultados:
    ruta = (resultado.get("source") or {}).get("path")
    paquetes[ruta] = paquetes.get(ruta, 0) + len(resultado.get("packages") or [])
correcto = True
for exigido in sys.argv[2:]:
    manifiesto, minimo = exigido.rsplit("=", 1)
    encontrados = paquetes.get("/src/" + manifiesto, 0)
    suficiente = encontrados >= int(minimo)
    correcto = correcto and suficiente
    estado = "correcto" if suficiente else "POR DEBAJO DEL MÍNIMO"
    print(f"  {manifiesto}: {encontrados} paquetes (mínimo {minimo}): {estado}")
sys.exit(0 if correcto else 1)
PY
then
  echo "::error::El escaneo no analizó todos los manifiestos exigidos con el mínimo de paquetes (ver la lista de arriba): el escaneo está incompleto. No se publica ningún informe. Si un manifiesto se ha movido o el mínimo ya no es realista, actualiza MANIFIESTOS_EXIGIDOS en .github/scripts/osv-escanear.sh."
  exit 1
fi

# --- 2. Informe ------------------------------------------------------
# osv-reporter (en la misma imagen) convierte results.json en SARIF y
# --fail-on-vuln=true hace que salga con 1 si hay vulnerabilidades.
# Imprime la tabla de vulnerabilidades en el log (con nombres de paquetes
# y rutas: también va dentro de ::stop-commands::, ver el paso 1).
token=$(nuevo_token)
echo "::stop-commands::$token"
set +e
docker run --rm \
  --volume "$salida:/out" \
  --entrypoint /root/osv-reporter \
  "$imagen" \
  --new=/out/results.json \
  --output-files=sarif:/out/results.sarif \
  --fail-on-vuln=true
codigo_informe=$?
set -e
printf '\n::%s::\n' "$token"
echo "osv-reporter terminó con el código $codigo_informe."

if [[ $codigo_informe -ne 0 && $codigo_informe -ne 1 ]]; then
  echo "::error::osv-reporter falló (código $codigo_informe): no se publica ningún informe."
  exit 1
fi
if [[ ! -s "$salida/results.sarif" ]]; then
  echo "::error::osv-reporter no escribió results.sarif."
  exit 1
fi

# Escaneo completo: el SARIF se puede subir (también cuando hay
# vulnerabilidades, que es justo cuando interesa verlo en Security).
if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  echo "sarif=$salida/results.sarif" >> "$GITHUB_OUTPUT"
fi

if [[ $codigo_informe -eq 1 ]]; then
  echo "::error::Hay vulnerabilidades conocidas sin excepción vigente en osv-scanner.toml (ver la tabla de arriba)."
fi
exit "$codigo_informe"
