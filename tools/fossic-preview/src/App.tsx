import { useCallback, useRef, useState } from 'react'
import PosterCanvas from './components/PosterCanvas'
import VideoConverter from './components/VideoConverter'
import { heroShipIds, shipById } from './data/ships'
import { terminateFFmpeg } from './ffmpeg/engine'
import { encodeBanner, encodePoster } from './ffmpeg/webp'
import type { BannerFormat, PosterFormat } from './ffmpeg/webp'
import { usePosterAssets } from './render/assets'
import { useRubikFont } from './render/fonts'
import { BANNER_DEFAULT_FRAME_RATE, BANNER_DURATION_SECONDS } from './render/banner'
import { layoutSpecs } from './render/shared'
import type { ExportScale, PosterLayout, PosterSettings } from './render/shared'

const defaultSettings: PosterSettings = {
  layout: 'hero',
  mainShipId: 'astd_xc_001',
  bloom: 1.1,
  backgroundBrightness: 82,
  showGrid: true,
  showAnnotations: true,
  showSafeArea: false,
  bannerTitle: '内容预览',
  bannerSide: '更新日志',
  bannerKicker: 'SECTION / 04',
  bannerFps: BANNER_DEFAULT_FRAME_RATE,
}

const layoutNames: Record<PosterLayout, string> = {
  hero: '主视觉编辑',
  atlasArc: '星坠设计图版',
  atlasLens: '紫菀设计图版',
  banner: '通用横幅编辑',
}

type IconName = 'hero' | 'atlas' | 'arc' | 'banner' | 'grid' | 'label' | 'safe' | 'reset' | 'export' | 'video' | 'play' | 'pause'

function Icon({ name }: { name: IconName }) {
  const paths: Record<IconName, React.ReactNode> = {
    hero: <><rect x="3" y="5" width="18" height="14" /><path d="M7 15l4-5 3 3 3-4 4 6" /></>,
    atlas: <><rect x="4" y="3" width="16" height="18" /><path d="M8 7h8M8 11h8M8 15h4" /></>,
    arc: <><path d="M4 4h16v16H4z" /><path d="M4 10h16M10 4v16" /></>,
    banner: <><path d="M3 6h18v12H3zM8 10h8M8 14h5" /></>,
    grid: <><rect x="3" y="3" width="18" height="18" /><path d="M9 3v18M15 3v18M3 9h18M3 15h18" /></>,
    label: <><path d="M4 5h16v11H9l-5 4z" /><path d="M8 9h8M8 12h5" /></>,
    safe: <><path d="M8 3H3v5M16 3h5v5M3 16v5h5M21 16v5h-5" /><rect x="7" y="7" width="10" height="10" strokeDasharray="2 2" /></>,
    reset: <><path d="M4 10a8 8 0 1 1 2 7" /><path d="M4 4v6h6" /></>,
    export: <><path d="M12 3v12M7 10l5 5 5-5" /><path d="M4 16v5h16v-5" /></>,
    video: <><rect x="3" y="5" width="13" height="14" /><path d="M16 10l5-3v10l-5-3z" /></>,
    play: <path d="M8 5l11 7-11 7z" />,
    pause: <path d="M8 5v14M16 5v14" />,
  }
  return <svg viewBox="0 0 24 24" aria-hidden="true">{paths[name]}</svg>
}

interface ToggleProps {
  label: string
  description: string
  checked: boolean
  onChange: (checked: boolean) => void
}

function Toggle({ label, description, checked, onChange }: ToggleProps) {
  return (
    <div className="toggle-row">
      <div><span className="control-label">{label}</span><span className="control-description">{description}</span></div>
      <button type="button" className={`switch ${checked ? 'is-on' : ''}`} role="switch" aria-checked={checked} aria-label={label} onClick={() => onChange(!checked)}><span /></button>
    </div>
  )
}

interface SliderProps {
  label: string
  value: number
  min: number
  max: number
  step: number
  displayValue: string
  onChange: (value: number) => void
}

