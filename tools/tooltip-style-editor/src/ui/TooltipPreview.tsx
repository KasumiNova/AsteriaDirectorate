import { useEffect, useMemo, useRef, useState } from 'react';
import type {
  Rgba,
  TooltipBlock,
  TooltipColorRole,
  TooltipPreset,
  TooltipTableCell,
  TooltipTextHighlight,
} from '../model/tooltipPreset';
import { estimateTooltipLayout } from '../render/tooltipLayout';
import { FullscreenShaderRenderer } from '../render/webgl/fullscreenShaderRenderer';

type TooltipPreviewProps = {
  preset: TooltipPreset;
};

const rgba = (color: Rgba): string => `rgba(${color.r}, ${color.g}, ${color.b}, ${color.a})`;
const DESIGN_TYPE_COLOR: Rgba = { r: 118, g: 139, b: 139, a: 1 };

const isRgba = (value: number | Rgba | undefined): value is Rgba =>
  typeof value === 'object' && value !== null && 'r' in value;

const uniformRgba = (
  uniforms: TooltipPreset['background']['uniforms'],
  key: 'u_primaryColor' | 'u_accentColor',
  fallback: Rgba,
): Rgba => {
  const value = uniforms[key];
  return isRgba(value) ? value : fallback;
};

const uniformNumber = (
  uniforms: TooltipPreset['background']['uniforms'],
  key: 'u_intensity',
  fallback: number,
): number => {
  const value = uniforms[key];
  return typeof value === 'number' ? value : fallback;
};

const rgbaToUniform = (color: Rgba): [number, number, number, number] => [
  color.r / 255,
  color.g / 255,
  color.b / 255,
  color.a,
];

const highlightColor = (preset: TooltipPreset, role: TooltipColorRole | undefined): string => {
  switch (role) {
    case 'warning':
      return rgba(preset.theme.text.warning);
    case 'positive':
      return rgba(preset.theme.text.positive);
    case 'orange':
      return rgba(preset.theme.text.orange);
    case 'muted':
      return rgba(preset.theme.text.muted);
    case 'accent':
    default:
      return rgba(preset.theme.section.textColor);
  }
};

const normalizeHighlights = (
  highlights: TooltipBlock['highlights'] | undefined,
): TooltipTextHighlight[] => {
  if (!highlights) {
    return [];
  }

  return highlights.map((highlight) =>
    typeof highlight === 'string' ? { value: highlight } : highlight,
  );
};

const inlineColor = (
  preset: TooltipPreset,
  value: { color?: Rgba; colorRole?: TooltipColorRole },
): string => value.color ? rgba(value.color) : highlightColor(preset, value.colorRole);

const renderHighlightedText = (text: string, block: TooltipBlock, preset: TooltipPreset) => {
  const highlights = normalizeHighlights(block.highlights).filter((highlight) => highlight.value);

  if (highlights.length === 0) {
    return text;
  }

  const parts: Array<{ text: string; highlight?: TooltipTextHighlight }> = [];
  let remaining = text;

  while (remaining.length > 0) {
    const next = highlights
      .map((highlight) => ({ highlight, index: remaining.indexOf(highlight.value) }))
      .filter((match) => match.index >= 0)
      .sort((left, right) => left.index - right.index)[0];

    if (!next) {
      parts.push({ text: remaining });
      break;
    }

    if (next.index > 0) {
      parts.push({ text: remaining.slice(0, next.index) });
    }

    parts.push({
      text: next.highlight.value,
      highlight: next.highlight,
    });
    remaining = remaining.slice(next.index + next.highlight.value.length);
  }

  return parts.map((part, index) =>
    part.highlight ? (
      <span
        className="tooltip-preview__highlight"
        key={`${part.text}-${index}`}
        style={{ color: inlineColor(preset, part.highlight) }}
      >
        {part.text}
      </span>
    ) : (
      <span key={`${part.text}-${index}`}>{part.text}</span>
    ),
  );
};

