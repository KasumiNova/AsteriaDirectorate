import { fetchFile } from '@ffmpeg/util'
import type { FFmpeg } from '@ffmpeg/ffmpeg'
import { renderPoster } from '../render'
import { BANNER_DURATION_SECONDS } from '../render/banner'
import type { ImageAssets, PosterSettings } from '../render/shared'
import { withFFmpegOperation } from './engine'

export type AnimationFormat = 'webp' | 'gif'
export type BannerFormat = AnimationFormat | 'png'
export type PosterFormat = 'webp' | 'png'
type ImageFormat = BannerFormat

export interface ConversionProgress {
  message: string
  value: number
}

export interface VideoConversionOptions {
  file: File
  width: number
  fps: number
  quality: number
  loop: boolean
  start: number
  duration?: number
  format: AnimationFormat
}

interface PosterConversionOptions {
  settings: PosterSettings
  assets: ImageAssets
  scale: number
  format: PosterFormat
}

interface BannerConversionOptions {
  settings: PosterSettings
  assets: ImageAssets
  scale: number
  format: BannerFormat
}

type ProgressHandler = (progress: ConversionProgress) => void

const paletteFilter = 'split[video_a][video_b];[video_a]palettegen=stats_mode=diff[palette];[video_b][palette]paletteuse=dither=sierra2_4a:diff_mode=rectangle'

function formatName(format: ImageFormat): string {
  return format.toUpperCase()
}

function formatMime(format: ImageFormat): string {
  if (format === 'gif') return 'image/gif'
  return format === 'png' ? 'image/png' : 'image/webp'
}

function pngBlob(canvas: HTMLCanvasElement): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob((result) => {
      if (!result) reject(new Error('浏览器未能生成 PNG 帧'))
      else if (result.type !== 'image/png') reject(new Error('浏览器未能提供无损 PNG 中间帧'))
      else resolve(result)
    }, 'image/png')
  })
}

async function readEncodedBlob(ffmpeg: FFmpeg, path: string, mime: string): Promise<Blob> {
  const data = await ffmpeg.readFile(path)
  if (typeof data === 'string') throw new Error('FFmpeg 返回了文本数据')
  const buffer = new ArrayBuffer(data.byteLength)
  new Uint8Array(buffer).set(data)
  return new Blob([buffer], { type: mime })
}

async function removeFiles(ffmpeg: FFmpeg, files: string[]): Promise<void> {
  if (!ffmpeg.loaded) return
  for (const file of files) {
    try {
      await ffmpeg.deleteFile(file)
    } catch (error) {
      console.error(`清理 FFmpeg 临时文件失败：${file}`, error)
    }
  }
}

async function execute(ffmpeg: FFmpeg, args: string[]): Promise<void> {
  const logs: string[] = []
  const onLog = ({ message }: { message: string }) => {
    logs.push(message)
    if (logs.length > 8) logs.shift()
  }
  ffmpeg.on('log', onLog)
  try {
    const code = await ffmpeg.exec(args)
    if (code !== 0) throw new Error(`FFmpeg 执行失败，代码 ${code}：${logs.join(' | ')}`)
  } finally {
    ffmpeg.off('log', onLog)
  }
}

function trackProgress(ffmpeg: FFmpeg, format: ImageFormat, onProgress: ProgressHandler): () => void {
  const listener = ({ progress }: { progress: number }) => {
    if (Number.isFinite(progress) && progress >= 0) {
      onProgress({ message: `正在编码 ${formatName(format)}`, value: Math.min(1, progress) })
    }
  }
  ffmpeg.on('progress', listener)
  return () => ffmpeg.off('progress', listener)
}

export function encodePoster(
  options: PosterConversionOptions,
  onProgress: ProgressHandler,
): Promise<Blob> {
  onProgress({ message: '正在等待本地 FFmpeg', value: 0 })
  return withFFmpegOperation(async (ffmpeg) => {
    const input = 'poster-source.png'
    const output = `fossic-poster.${options.format}`
    const files = [input, output]
    try {
      const canvas = document.createElement('canvas')
      renderPoster(canvas, options.settings, options.assets, options.scale)
      const source = await pngBlob(canvas)
      await ffmpeg.writeFile(input, new Uint8Array(await source.arrayBuffer()))
      onProgress({ message: `正在压缩无损 ${formatName(options.format)}`, value: 0.25 })
      const args = ['-i', input, '-frames:v', '1']
      if (options.format === 'png') {
        args.push('-c:v', 'png', '-pred', 'mixed', '-compression_level', '9')
      } else {
        args.push('-c:v', 'libwebp', '-lossless', '1', '-q:v', '100')
      }
      args.push('-an', output)
      await execute(ffmpeg, args)
      onProgress({ message: '正在读取图片文件', value: 0.97 })
      const result = await readEncodedBlob(ffmpeg, output, formatMime(options.format))
      onProgress({ message: `无损 ${formatName(options.format)} 已生成`, value: 1 })
      return result
    } finally {
      await removeFiles(ffmpeg, files)
    }
  })
}