function Slider({ label, value, min, max, step, displayValue, onChange }: SliderProps) {
  const id = `slider-${label}`
  return (
    <div className="slider-control">
      <div className="slider-heading"><label htmlFor={id}>{label}</label><output htmlFor={id}>{displayValue}</output></div>
      <input id={id} type="range" min={min} max={max} step={step} value={value} onChange={(event) => onChange(Number(event.target.value))} />
      <div className="range-labels"><span>{min}</span><span>{max}</span></div>
    </div>
  )
}

export default function App() {
  const [settings, setSettings] = useState<PosterSettings>(defaultSettings)
  const [exportScale, setExportScale] = useState<ExportScale>(1)
  const [posterFormat, setPosterFormat] = useState<PosterFormat>('webp')
  const [bannerFormat, setBannerFormat] = useState<BannerFormat>('webp')
  const [bannerStatic, setBannerStatic] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [exportProgress, setExportProgress] = useState(0)
  const [notice, setNotice] = useState('')
  const [exportError, setExportError] = useState('')
  const [renderError, setRenderError] = useState('')
  const [playing, setPlaying] = useState(true)
  const [videoOpen, setVideoOpen] = useState(false)
  const [videoBusy, setVideoBusy] = useState(false)
  const exportCancelled = useRef(false)
  const { assets, errors: assetErrors, pending } = usePosterAssets()
  const { ready: fontsReady, error: fontError } = useRubikFont()
  const spec = layoutSpecs[settings.layout]
  const mainShip = shipById.get(settings.mainShipId)
  const isBanner = settings.layout === 'banner'
  const animatedBanner = isBanner && !bannerStatic
  const exportWidth = spec.width * exportScale
  const exportHeight = spec.height * exportScale
  const resourcesReady = pending === 0 && fontsReady && assetErrors.length === 0 && !fontError

  function update<K extends keyof PosterSettings>(key: K, value: PosterSettings[K]): void {
    setSettings((current) => ({ ...current, [key]: value }))
    setNotice('')
    setExportError('')
  }

  const onRenderError = useCallback((message: string) => setRenderError(message), [])

  function changeLayout(layout: PosterLayout): void {
    if (exporting || videoBusy) return
    update('layout', layout)
    setExportScale(1)
    setVideoOpen(false)
  }

  function reset(): void {
    if (exporting || videoBusy) return
    setSettings(defaultSettings)
    setExportScale(1)
    setPosterFormat('webp')
    setBannerFormat('webp')
    setBannerStatic(false)
    setPlaying(true)
    setNotice('参数已恢复为默认值')
    setExportError('')
  }

  async function exportPoster(): Promise<void> {
    if (exporting || videoBusy || !resourcesReady || renderError) return
    if (animatedBanner && (!Number.isInteger(settings.bannerFps) || settings.bannerFps < 1 || settings.bannerFps > 60)) {
      setNotice('')
      setExportError('导出 FPS 必须是 1–60 的整数')
      return
    }
    const format = animatedBanner ? bannerFormat : posterFormat
    const formatName = animatedBanner && format === 'png' ? 'APNG' : format.toUpperCase()
    exportCancelled.current = false
    setExporting(true)
    setExportProgress(0)
    setNotice(animatedBanner ? `准备生成动画 ${formatName}…` : `正在生成无损 ${formatName}…`)
    setExportError('')
    const onProgress = (progress: { message: string, value: number }) => {
      setNotice(progress.message)
      setExportProgress(progress.value)
    }
    try {
      const blob = animatedBanner
        ? await encodeBanner({ settings, assets, scale: exportScale, format: bannerFormat }, onProgress)
        : await encodePoster({ settings, assets, scale: exportScale, format: posterFormat }, onProgress)
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = `fossic-${settings.layout}${isBanner && bannerStatic ? '-static' : ''}-${exportWidth}x${exportHeight}.${format}`
      document.body.appendChild(link)
      link.click()
      link.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 1000)
      setNotice(`已导出 ${exportWidth} × ${exportHeight} ${animatedBanner ? '动画 ' : ''}${formatName}`)
      setExportProgress(1)
    } catch (error) {
      console.error(error)
      setExportError(exportCancelled.current ? '导出已取消' : `导出失败：${error instanceof Error ? error.message : String(error)}`)
      setNotice('')
    } finally {
      setExporting(false)
    }
  }

  function cancelExport(): void {
    exportCancelled.current = true
    terminateFFmpeg()
    setNotice('正在终止导出')
  }

  const blocked = exporting || videoBusy || !resourcesReady || Boolean(renderError)
  const footerText = settings.layout === 'hero' && mainShip
    ? `主舰 / ${mainShip.name}`
    : settings.layout === 'atlasArc'
      ? 'ARC / 07 HULLS'
      : settings.layout === 'atlasLens'
        ? 'LENS / 06 HULLS'
        : `${settings.bannerFps} FPS / ${BANNER_DURATION_SECONDS} SEC / LOOP`

  return (
    <div className="workbench">
      <header className="app-header">
        <div className="app-brand"><img src="/assets/crest.png" alt="" /><div><strong>FOSSIC</strong><span>舰船宣传图工作台</span></div></div>
        <div className="document-title"><span>ASTERIA DIRECTORATE</span><span className="document-divider">/</span><span>{layoutNames[settings.layout]}</span></div>
        <div className={`engine-status ${resourcesReady ? 'is-ready' : ''}`}><span className="status-dot" />{!fontsReady && !fontError ? '载入 Rubik' : pending > 0 ? `载入素材 ${pending}` : assetErrors.length > 0 || fontError ? '资源异常' : '渲染器就绪'}</div>
      </header>

      <div className="workspace">
        <nav className="tool-rail" aria-label="工作台工具">
          <div className="rail-group">
            <button disabled={exporting || videoBusy} className={settings.layout === 'hero' && !videoOpen ? 'is-active' : ''} title="横版主视觉" aria-label="切换到横版主视觉" onClick={() => changeLayout('hero')}><Icon name="hero" /></button>
            <button disabled={exporting || videoBusy} className={settings.layout === 'atlasArc' && !videoOpen ? 'is-active' : ''} title="星坠设计" aria-label="切换到星坠设计图鉴" onClick={() => changeLayout('atlasArc')}><Icon name="atlas" /></button>
            <button disabled={exporting || videoBusy} className={settings.layout === 'atlasLens' && !videoOpen ? 'is-active' : ''} title="紫菀设计" aria-label="切换到紫菀设计图鉴" onClick={() => changeLayout('atlasLens')}><Icon name="arc" /></button>
            <button disabled={exporting || videoBusy} className={settings.layout === 'banner' && !videoOpen ? 'is-active' : ''} title="通用横幅" aria-label="切换到通用横幅" onClick={() => changeLayout('banner')}><Icon name="banner" /></button>
            <button disabled={exporting} className={videoOpen ? 'is-active' : ''} title="视频转 WebP / GIF" aria-label="打开视频转动画工具" aria-pressed={videoOpen} onClick={() => setVideoOpen(true)}><Icon name="video" /></button>
          </div>
          <div className="rail-group rail-group--middle">
            <button className={settings.showGrid ? 'is-active' : ''} title="坐标网格" aria-label="切换坐标网格" aria-pressed={settings.showGrid} onClick={() => update('showGrid', !settings.showGrid)}><Icon name="grid" /></button>
            <button className={settings.showAnnotations ? 'is-active' : ''} title="技术标注" aria-label="切换舰船标注" aria-pressed={settings.showAnnotations} onClick={() => update('showAnnotations', !settings.showAnnotations)}><Icon name="label" /></button>
            <button className={settings.showSafeArea ? 'is-active' : ''} title="安全边距" aria-label="切换安全边距" aria-pressed={settings.showSafeArea} onClick={() => update('showSafeArea', !settings.showSafeArea)}><Icon name="safe" /></button>
          </div>
          <div className="rail-group rail-group--bottom">
            <button title="重置参数" aria-label="重置参数" disabled={exporting || videoBusy} onClick={reset}><Icon name="reset" /></button>
            <button className="rail-export" title="导出图片" aria-label="导出图片" disabled={blocked} onClick={exportPoster}><Icon name="export" /></button>
          </div>
        </nav>

        <main className="preview-area">
          <div className="preview-meta"><div><span className="meta-index">{String(Object.keys(layoutNames).indexOf(settings.layout) + 1).padStart(2, '0')}</span><span>{layoutNames[settings.layout]}</span></div><span>{isBanner ? exportWidth : spec.width} × {isBanner ? exportHeight : spec.height} PX</span></div>
          <div className={`canvas-stage canvas-stage--${settings.layout}`}>
            <div className="canvas-registration canvas-registration--top" />
            <PosterCanvas settings={settings} assets={assets} fontsReady={fontsReady} playing={playing} scale={isBanner ? exportScale : 1} onRenderError={onRenderError} />
            <div className="canvas-registration canvas-registration--bottom" />
          </div>
          <div className="preview-footer"><span>CANVAS 2D</span><span>{footerText}</span><span>{isBanner ? (playing ? '循环播放中' : '已暂停') : '预览等比缩放'}</span></div>
        </main>

        {videoOpen ? <VideoConverter onClose={() => setVideoOpen(false)} onBusyChange={setVideoBusy} /> : (
          <aside className="inspector" aria-label="海报参数">
            <div className="inspector-heading"><div><span className="eyebrow">INSPECTOR</span><h1>海报参数</h1></div><button type="button" className="text-button" disabled={exporting || videoBusy} onClick={reset}>重置</button></div>
            <section className="panel-section">
              <h2><span>01</span>版式</h2>
              <div className="layout-options">
                <button disabled={exporting || videoBusy} className={settings.layout === 'hero' ? 'is-selected' : ''} onClick={() => changeLayout('hero')}><span>主视觉</span><small>2560 × 1440</small></button>
                <button disabled={exporting || videoBusy} className={settings.layout === 'atlasArc' ? 'is-selected' : ''} onClick={() => changeLayout('atlasArc')}><span>星坠设计</span><small>ARC / 07</small></button>
                <button disabled={exporting || videoBusy} className={settings.layout === 'atlasLens' ? 'is-selected' : ''} onClick={() => changeLayout('atlasLens')}><span>紫菀设计</span><small>LENS / 06</small></button>
                <button disabled={exporting || videoBusy} className={settings.layout === 'banner' ? 'is-selected' : ''} onClick={() => changeLayout('banner')}><span>通用横幅</span><small>1920 × 360</small></button>
              </div>
            </section>

            {settings.layout === 'hero' && (
              <section className="panel-section">
                <h2><span>02</span>主舰</h2>
                <label className="select-label" htmlFor="main-ship">编队中心舰体</label>
                <div className="select-wrap"><select id="main-ship" value={settings.mainShipId} onChange={(event) => update('mainShipId', event.target.value)}>{heroShipIds.map((id) => { const ship = shipById.get(id)!; return <option key={id} value={id}>{ship.name} / {ship.designation}</option> })}</select></div>
                {mainShip && <div className="ship-readout"><span>{mainShip.series}</span><span>{mainShip.id.toUpperCase()}</span></div>}
              </section>
            )}

            {isBanner && (
              <section className="panel-section banner-fields">
                <h2><span>02</span>横幅文本</h2>
                <label>主文本<input value={settings.bannerTitle} onChange={(event) => update('bannerTitle', event.target.value)} /></label>
                <label>右侧文本<input value={settings.bannerSide} onChange={(event) => update('bannerSide', event.target.value)} /></label>
                <label>英文标记<input value={settings.bannerKicker} onChange={(event) => update('bannerKicker', event.target.value)} /></label>
                <label>导出 FPS<input type="number" min="1" max="60" step="1" value={settings.bannerFps} disabled={exporting || bannerStatic} onChange={(event) => update('bannerFps', Number(event.target.value))} /></label>
                <button type="button" className="playback-button" onClick={() => setPlaying((current) => !current)}><Icon name={playing ? 'pause' : 'play'} />{playing ? '暂停动画' : '播放动画'}</button>
              </section>
            )}

            <section className="panel-section">
              <h2><span>03</span>画面</h2>
              {!isBanner && <Slider label="发光强度" value={settings.bloom} min={0} max={2} step={0.05} displayValue={settings.bloom.toFixed(2)} onChange={(value) => update('bloom', value)} />}
              <Slider label="背景亮度" value={settings.backgroundBrightness} min={40} max={120} step={1} displayValue={`${settings.backgroundBrightness}%`} onChange={(value) => update('backgroundBrightness', value)} />
            </section>

            <section className="panel-section panel-section--toggles">
              <h2><span>04</span>辅助信息</h2>
              <Toggle label="坐标网格" description="低对比技术网格" checked={settings.showGrid} onChange={(value) => update('showGrid', value)} />
              <Toggle label="技术标注" description="编号与定位线" checked={settings.showAnnotations} onChange={(value) => update('showAnnotations', value)} />
              <Toggle label="安全边距" description="显示画面安全区域" checked={settings.showSafeArea} onChange={(value) => update('showSafeArea', value)} />
            </section>

            <section className="panel-section export-section">
              <h2><span>05</span>导出</h2>
              {isBanner && <div className="export-scale" aria-label="横幅导出类型"><button disabled={exporting} className={!bannerStatic ? 'is-selected' : ''} aria-pressed={!bannerStatic} onClick={() => { setBannerStatic(false); setNotice(''); setExportError('') }}>动画</button><button disabled={exporting} className={bannerStatic ? 'is-selected' : ''} aria-pressed={bannerStatic} onClick={() => { setBannerStatic(true); setNotice(''); setExportError('') }}>静态图</button></div>}
              {animatedBanner ? (
                <div className="export-scale export-scale--formats" aria-label="动画格式">{(['webp', 'gif', 'png'] as BannerFormat[]).map((format) => <button key={format} disabled={exporting} className={bannerFormat === format ? 'is-selected' : ''} aria-pressed={bannerFormat === format} onClick={() => { setBannerFormat(format); setNotice(''); setExportError('') }}>{format === 'png' ? 'APNG' : format.toUpperCase()}</button>)}</div>
              ) : (
                <div className="export-scale" aria-label="图片格式">{(['webp', 'png'] as PosterFormat[]).map((format) => <button key={format} disabled={exporting} className={posterFormat === format ? 'is-selected' : ''} aria-pressed={posterFormat === format} onClick={() => { setPosterFormat(format); setNotice(''); setExportError('') }}>{format.toUpperCase()}</button>)}</div>
              )}
              {isBanner ? (
                <div className="export-scale export-scale--resolutions" aria-label="横幅导出分辨率">{([0.25, 0.5, 1, 2] as ExportScale[]).map((scale) => <button key={scale} disabled={exporting} className={exportScale === scale ? 'is-selected' : ''} aria-pressed={exportScale === scale} onClick={() => { setExportScale(scale); setNotice(''); setExportError('') }}>{spec.width * scale} × {spec.height * scale}</button>)}</div>
              ) : (
                <div className="export-scale" aria-label="导出比例">{([1, 2] as ExportScale[]).map((scale) => <button key={scale} disabled={exporting} className={exportScale === scale ? 'is-selected' : ''} aria-pressed={exportScale === scale} onClick={() => { setExportScale(scale); setNotice(''); setExportError('') }}>{scale}x</button>)}</div>
              )}
              <div className="export-spec"><span>{animatedBanner ? `ANIMATED ${bannerFormat === 'png' ? 'APNG' : bannerFormat.toUpperCase()} / ${settings.bannerFps} FPS` : `${posterFormat.toUpperCase()} / 无损压缩`}</span><span>{exportWidth} × {exportHeight}</span></div>
              <button type="button" className="export-button" disabled={blocked} onClick={exportPoster}><Icon name="export" /><span>{exporting ? '正在导出…' : !resourcesReady ? '等待资源加载' : animatedBanner ? `导出动画 ${bannerFormat === 'png' ? 'APNG' : bannerFormat.toUpperCase()}` : `导出 ${posterFormat.toUpperCase()}`}</span></button>
              {exporting && <button type="button" className="cancel-button" onClick={cancelExport}>取消导出</button>}
              {(notice || exporting) && <p className="notice" role="status">{notice}</p>}
              {exporting && <progress className="export-progress" max="1" value={exportProgress} />}
              {exportError && <p className="error-message" role="alert">{exportError}</p>}
            </section>
          </aside>
        )}
      </div>

      {(assetErrors.length > 0 || renderError || fontError) && <div className="global-error" role="alert"><strong>渲染错误</strong>{renderError && <span>{renderError}</span>}{fontError && <span>{fontError}</span>}{assetErrors.map((error) => <span key={error}>{error}</span>)}</div>}
    </div>
  )
}
