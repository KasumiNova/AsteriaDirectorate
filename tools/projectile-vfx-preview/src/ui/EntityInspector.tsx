import { ReactNode, useState } from 'react';
import {
  BoxUtilPreviewPreset,
  Rgba,
  RibbonWaveType,
  SimulationConfig,
  TrailDecorationGradientStop,
  TrailRibbonDecorationConfig,
  TrailEntityConfig,
  createDefaultTrailRibbonDecorationConfig,
  createDefaultTrailEntityConfig,
} from '../model/preset';

export interface EntityInspectorProps {
  preset: BoxUtilPreviewPreset;
  onPresetChange: (preset: BoxUtilPreviewPreset) => void;
}

type TrailNumberKey = keyof Pick<TrailEntityConfig,
  'startWidth' | 'endWidth' | 'texturePixels' | 'textureSpeed' | 'uvOffset' | 'jitterPower' | 'flickMixValue'>;
type TrailBoolKey = keyof Pick<TrailEntityConfig,
  'flick' | 'syncFlick' | 'stripLineMode' | 'flowWhenPaused' | 'flickWhenPaused'>;

const trailNumberFields: TrailNumberKey[] = [
  'startWidth', 'endWidth', 'texturePixels', 'textureSpeed', 'uvOffset', 'jitterPower', 'flickMixValue',
];

const trailNumberHelp: Record<TrailNumberKey, string> = {
  startWidth: '拖尾起点宽度',
  endWidth: '拖尾末端宽度',
  texturePixels: '纹理采样像素，越大纹理越拉长',
  textureSpeed: '纹理流动速度',
  uvOffset: 'UV 起始偏移',
  jitterPower: '边缘噪声强度',
  flickMixValue: '闪烁混色比',
};

const trailBooleanFields: TrailBoolKey[] = [
  'flick', 'syncFlick', 'stripLineMode', 'flowWhenPaused', 'flickWhenPaused',
];

const trailBooleanHelp: Record<TrailBoolKey, string> = {
  flick: '启用整体闪烁',
  syncFlick: '多段拖尾闪烁同步',
  stripLineMode: '使用 strip line 模式',
  flowWhenPaused: '暂停时仍然流动',
  flickWhenPaused: '暂停时仍然闪烁',
};

const ribbonRenderModeHelp: Record<TrailRibbonDecorationConfig['renderMode'], string> = {
  byNodeCount: '按节点数采样装饰',
  byLength: '按长度采样装饰',
};

const ribbonWaveTypeHelp: Record<RibbonWaveType, string> = {
  sine: '正弦波（平滑波动）',
  noise: '噪声（随机有机漂移）',
  zigzag: '锯齿波（折线抖动）',
};

const channelLabels = ['r', 'g', 'b', 'a'];

function toRgbHex(r: number, g: number, b: number): string {
  const c = (v: number) => Math.max(0, Math.min(255, Math.round(v * 255))).toString(16).padStart(2, '0');
  return `#${c(r)}${c(g)}${c(b)}`;
}

function toColorInputValue(value: number): number {
  return Math.round(value * 255);
}

function fromColorInputValue(value: number): number {
  return Math.max(0, Math.min(255, value)) / 255;
}

