import { useEffect, useRef, useState } from 'react'
import { terminateFFmpeg } from '../ffmpeg/engine'
import { convertVideo } from '../ffmpeg/webp'
import type { AnimationFormat, ConversionProgress } from '../ffmpeg/webp'

interface VideoMetadata {
  width: number
  height: number
  duration: number
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

function formatTime(seconds: number): string {
  if (!Number.isFinite(seconds)) return '—'
  const minutes = Math.floor(seconds / 60)
  const rest = (seconds % 60).toFixed(2).padStart(5, '0')
  return `${String(minutes).padStart(2, '0')}:${rest}`
}

interface VideoConverterProps {
  onClose: () => void
  onBusyChange: (busy: boolean) => void
}

export default function VideoConverter({ onClose, onBusyChange }: VideoConverterProps) {
  const [file, setFile] = useState<File | null>(null)
  const [sourceUrl, setSourceUrl] = useState('')
  const [metadata, setMetadata] = useState<VideoMetadata | null>(null)
  const [metadataError, setMetadataError] = useState('')
  const [width, setWidth] = useState(720)
  const [fps, setFps] = useState(12)
  const [quality, setQuality] = useState(82)
  const [format, setFormat] = useState<AnimationFormat>('webp')
  const [loop, setLoop] = useState(true)
  const [start, setStart] = useState(0)
  const [duration, setDuration] = useState<number | ''>('')
  const [resultUrl, setResultUrl] = useState('')
  const [resultBlob, setResultBlob] = useState<Blob | null>(null)
  const [resultWidth, setResultWidth] = useState(0)
  const [resultName, setResultName] = useState('fossic-video.webp')
  const [resultFormat, setResultFormat] = useState<AnimationFormat>('webp')
  const [converting, setConverting] = useState(false)
  const [progress, setProgress] = useState<ConversionProgress>({ message: '', value: 0 })
  const [error, setError] = useState('')
  const cancelled = useRef(false)

  useEffect(() => () => {
    if (sourceUrl) URL.revokeObjectURL(sourceUrl)
  }, [sourceUrl])

  useEffect(() => () => {
    if (resultUrl) URL.revokeObjectURL(resultUrl)
  }, [resultUrl])

  useEffect(() => {
    onBusyChange(converting)
  }, [converting, onBusyChange])

  function chooseFile(selected?: File): void {
    if (!selected) return
    setFile(selected)
    setSourceUrl(URL.createObjectURL(selected))
    setMetadata(null)
    setMetadataError('')
    setResultUrl('')
    setResultBlob(null)
    setResultFormat('webp')
    setResultName('fossic-video.webp')
    setError('')
    setProgress({ message: '', value: 0 })
  }

  async function runConversion(): Promise<void> {
    if (!file || converting || metadataError) return
    if (start < 0 || (duration !== '' && duration <= 0)) {
      setError('开始时间不得小于 0，时长必须大于 0')
      return
    }
    if (metadata && start >= metadata.duration) {
      setError(`开始时间必须小于视频时长 ${metadata.duration.toFixed(2)} 秒`)
      return
    }
    if (metadata && duration !== '' && start + duration > metadata.duration) {
      setError(`开始时间加上时长不得超过视频时长 ${metadata.duration.toFixed(2)} 秒`)
      return
    }
    const outputWidth = width
    const outputFormat = format
    cancelled.current = false
    setConverting(true)
    setError('')
    setResultUrl('')
    setResultBlob(null)
    try {
      const blob = await convertVideo({
        file,
        width: outputWidth,
        fps,
        quality,
        loop,
        start,
        duration: duration === '' ? undefined : duration,
        format: outputFormat,
      }, setProgress)
      setResultUrl(URL.createObjectURL(blob))
      setResultBlob(blob)
      setResultWidth(outputWidth)
      setResultFormat(outputFormat)
      setResultName(`fossic-video-${outputWidth}px.${outputFormat}`)
    } catch (reason) {
      console.error(reason)
      setError(cancelled.current ? '转换已取消' : `转换失败：${reason instanceof Error ? reason.message : String(reason)}`)
    } finally {
      setConverting(false)
    }
  }

  function cancel(): void {
    cancelled.current = true
    terminateFFmpeg()
    setProgress({ message: '正在终止转换', value: 0 })
  }

  return (
    <aside className="video-converter" aria-label="视频转动画工具">
      <div className="inspector-heading">
        <div><span className="eyebrow">LOCAL FFMPEG</span><h1>视频转 WebP / GIF</h1></div>
        <button type="button" className="text-button" disabled={converting} onClick={onClose}>返回海报</button>
      </div>

      <section className="panel-section">
        <h2><span>01</span>源视频</h2>
        <label className={`file-picker ${file ? 'has-file' : ''}`}>
          <input type="file" accept="video/*" disabled={converting} onChange={(event) => chooseFile(event.target.files?.[0])} />
          <span>{file ? file.name : '选择本地视频'}</span>
          <small>{file ? formatBytes(file.size) : 'MP4 / WEBM / MOV'}</small>
        </label>
        {sourceUrl && (
          <video
            className="source-video"
            src={sourceUrl}
            controls
            muted
            playsInline
            onLoadedMetadata={(event) => {
              const video = event.currentTarget
              setMetadataError('')
              setMetadata({ width: video.videoWidth, height: video.videoHeight, duration: video.duration })
            }}
            onError={() => {
              setMetadata(null)
              setMetadataError('视频解码或读取失败')
              setError('无法解码或读取所选视频，请选择浏览器支持的视频文件')
            }}
          />
        )}
        {file && (
          <dl className="video-metadata">
            <div><dt>原始尺寸</dt><dd>{metadata ? `${metadata.width} × ${metadata.height}` : metadataError || '读取中'}</dd></div>
            <div><dt>时长</dt><dd>{metadata ? formatTime(metadata.duration) : metadataError || '读取中'}</dd></div>
            <div><dt>文件大小</dt><dd>{formatBytes(file.size)}</dd></div>
          </dl>
        )}
      </section>

      <section className="panel-section">
        <h2><span>02</span>转换参数</h2>
        <div className="field-grid">
          <label>输出宽度
            <select value={width} disabled={converting} onChange={(event) => setWidth(Number(event.target.value))}>
              {[480, 720, 960, 1280].map((value) => <option key={value} value={value}>{value} px</option>)}
            </select>
          </label>
          <label>帧率
            <select value={fps} disabled={converting} onChange={(event) => setFps(Number(event.target.value))}>
              {[10, 12, 15, 24].map((value) => <option key={value} value={value}>{value} fps</option>)}
            </select>
          </label>
          <label>开始时间 / 秒
            <input type="number" min="0" step="0.1" value={start} disabled={converting} onChange={(event) => setStart(Number(event.target.value))} />
          </label>
          <label>时长 / 秒
            <input type="number" min="0.1" step="0.1" placeholder="完整视频" value={duration} disabled={converting} onChange={(event) => setDuration(event.target.value === '' ? '' : Number(event.target.value))} />
          </label>
        </div>
        <div className="export-scale video-format" aria-label="转换格式">
          {(['webp', 'gif'] as AnimationFormat[]).map((item) => <button key={item} disabled={converting} className={format === item ? 'is-selected' : ''} aria-pressed={format === item} onClick={() => setFormat(item)}>{item.toUpperCase()}</button>)}
        </div>
        <div className="slider-heading video-quality"><label htmlFor="video-quality">质量</label><output>{quality}</output></div>
        <input id="video-quality" type="range" min="1" max="100" value={quality} disabled={converting || format === 'gif'} onChange={(event) => setQuality(Number(event.target.value))} />
        {format === 'gif' && <p className="control-description">GIF 使用调色板与抖动，质量滑块不适用</p>}
        <label className="check-row"><input type="checkbox" checked={loop} disabled={converting} onChange={(event) => setLoop(event.target.checked)} /><span>循环播放</span></label>
      </section>

      <section className="panel-section convert-section">
        <button type="button" className="export-button" disabled={!file || converting || Boolean(metadataError)} onClick={runConversion}>{converting ? '正在转换…' : '开始转换'}</button>
        {converting && <button type="button" className="cancel-button" onClick={cancel}>取消转换</button>}
        {(progress.message || converting) && (
          <div className="conversion-progress" role="status">
            <div><span>{progress.message}</span><span>{Math.round(progress.value * 100)}%</span></div>
            <progress max="1" value={progress.value} />
          </div>
        )}
        {error && <p className="error-message" role="alert">{error}</p>}
      </section>

      {resultUrl && resultBlob && (
        <section className="panel-section conversion-result">
          <h2><span>03</span>转换结果 / {resultFormat.toUpperCase()}</h2>
          <img src={resultUrl} alt={`转换后的动画 ${resultFormat.toUpperCase()} 预览`} />
          <div className="result-meta"><span>{resultFormat.toUpperCase()} / {resultWidth} PX</span><span>{formatBytes(resultBlob.size)}</span></div>
          <a className="download-button" href={resultUrl} download={resultName}>下载动画 {resultFormat.toUpperCase()}</a>
        </section>
      )}
    </aside>
  )
}
