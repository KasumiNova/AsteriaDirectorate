import { mkdir, copyFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

const root = fileURLToPath(new URL('..', import.meta.url))
const target = `${root}/public/ffmpeg`
await mkdir(target, { recursive: true })
const files = [
  ['node_modules/@ffmpeg/core/dist/esm', 'ffmpeg-core.js'],
  ['node_modules/@ffmpeg/core/dist/esm', 'ffmpeg-core.wasm'],
  ['node_modules/@ffmpeg/ffmpeg/dist/esm', 'worker.js'],
  ['node_modules/@ffmpeg/ffmpeg/dist/esm', 'const.js'],
  ['node_modules/@ffmpeg/ffmpeg/dist/esm', 'errors.js'],
]
await Promise.all(files.map(([directory, file]) => (
  copyFile(`${root}/${directory}/${file}`, `${target}/${file}`)
)))
