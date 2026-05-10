import { TimelineState, getCurrentFrame } from '../sim/timeline';

export interface TimelineControlsProps {
  state: TimelineState;
  onPlayPause: () => void;
  onStepBackward: () => void;
  onStepForward: () => void;
  onSeek: (timeSeconds: number) => void;
}

export function TimelineControls({ state, onPlayPause, onStepBackward, onStepForward, onSeek }: TimelineControlsProps) {
  const tailMax = Math.min(state.durationSeconds * 0.38, state.durationSeconds);
  const dissolveStart = Math.min(state.durationSeconds * 0.6, state.durationSeconds);
  const keyframes = [0, tailMax, dissolveStart, 1];

  return (
    <section className="timeline-controls" aria-label="Timeline controls">
      <span className="muted-chip">Preview Only</span>
      <button type="button" onClick={onPlayPause}>{state.playing ? 'Pause' : 'Play'}</button>
      <button type="button" onClick={onStepBackward}>-1f</button>
      <button type="button" onClick={onStepForward}>+1f</button>
      <label>
        Time
        <span className="timeline-range-wrap">
          <input
            type="range"
            min={0}
            max={state.durationSeconds}
            step={1 / state.fps}
            value={state.timeSeconds}
            onChange={(event) => onSeek(Number(event.currentTarget.value))}
          />
          <span className="timeline-keyframe-markers" aria-hidden="true">
            {keyframes.map((ratio, index) => (
              <span key={`${index}-${ratio}`} className="timeline-keyframe-marker" style={{ left: `${ratio * 100}%` }} />
            ))}
          </span>
        </span>
      </label>
      <output>Frame {getCurrentFrame(state)} · {state.timeSeconds.toFixed(3)}s / {state.durationSeconds.toFixed(2)}s</output>
    </section>
  );
}
