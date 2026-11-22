/**
 * Genera `clip.webm`, el vídeo diminuto que usan los E2E del reproductor
 * (`e2e/player.spec.ts`). Solo hace falta volver a ejecutarlo si se quiere
 * cambiar el vídeo: el archivo generado está en el repositorio (≈ 7 kB).
 *
 * **Por qué así.** El reproductor solo abre archivos de vídeo de verdad (WebM,
 * MP4, Ogg) y los E2E no deben descargar nada de Internet. Sin `ffmpeg` en el
 * sistema, se usa lo que ya trae Playwright: su Chromium dibuja cada fotograma
 * (un número de segundo sobre un color) y su `ffmpeg` (el que usa para grabar
 * vídeos de las pruebas, que solo sabe leer JPEG y escribir VP9/WebM) los junta
 * a 1 fotograma por segundo. El resultado dura {@link SECONDS} s, mide 160×90
 * y no tiene sonido.
 *
 * Uso (desde `frontend/`): `node e2e/fixtures/generar-video.mjs`
 */
import { spawn } from 'node:child_process';
import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from '@playwright/test';

/** Duración del vídeo, en segundos (más de los 10 s que hacen falta para que se guarde el progreso). */
const SECONDS = 60;
const WIDTH = 160;
const HEIGHT = 90;
const OUTPUT = fileURLToPath(new URL('./clip.webm', import.meta.url));

/** Ruta del `ffmpeg` que instala Playwright (`ms-playwright/ffmpeg-<versión>/`). */
function playwrightFfmpeg() {
  const base =
    process.env.PLAYWRIGHT_BROWSERS_PATH ??
    (process.platform === 'win32'
      ? join(process.env.LOCALAPPDATA ?? '', 'ms-playwright')
      : process.platform === 'darwin'
        ? join(process.env.HOME ?? '', 'Library', 'Caches', 'ms-playwright')
        : join(process.env.HOME ?? '', '.cache', 'ms-playwright'));
  const folder = readdirSync(base).find((name) => name.startsWith('ffmpeg'));
  if (!folder) throw new Error(`No hay ffmpeg de Playwright en ${base} (npx playwright install chromium).`);
  const exe = ['ffmpeg-win64.exe', 'ffmpeg-linux', 'ffmpeg-mac'].map((name) => join(base, folder, name)).find(existsSync);
  if (!exe) throw new Error(`No se encuentra el ejecutable de ffmpeg en ${join(base, folder)}.`);
  return exe;
}

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: WIDTH, height: HEIGHT } });
const ffmpeg = spawn(
  playwrightFfmpeg(),
  ['-y', '-f', 'image2pipe', '-framerate', '1', '-c:v', 'mjpeg', '-i', 'pipe:0', '-c:v', 'libvpx-vp9', '-b:v', '0', '-crf', '50', '-pix_fmt', 'yuv420p', OUTPUT],
  { stdio: ['pipe', 'inherit', 'inherit'] },
);
for (let second = 0; second < SECONDS; second++) {
  const hue = Math.round((second / SECONDS) * 360);
  await page.setContent(
    `<body style="margin:0;display:grid;place-items:center;height:100vh;background:hsl(${hue} 60% 30%);color:#fff;font:700 40px system-ui">${second}</body>`,
  );
  ffmpeg.stdin.write(await page.screenshot({ type: 'jpeg', quality: 80 }));
}
ffmpeg.stdin.end();
await new Promise((resolve, reject) => ffmpeg.on('close', (code) => (code === 0 ? resolve() : reject(new Error(`ffmpeg: ${code}`)))));
await browser.close();
console.log(`Vídeo generado: ${OUTPUT}`);