export const TooltipPreview = ({ preset }: TooltipPreviewProps) => {
  const layout = estimateTooltipLayout(preset);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const rendererRef = useRef<FullscreenShaderRenderer | null>(null);
  const [shaderError, setShaderError] = useState<string | null>(null);
  const primaryColor = useMemo(
    () =>
      uniformRgba(preset.background.uniforms, 'u_primaryColor', {
        r: preset.theme.panel.backgroundColor.r,
        g: preset.theme.panel.backgroundColor.g,
        b: preset.theme.panel.backgroundColor.b,
        a: 1,
      }),
    [preset.background.uniforms, preset.theme.panel.backgroundColor],
  );
  const accentColor = useMemo(
    () => uniformRgba(preset.background.uniforms, 'u_accentColor', preset.theme.section.textColor),
    [preset.background.uniforms, preset.theme.section.textColor],
  );
  const intensity = uniformNumber(preset.background.uniforms, 'u_intensity', 1);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) {
      return undefined;
    }

    if (typeof window.WebGLRenderingContext === 'undefined') {
      setShaderError('WebGL is not available.');
      return undefined;
    }

    try {
      rendererRef.current = new FullscreenShaderRenderer(canvas);
      const result = rendererRef.current.setFragmentShader(preset.background.fragmentShader);
      setShaderError(result.ok ? null : result.message);
    } catch (error) {
      setShaderError(error instanceof Error ? error.message : String(error));
      return undefined;
    }

    return () => {
      rendererRef.current?.dispose();
      rendererRef.current = null;
    };
  }, []);

  useEffect(() => {
    const renderer = rendererRef.current;
    if (!renderer) {
      return;
    }

    const result = renderer.setFragmentShader(preset.background.fragmentShader);
    setShaderError(result.ok ? null : result.message);
  }, [preset.background.fragmentShader]);

  useEffect(() => {
    const canvas = canvasRef.current;
    const renderer = rendererRef.current;
    if (!canvas || !renderer || shaderError) {
      return undefined;
    }

    let animationFrame = 0;

    const renderFrame = (timeMs: number) => {
      const devicePixelRatio = window.devicePixelRatio || 1;
      const width = Math.max(1, Math.floor(canvas.clientWidth * devicePixelRatio));
      const height = Math.max(1, Math.floor(canvas.clientHeight * devicePixelRatio));

      if (canvas.width !== width || canvas.height !== height) {
        canvas.width = width;
        canvas.height = height;
      }

      renderer.render({
        timeSeconds: timeMs / 1000,
        resolution: [width, height],
        primaryColor: rgbaToUniform(primaryColor),
        accentColor: rgbaToUniform(accentColor),
        intensity,
      });

      animationFrame = window.requestAnimationFrame(renderFrame);
    };

    animationFrame = window.requestAnimationFrame(renderFrame);

    return () => window.cancelAnimationFrame(animationFrame);
  }, [accentColor, intensity, primaryColor, shaderError]);

  return (
    <section
      aria-label="tooltip preview"
      className="tooltip-preview"
      style={{
        width: layout.width,
        minHeight: layout.height,
        borderColor: rgba(preset.theme.panel.borderColor),
        backgroundColor: rgba(preset.theme.panel.backgroundColor),
        color: rgba(preset.theme.text.body),
      }}
    >
      <canvas aria-hidden="true" className="tooltip-preview__shader-canvas" ref={canvasRef} />
      {shaderError ? <div className="tooltip-preview__shader-error">{shaderError}</div> : null}

      <div className="tooltip-preview__content">
        <div className="tooltip-preview__header">
          <div>
            <h1 style={{ color: rgba(preset.theme.text.title) }}>{preset.hullmod.displayName}</h1>
            <p>
              <span style={{ color: rgba(DESIGN_TYPE_COLOR) }}>设计类型： </span>
              <span style={{ color: rgba(preset.hullmod.designTypeColor ?? DESIGN_TYPE_COLOR) }}>
                {preset.hullmod.designType}
              </span>
            </p>
          </div>
        </div>

        <div className="tooltip-preview__blocks">
          {preset.blocks.map((block) => {
            if (block.kind === 'spacer') {
              return <div aria-hidden="true" className="tooltip-preview__spacer" key={block.id} />;
            }

            if (block.kind === 'section-heading') {
              return (
                <div
                  className="tooltip-preview__section-heading"
                  key={block.id}
                  style={{
                    marginTop: block.padTop ?? 0,
                    textAlign: block.align ?? 'center',
                    backgroundColor: rgba(block.backgroundColor ?? preset.theme.section.backgroundColor),
                    color: rgba(block.textColor ?? preset.theme.section.textColor),
                  }}
                >
                  {block.text}
                </div>
              );
            }

            if (block.kind === 'table') {
              return (
                <table className="tooltip-preview__table" key={block.id} style={{ marginTop: block.padTop ?? 0 }}>
                  <thead>
                    <tr>
                      {(block.columns ?? []).map((column) => (
                        <th
                          key={column.id}
                          style={{
                            textAlign: column.align ?? 'center',
                            width: column.width ? `${column.width}%` : undefined,
                          }}
                        >
                          {column.label}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {(block.rows ?? []).map((row) => (
                      <tr key={row.id}>
                        {(block.columns ?? []).map((column, columnIndex) => {
                          const cell = row.cells[columnIndex] ?? ({ text: '' } satisfies TooltipTableCell);
                          return (
                            <td
                              key={column.id}
                              style={{
                                color: inlineColor(preset, cell),
                                textAlign: column.align ?? 'center',
                              }}
                            >
                              {cell.text}
                            </td>
                          );
                        })}
                      </tr>
                    ))}
                  </tbody>
                </table>
              );
            }

            return (
              <p
                className="tooltip-preview__block"
                key={block.id}
                style={{
                  marginTop: block.padTop ?? 0,
                  textAlign: block.align ?? 'start',
                }}
              >
                {renderHighlightedText(block.text, block, preset)}
              </p>
            );
          })}
        </div>
      </div>
    </section>
  );
};