export function encodeBanner(
  options: BannerConversionOptions,
  onProgress: ProgressHandler,
): Promise<Blob> {
  onProgress({ message: '正在等待本地 FFmpeg', value: 0 })
  return withFFmpegOperation(async (ffmpeg) => {
    const files: string[] = []
    const output = `fossic-banner.${options.format}`
    const frameCount = options.settings.bannerFps * BANNER_DURATION_SECONDS
    try {
      const canvas = document.createElement('canvas')
      for (let index = 0; index < frameCount; index += 1) {
        renderPoster(canvas, options.settings, options.assets, options.scale, index / options.settings.bannerFps)
        const blob = await pngBlob(canvas)
        const name = `frame-${String(index).padStart(3, '0')}.png`
        await ffmpeg.writeFile(name, new Uint8Array(await blob.arrayBuffer()))
        files.push(name)
        onProgress({ message: `正在生成动画帧 ${index + 1} / ${frameCount}`, value: (index + 1) / frameCount * 0.55 })
        await new Promise<void>((resolve) => window.setTimeout(resolve, 0))
      }
      const untrack = trackProgress(ffmpeg, options.format, (progress) => {
        onProgress({ message: progress.message, value: 0.55 + progress.value * 0.4 })
      })
      try {
        const args = ['-framerate', String(options.settings.bannerFps), '-i', 'frame-%03d.png']
        if (options.format === 'gif') {
          args.push('-filter_complex', `[0:v]${paletteFilter}`, '-loop', '0')
        } else if (options.format === 'png') {
          args.push('-c:v', 'apng', '-pred', 'mixed', '-compression_level', '9', '-plays', '0', '-f', 'apng')
        } else {
          args.push(
            '-c:v', 'libwebp_anim',
            '-lossless', '1',
            '-q:v', '100',
            '-loop', '0',
          )
        }
        args.push('-an', output)
        await execute(ffmpeg, args)
      } finally {
        untrack()
      }
      onProgress({ message: '正在读取动画文件', value: 0.97 })
      files.push(output)
      const result = await readEncodedBlob(ffmpeg, output, formatMime(options.format))
      onProgress({ message: `动画 ${formatName(options.format)} 已生成`, value: 1 })
      return result
    } finally {
      await removeFiles(ffmpeg, files)
    }
  })
}

export function convertVideo(
  options: VideoConversionOptions,
  onProgress: ProgressHandler,
): Promise<Blob> {
  onProgress({ message: '正在等待本地 FFmpeg', value: 0 })
  return withFFmpegOperation(async (ffmpeg) => {
    const input = 'source-video'
    const output = `converted-video.${options.format}`
    const files: string[] = []
    try {
      onProgress({ message: '正在写入视频数据', value: 0.04 })
      await ffmpeg.writeFile(input, await fetchFile(options.file))
      files.push(input)
      const args = ['-ss', String(options.start), '-i', input]
      if (options.duration !== undefined) args.push('-t', String(options.duration))
      const videoFilter = `fps=${options.fps},scale=${options.width}:-2:flags=lanczos`
      if (options.format === 'gif') {
        args.push(
          '-filter_complex', `[0:v]${videoFilter},${paletteFilter}`,
          '-loop', options.loop ? '0' : '-1',
        )
      } else {
        args.push(
          '-vf', videoFilter,
          '-c:v', 'libwebp_anim',
          '-lossless', '0',
          '-q:v', String(options.quality),
          '-loop', options.loop ? '0' : '1',
        )
      }
      args.push('-an', output)
      const untrack = trackProgress(ffmpeg, options.format, (progress) => {
        onProgress({ message: '正在转换视频', value: 0.08 + progress.value * 0.84 })
      })
      try {
        await execute(ffmpeg, args)
      } finally {
        untrack()
      }
      onProgress({ message: '正在读取转换结果', value: 0.96 })
      files.push(output)
      const result = await readEncodedBlob(ffmpeg, output, formatMime(options.format))
      onProgress({ message: '视频转换完成', value: 1 })
      return result
    } finally {
      await removeFiles(ffmpeg, files)
    }
  })
}