export function EntityInspector({ preset, onPresetChange }: EntityInspectorProps) {
  const trail = preset.trailEntities[0] ?? createDefaultTrailEntityConfig();
  const [activeTab, setActiveTab] = useState<'trail' | 'colors' | 'ribbons' | 'simulation'>('trail');
  const [expandedRibbons, setExpandedRibbons] = useState<Record<string, boolean>>({
    'astd_default_ribbon_0': true
  });

  const toggleRibbonExpand = (id: string) => {
    setExpandedRibbons((prev) => ({ ...prev, [id]: !prev[id] }));
  };

  const updateTrail = (patch: Partial<TrailEntityConfig>) => {
    onPresetChange({
      ...preset,
      trailEntities: preset.trailEntities.map((entity, index) => (index === 0 ? { ...entity, ...patch } : entity)),
    });
  };

  const updateTrailColor = (
    field: 'startColor' | 'endColor' | 'startEmissive' | 'endEmissive',
    channel: number,
    value: number,
  ) => {
    const next = [...trail[field]] as Rgba;
    next[channel] = value;
    updateTrail({ [field]: next });
  };

  const updateTrailColorBatch = (
    field: 'startColor' | 'endColor' | 'startEmissive' | 'endEmissive',
    r: number, g: number, b: number,
  ) => {
    const next = [...trail[field]] as Rgba;
    next[0] = r; next[1] = g; next[2] = b;
    updateTrail({ [field]: next });
  };

  const updateRibbonDecoration = (index: number, patch: Partial<TrailRibbonDecorationConfig>) => {
    const ribbonDecorations = trail.ribbonDecorations.map((item, itemIndex) => (itemIndex === index ? { ...item, ...patch } : item));
    onPresetChange({
      ...preset,
      ribbonDecorations,
      trailEntities: preset.trailEntities.map((entity, entityIndex) => (entityIndex === 0 ? { ...entity, ribbonDecorations } : entity)),
    });
  };

  const updateRibbonDecorationColor = (
    index: number,
    field: 'color' | 'startColor' | 'endColor',
    channel: number,
    value: number,
  ) => {
    const color = [...trail.ribbonDecorations[index][field]] as Rgba;
    color[channel] = value;
    updateRibbonDecoration(index, { [field]: color });
  };

  const updateRibbonDecorationColorBatch = (index: number, field: 'color' | 'startColor' | 'endColor', r: number, g: number, b: number) => {
    const color = [...trail.ribbonDecorations[index][field]] as Rgba;
    color[0] = r;
    color[1] = g;
    color[2] = b;
    updateRibbonDecoration(index, { [field]: color });
  };

  const updateRibbonGradientStop = (ribbonIndex: number, stopIndex: number, patch: Partial<TrailDecorationGradientStop>) => {
    const ribbon = trail.ribbonDecorations[ribbonIndex] ?? createDefaultTrailRibbonDecorationConfig();
    const stops = ribbon.colorGradient.stops.map((stop, index) => (index === stopIndex ? { ...stop, ...patch } : stop));
    updateRibbonDecoration(ribbonIndex, {
      colorGradient: { ...ribbon.colorGradient, stops },
    });
  };

  const addRibbonDecoration = () => {
    const newId = `astd_default_ribbon_${trail.ribbonDecorations.length}`;
    const ribbonDecorations = [...trail.ribbonDecorations, createDefaultTrailRibbonDecorationConfig(newId)];
    setExpandedRibbons((prev) => ({ ...prev, [newId]: true }));
    onPresetChange({
      ...preset,
      ribbonDecorations,
      trailEntities: preset.trailEntities.map((entity, entityIndex) => (entityIndex === 0 ? { ...entity, ribbonDecorations } : entity)),
    });
  };

  const removeRibbonDecoration = (index: number) => {
    const ribbonDecorations = trail.ribbonDecorations.filter((_, itemIndex) => itemIndex !== index);
    onPresetChange({
      ...preset,
      ribbonDecorations,
      trailEntities: preset.trailEntities.map((entity, entityIndex) => (entityIndex === 0 ? { ...entity, ribbonDecorations } : entity)),
    });
  };

  const addRibbonGradientStop = (ribbonIndex: number) => {
    const ribbon = trail.ribbonDecorations[ribbonIndex] ?? createDefaultTrailRibbonDecorationConfig();
    updateRibbonDecoration(ribbonIndex, {
      colorGradient: {
        ...ribbon.colorGradient,
        stops: [...ribbon.colorGradient.stops, { offset: 1, color: ribbon.endColor }],
      },
    });
  };

  const removeRibbonGradientStop = (ribbonIndex: number, stopIndex: number) => {
    const ribbon = trail.ribbonDecorations[ribbonIndex] ?? createDefaultTrailRibbonDecorationConfig();
    updateRibbonDecoration(ribbonIndex, {
      colorGradient: {
        ...ribbon.colorGradient,
        stops: ribbon.colorGradient.stops.filter((_, index) => index !== stopIndex),
      },
    });
  };

  const updateSimulation = (patch: Partial<SimulationConfig>) => {
    onPresetChange({ ...preset, simulation: { ...preset.simulation, ...patch } });
  };

  return (
    <aside className="entity-inspector">
      <div className="inspector-tabs">
        <button
          type="button"
          className={`tab-btn ${activeTab === 'trail' ? 'active' : ''}`}
          onClick={() => setActiveTab('trail')}
        >
          Trail
        </button>
        <button
          type="button"
          className={`tab-btn ${activeTab === 'colors' ? 'active' : ''}`}
          onClick={() => setActiveTab('colors')}
        >
          Color
        </button>
        <button
          type="button"
          className={`tab-btn ${activeTab === 'ribbons' ? 'active' : ''}`}
          onClick={() => setActiveTab('ribbons')}
        >
          Ribbon ({trail.ribbonDecorations.length})
        </button>
        <button
          type="button"
          className={`tab-btn ${activeTab === 'simulation' ? 'active' : ''}`}
          onClick={() => setActiveTab('simulation')}
        >
          Sim
        </button>
      </div>

      <div className="inspector-tab-content">
        {/* TAB 1: BASE TRAIL */}
        <div className={`tab-pane ${activeTab === 'trail' ? 'active' : ''}`}>
          <ParamGroup
            title="TrailEntity"
            id={trail.id}
            badge="Runtime"
            description="拖尾本体基本物理属性与参数设置。"
          >
            {trailNumberFields.map((field) => (
              <NumberRow
                key={field}
                label={field}
                ariaLabel={`trail-${field}`}
                help={trailNumberHelp[field]}
                value={trail[field]}
                onChange={(v) => updateTrail({ [field]: v })}
              />
            ))}
            {trailBooleanFields.map((field) => (
              <BoolRow
                key={field}
                label={field}
                ariaLabel={`trail-${field}`}
                help={trailBooleanHelp[field]}
                checked={trail[field]}
                onChange={(v) => updateTrail({ [field]: v } as Partial<TrailEntityConfig>)}
              />
            ))}
            <div className="param-row">
              <label className="param-label" htmlFor="trail-diffuseSpritePath">
                <span className="param-name">diffuseSpritePath</span>
                <span className="param-help">漫反射材质贴图路径</span>
              </label>
              <div className="param-control">
                <input
                  id="trail-diffuseSpritePath"
                  type="text"
                  value={trail.diffuseSpritePath}
                  onChange={(event) => updateTrail({ diffuseSpritePath: event.currentTarget.value })}
                />
              </div>
            </div>
            <div className="param-row">
              <label className="param-label" htmlFor="trail-emissiveSpritePath">
                <span className="param-name">emissiveSpritePath</span>
                <span className="param-help">发光材质贴图路径</span>
              </label>
              <div className="param-control">
                <input
                  id="trail-emissiveSpritePath"
                  type="text"
                  value={trail.emissiveSpritePath}
                  onChange={(event) => updateTrail({ emissiveSpritePath: event.currentTarget.value })}
                />
              </div>
            </div>
            <SelectRow
              label="blendMode"
              ariaLabel="trail-blendMode"
              help="渲染混合模式"
              value={trail.blendMode}
              options={['normal', 'additive']}
              onChange={(value) => updateTrail({ blendMode: value as any })}
            />
          </ParamGroup>
        </div>

        {/* TAB 2: COLOR & GLOW */}
        <div className={`tab-pane ${activeTab === 'colors' ? 'active' : ''}`}>
          <ParamGroup
            title="Color Settings"
            id="colors"
            badge="Runtime"
            description="拖尾起点、终点色与发光强度的RGBA调色板。"
          >
            {(['startColor', 'endColor', 'startEmissive', 'endEmissive'] as const).map((field) => (
              <ColorRow
                key={field}
                label={field}
                help={field.includes('Emissive') ? '发光色 RGBA' : '可见色 RGBA'}
                ariaPrefix={field}
                value={trail[field]}
                onChange={(channel, value) => updateTrailColor(field, channel, value)}
                onRgbChange={(r, g, b) => updateTrailColorBatch(field, r, g, b)}
              />
            ))}
          </ParamGroup>
        </div>

        {/* TAB 3: RIBBON DECORATIONS */}
        <div className={`tab-pane ${activeTab === 'ribbons' ? 'active' : ''}`}>
          <section className="param-group">
            <header className="param-group-head">
              <h2>Ribbon Decorations</h2>
              <span className="param-group-meta">
                <span className="muted-chip">Runtime</span>
                <button type="button" className="btn-primary btn-small" onClick={addRibbonDecoration}>
                  + Add Ribbon
                </button>
              </span>
            </header>
            <p className="param-group-desc">在拖尾之上叠加的波动缎带装饰，大幅增加视觉动感。</p>

            <div className="ribbons-accordion-container">
              {trail.ribbonDecorations.map((ribbon, ribbonIndex) => {
                const isExpanded = expandedRibbons[ribbon.id] !== false;
                return (
                  <section key={ribbon.id} className={`ribbon-card-panel ${isExpanded ? 'expanded' : 'collapsed'}`}>
                    <header className="ribbon-card-header" onClick={() => toggleRibbonExpand(ribbon.id)}>
                      <div className="ribbon-title-section">
                        <span className="accordion-arrow">{isExpanded ? '▼' : '►'}</span>
                        <strong className="ribbon-id-title">{ribbon.id}</strong>
                        <span className={`status-badge ${ribbon.enabled ? 'enabled' : 'disabled'}`}>
                          {ribbon.enabled ? 'Active' : 'Inactive'}
                        </span>
                      </div>
                      <div className="ribbon-action-buttons" onClick={(e) => e.stopPropagation()}>
                        <button
                          type="button"
                          className="btn-action-small"
                          onClick={() => updateRibbonDecoration(ribbonIndex, { enabled: !ribbon.enabled })}
                        >
                          {ribbon.enabled ? 'Disable' : 'Enable'}
                        </button>
                        <button
                          type="button"
                          className="btn-danger-small"
                          onClick={() => removeRibbonDecoration(ribbonIndex)}
                        >
                          Remove
                        </button>
                      </div>
                    </header>

                    {isExpanded && (
                      <div className="ribbon-card-body">
                        <BoolRow
                          label="enabled"
                          ariaLabel={`trail-ribbon-${ribbonIndex}-enabled`}
                          help="启用此缎带装饰"
                          checked={ribbon.enabled}
                          onChange={(value) => updateRibbonDecoration(ribbonIndex, { enabled: value })}
                        />
                        <SelectRow
                          label="renderMode"
                          ariaLabel={`trail-ribbon-${ribbonIndex}-renderMode`}
                          help={ribbonRenderModeHelp[ribbon.renderMode]}
                          value={ribbon.renderMode}
                          options={['byNodeCount', 'byLength']}
                          onChange={(value) =>
                            updateRibbonDecoration(ribbonIndex, {
                              renderMode: value as TrailRibbonDecorationConfig['renderMode'],
                            })
                          }
                        />
                        <NumberRow
                          label="startOffset"
                          ariaLabel={`trail-ribbon-${ribbonIndex}-startOffset`}
                          help="相对拖尾起点的偏移"
                          value={ribbon.startOffset}
                          onChange={(value) => updateRibbonDecoration(ribbonIndex, { startOffset: value })}
                        />
                        <NumberRow
                          label="endOffset"
                          ariaLabel={`trail-ribbon-${ribbonIndex}-endOffset`}
                          help="相对拖尾末端的偏移"
                          value={ribbon.endOffset}
                          onChange={(value) => updateRibbonDecoration(ribbonIndex, { endOffset: value })}
                        />
                        <NumberRow
                          label="thickness"
                          ariaLabel={`trail-ribbon-${ribbonIndex}-thickness`}
                          help="装饰线条厚度系数"
                          value={ribbon.thickness}
                          onChange={(value) => updateRibbonDecoration(ribbonIndex, { thickness: value })}
                        />
                        <NumberRow
                          label="alphaScale"
                          ariaLabel={`trail-ribbon-${ribbonIndex}-alphaScale`}
                          help="装饰透明度倍率"
                          value={ribbon.alphaScale}
                          onChange={(value) => updateRibbonDecoration(ribbonIndex, { alphaScale: value })}
                        />
                        <NumberRow
                          label="lengthScale"
                          ariaLabel={`trail-ribbon-${ribbonIndex}-lengthScale`}
                          help="按长度渲染时的长度倍率"
                          value={ribbon.lengthScale}
                          onChange={(value) => updateRibbonDecoration(ribbonIndex, { lengthScale: value })}
                        />
                        <NumberRow
                          label="nodeCountScale"
                          ariaLabel={`trail-ribbon-${ribbonIndex}-nodeCountScale`}
                          help="按节点数渲染时的节点倍率"
                          value={ribbon.nodeCountScale}
                          onChange={(value) => updateRibbonDecoration(ribbonIndex, { nodeCountScale: value })}
                        />
                        <NumberRow
                          label="waveAmplitude"
                          ariaLabel={`trail-ribbon-${ribbonIndex}-waveAmplitude`}
                          help="螺旋幅度"
                          value={ribbon.waveAmplitude}
                          onChange={(value) => updateRibbonDecoration(ribbonIndex, { waveAmplitude: value })}
                        />
                        <NumberRow
                          label="waveFrequency"
                          ariaLabel={`trail-ribbon-${ribbonIndex}-waveFrequency`}
                          help="螺旋频率"
                          value={ribbon.waveFrequency}
                          onChange={(value) => updateRibbonDecoration(ribbonIndex, { waveFrequency: value })}
                        />
                        <NumberRow
                          label="waveSpeed"
                          ariaLabel={`trail-ribbon-${ribbonIndex}-waveSpeed`}
                          help="螺旋速度"
                          value={ribbon.waveSpeed}
                          onChange={(value) => updateRibbonDecoration(ribbonIndex, { waveSpeed: value })}
                        />
                        <SelectRow
                          label="waveType"
                          ariaLabel={`trail-ribbon-${ribbonIndex}-waveType`}
                          help={ribbonWaveTypeHelp[ribbon.waveType]}
                          value={ribbon.waveType}
                          options={['sine', 'noise', 'zigzag']}
                          onChange={(value) =>
                            updateRibbonDecoration(ribbonIndex, { waveType: value as RibbonWaveType })
                          }
                        />
                        {ribbon.waveType === 'noise' && (
                          <NumberRow
                            label="noiseScale"
                            ariaLabel={`trail-ribbon-${ribbonIndex}-noiseScale`}
                            help="噪声空间频率（越大抖动越密集）"
                            value={ribbon.noiseScale}
                            onChange={(value) => updateRibbonDecoration(ribbonIndex, { noiseScale: value })}
                          />
                        )}
                        <NumberRow
                          label="blur"
                          ariaLabel={`trail-ribbon-${ribbonIndex}-blur`}
                          help="模糊强度"
                          value={ribbon.blur}
                          onChange={(value) => updateRibbonDecoration(ribbonIndex, { blur: value })}
                        />
                        <ColorRow
                          label="color"
                          help="独立颜色，默认跟随弹体颜色"
                          ariaPrefix={`trail-ribbon-${ribbonIndex}-color`}
                          value={ribbon.color}
                          onChange={(channel, value) => updateRibbonDecorationColor(ribbonIndex, 'color', channel, value)}
                          onRgbChange={(r, g, b) => updateRibbonDecorationColorBatch(ribbonIndex, 'color', r, g, b)}
                        />
                        <ColorRow
                          label="startColor"
                          help="装饰起点颜色 RGBA"
                          ariaPrefix={`trail-ribbon-${ribbonIndex}-startColor`}
                          value={ribbon.startColor}
                          onChange={(channel, value) => updateRibbonDecorationColor(ribbonIndex, 'startColor', channel, value)}
                          onRgbChange={(r, g, b) => updateRibbonDecorationColorBatch(ribbonIndex, 'startColor', r, g, b)}
                        />
                        <ColorRow
                          label="endColor"
                          help="装饰末端颜色 RGBA"
                          ariaPrefix={`trail-ribbon-${ribbonIndex}-endColor`}
                          value={ribbon.endColor}
                          onChange={(channel, value) => updateRibbonDecorationColor(ribbonIndex, 'endColor', channel, value)}
                          onRgbChange={(r, g, b) => updateRibbonDecorationColorBatch(ribbonIndex, 'endColor', r, g, b)}
                        />
                        <BoolRow
                          label="colorGradient.enabled"
                          ariaLabel={`trail-ribbon-${ribbonIndex}-colorGradient-enabled`}
                          help="启用多色混合渐变"
                          checked={ribbon.colorGradient.enabled}
                          onChange={(value) =>
                            updateRibbonDecoration(ribbonIndex, {
                              colorGradient: { ...ribbon.colorGradient, enabled: value },
                            })
                          }
                        />
                        <div className="panel-inline-head panel-inline-subhead">
                          <span className="param-subgroup-title text-glow">Gradient Stops</span>
                          <button
                            type="button"
                            className="btn-action-small"
                            onClick={() => addRibbonGradientStop(ribbonIndex)}
                          >
                            Add Stop
                          </button>
                        </div>
                        {ribbon.colorGradient.stops.map((stop, stopIndex) => (
                          <div
                            key={`${ribbon.id}-stop-${stopIndex}`}
                            className="param-row param-row-nested color-gradient-stop-card"
                          >
                            <label
                              className="param-label"
                              htmlFor={`trail-ribbon-${ribbonIndex}-stop-${stopIndex}`}
                            >
                              <span className="param-name">stop {stopIndex}</span>
                              <span className="param-help">渐变偏移点与色彩值</span>
                            </label>
                            <div className="gradient-stop-control-grid">
                              <div className="gradient-offset-wrapper">
                                <span>Offset:</span>
                                <input
                                  id={`trail-ribbon-${ribbonIndex}-stop-${stopIndex}`}
                                  aria-label={`trail-ribbon-${ribbonIndex}-stop-${stopIndex}-offset`}
                                  type="number"
                                  min="0"
                                  max="1"
                                  step="0.01"
                                  value={stop.offset}
                                  onChange={(event) =>
                                    updateRibbonGradientStop(ribbonIndex, stopIndex, {
                                      offset: Number(event.currentTarget.value),
                                    })
                                  }
                                />
                              </div>
                              <div className="param-control param-control-color nested-stop-colors">
                                {stop.color.map((channel, channelIndex) => (
                                  <label key={channelIndex} className="param-color-cell">
                                    <span>{channelLabels[channelIndex]}</span>
                                    <input
                                      aria-label={`trail-ribbon-${ribbonIndex}-stop-${stopIndex}-color-${channelIndex}`}
                                      type="number"
                                      min="0"
                                      max="255"
                                      step="1"
                                      value={toColorInputValue(channel)}
                                      onChange={(event) => {
                                        const next = [...stop.color] as Rgba;
                                        next[channelIndex] = fromColorInputValue(
                                          Number(event.currentTarget.value)
                                        );
                                        updateRibbonGradientStop(ribbonIndex, stopIndex, { color: next });
                                      }}
                                    />
                                  </label>
                                ))}
                                <button
                                  type="button"
                                  className="btn-danger-small btn-remove-stop"
                                  onClick={() => removeRibbonGradientStop(ribbonIndex, stopIndex)}
                                >
                                  Remove
                                </button>
                              </div>
                            </div>
                          </div>
                        ))}
                      </div>
                    )}
                  </section>
                );
              })}
            </div>
          </section>
        </div>

        {/* TAB 4: SIMULATION */}
        <div className={`tab-pane ${activeTab === 'simulation' ? 'active' : ''}`}>
          <ParamGroup
            title="Simulation"
            id="simulation"
            badge="Preview Only"
            description="前端预览模拟曲线，只影响画布表现。"
          >
            <NumberRow
              label="curveAmount"
              ariaLabel="sim-curveAmount"
              help="弯曲轨迹振幅（像素），0 = 直线飞行"
              value={preset.simulation.curveAmount}
              onChange={(v) => updateSimulation({ curveAmount: v })}
            />
            <NumberRow
              label="curveFrequency"
              ariaLabel="sim-curveFrequency"
              help="弯曲频率（Hz），控制正弦波周期"
              value={preset.simulation.curveFrequency}
              onChange={(v) => updateSimulation({ curveFrequency: v })}
            />
          </ParamGroup>
        </div>
      </div>
    </aside>
  );
}

