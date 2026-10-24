import { FFmpeg } from '@ffmpeg/ffmpeg'

let instance: FFmpeg | null = null
let loading: Promise<FFmpeg> | null = null
let operationQueue: Promise<void> = Promise.resolve()

/** 懒加载同源 FFmpeg core，返回可复用实例。 */
export function getFFmpeg(): Promise<FFmpeg> {
  if (instance?.loaded) return Promise.resolve(instance)
  if (loading) return loading
  const ffmpeg = new FFmpeg()
  instance = ffmpeg
  loading = ffmpeg.load({
    coreURL: new URL('/ffmpeg/ffmpeg-core.js', window.location.href).href,
    wasmURL: new URL('/ffmpeg/ffmpeg-core.wasm', window.location.href).href,
    classWorkerURL: new URL('/ffmpeg/worker.js', window.location.href).href,
  }).then(() => ffmpeg).catch((error: unknown) => {
    ffmpeg.terminate()
    instance = null
    loading = null
    throw error
  })
  return loading
}

/** 将实际操作加入共享串行队列，等待期间不接触 FFmpeg 实例。 */
export function withFFmpegOperation<T>(operation: (ffmpeg: FFmpeg) => Promise<T>): Promise<T> {
  const result = operationQueue.then(() => getFFmpeg()).then(operation)
  operationQueue = result.then(() => undefined, () => undefined)
  return result
}

export function terminateFFmpeg(): void {
  instance?.terminate()
  instance = null
  loading = null
}