interface ParamGroupProps {
  title: string;
  id: string;
  badge: 'Preview Only' | 'Runtime';
  description: string;
  children: ReactNode;
}

function ParamGroup({ title, id, badge, description, children }: ParamGroupProps) {
  return (
    <section className="param-group">
      <header className="param-group-head">
        <h2>{title}</h2>
        <span className="param-group-meta">
          <span className={`muted-chip ${badge === 'Runtime' ? 'badge-runtime' : 'badge-preview'}`}>
            {badge}
          </span>
          <span className="param-group-id">{id}</span>
        </span>
      </header>
      <p className="param-group-desc">{description}</p>
      <div className="param-list">{children}</div>
    </section>
  );
}

interface NumberRowProps {
  label: string;
  ariaLabel: string;
  help: string;
  value: number;
  onChange: (value: number) => void;
}

function NumberRow({ label, ariaLabel, help, value, onChange }: NumberRowProps) {
  return (
    <div className="param-row">
      <label className="param-label" htmlFor={ariaLabel}>
        <span className="param-name">{label}</span>
        <span className="param-help">{help}</span>
      </label>
      <div className="param-control">
        <input
          id={ariaLabel}
          aria-label={ariaLabel}
          type="number"
          step="any"
          value={value}
          onChange={(event) => onChange(Number(event.currentTarget.value))}
        />
      </div>
    </div>
  );
}

interface BoolRowProps {
  label: string;
  ariaLabel: string;
  help: string;
  checked: boolean;
  onChange: (value: boolean) => void;
}

function BoolRow({ label, ariaLabel, help, checked, onChange }: BoolRowProps) {
  return (
    <div className="param-row">
      <label className="param-label" htmlFor={ariaLabel}>
        <span className="param-name">{label}</span>
        <span className="param-help">{help}</span>
      </label>
      <div className="param-control param-control-bool">
        <input
          id={ariaLabel}
          aria-label={ariaLabel}
          type="checkbox"
          checked={checked}
          onChange={(event) => onChange(event.currentTarget.checked)}
        />
      </div>
    </div>
  );
}

interface SelectRowProps {
  label: string;
  ariaLabel: string;
  help: string;
  value: string;
  options: string[];
  onChange: (value: string) => void;
}

function SelectRow({ label, ariaLabel, help, value, options, onChange }: SelectRowProps) {
  return (
    <div className="param-row">
      <label className="param-label" htmlFor={ariaLabel}>
        <span className="param-name">{label}</span>
        <span className="param-help">{help}</span>
      </label>
      <div className="param-control">
        <select
          id={ariaLabel}
          aria-label={ariaLabel}
          value={value}
          onChange={(event) => onChange(event.currentTarget.value)}
        >
          {options.map((opt) => (
            <option key={opt} value={opt}>
              {opt}
            </option>
          ))}
        </select>
      </div>
    </div>
  );
}

interface ColorRowProps {
  label: string;
  help: string;
  ariaPrefix: string;
  value: Rgba;
  onChange: (channel: number, value: number) => void;
  onRgbChange: (r: number, g: number, b: number) => void;
}

function ColorRow({ label, help, ariaPrefix, value, onChange, onRgbChange }: ColorRowProps) {
  const rVal = toColorInputValue(value[0]);
  const gVal = toColorInputValue(value[1]);
  const bVal = toColorInputValue(value[2]);
  const aVal = toColorInputValue(value[3]);

  const swatch = `rgba(${rVal}, ${gVal}, ${bVal}, ${value[3]})`;
  const rgbHex = toRgbHex(value[0], value[1], value[2]);

  return (
    <div className="param-row color-row-expanded">
      <div className="color-header">
        <span className="param-label">
          <span className="param-name">
            <span className="param-swatch-wrapper">
              <span className="param-swatch" style={{ background: swatch }} />
              <input
                type="color"
                className="param-swatch-picker"
                value={rgbHex}
                title="点击打开取色器"
                onChange={(e) => {
                  const hex = e.currentTarget.value;
                  const r = parseInt(hex.slice(1, 3), 16) / 255;
                  const g = parseInt(hex.slice(3, 5), 16) / 255;
                  const b = parseInt(hex.slice(5, 7), 16) / 255;
                  onRgbChange(r, g, b);
                }}
              />
            </span>
            {label}
          </span>
          <span className="param-help">{help}</span>
        </span>
        <span
          className="color-hex-badge"
          onClick={() => {
            navigator.clipboard.writeText(rgbHex).catch(() => {});
          }}
          title="点击复制 HEX 代码"
        >
          {rgbHex}
        </span>
      </div>

      <div className="color-sliders-grid">
        {value.map((channel, index) => {
          const val255 = toColorInputValue(channel);
          return (
            <div key={index} className="color-slider-item">
              <span className="color-slider-channel-label">{channelLabels[index].toUpperCase()}</span>
              <input
                type="range"
                min="0"
                max="255"
                step="1"
                value={val255}
                onChange={(event) =>
                  onChange(index, fromColorInputValue(Number(event.currentTarget.value)))
                }
                className={`range-slider slider-${channelLabels[index]}`}
              />
              <input
                aria-label={`${ariaPrefix}-${index}`}
                type="number"
                min="0"
                max="255"
                step="1"
                value={val255}
                onChange={(event) =>
                  onChange(index, fromColorInputValue(Number(event.currentTarget.value)))
                }
                className="color-number-input"
              />
            </div>
          );
        })}
      </div>
    </div>
  );
}
