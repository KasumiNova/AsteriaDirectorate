import { useEffect, useReducer, useState } from 'react';
import './ui/materialComponents';
import './App.css';
import { updateParameter, type EffectState } from './model/effectDefinition';
import { createDefaultEffectState, EFFECTS, findEffect } from './model/effectRegistry';
import { createPlaybackState, playbackReducer } from './model/playback';
import { PreviewViewport } from './ui/PreviewViewport';

export default function App() {
  const [state, setState] = useState<EffectState>(() => createDefaultEffectState());
  const activeEffect = findEffect(state.effectId);
  const [playback, dispatchPlayback] = useReducer(
    playbackReducer,
    activeEffect.loopSeconds,
    createPlaybackState,
  );
  const [error, setError] = useState('');
  const presetJson = JSON.stringify({ effectId: state.effectId, parameters: state.parameters }, null, 2);

  useEffect(() => {
    dispatchPlayback({ type: 'setDuration', durationSeconds: activeEffect.loopSeconds });
  }, [activeEffect.loopSeconds]);

  useEffect(() => {
    let frame = 0;
    let lastTime = performance.now();
    const tick = (now: number) => {
      const deltaSeconds = Math.min(0.05, (now - lastTime) / 1000);
      lastTime = now;
      dispatchPlayback({ type: 'tick', deltaSeconds });
      frame = requestAnimationFrame(tick);
    };
    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, []);

  const setEffect = (effectId: string) => {
    setState(createDefaultEffectState(effectId));
  };

  const setPlaying = (playing: boolean) => {
    dispatchPlayback({ type: 'setPlaying', playing });
  };

  const setParameter = (key: string, value: number) => {
    setState((current) => updateParameter(current, key, value));
  };

  return (
    <main className="app-shell">
      <section className="preview-region" aria-labelledby="app-title">
        <header className="top-bar">
          <div>
            <p className="tool-label">ASTD tool</p>
            <h1 id="app-title">Game VFX Preview</h1>
          </div>
          <md-tabs aria-label="Preview mode tabs" active-tab-index="0">
            <md-primary-tab>Preview</md-primary-tab>
            <md-primary-tab>Preset</md-primary-tab>
          </md-tabs>
        </header>

        <div className="viewport-shell">
          <PreviewViewport
            effect={activeEffect}
            parameters={state.parameters}
            elapsedSeconds={playback.elapsedSeconds}
            onError={setError}
          />
          {error && <p className="viewport-error">{error}</p>}
        </div>
      </section>

      <aside className="control-rail" aria-label="Effect controls">
        <md-elevated-card className="control-card">
          <div className="control-card-content">
            <h2>{activeEffect.name}</h2>
            <md-outlined-select
              label="Effect"
              id="effect-select"
              name="effect-select"
              value={state.effectId}
              onInput={(event) => setEffect((event.currentTarget as HTMLSelectElement).value)}
            >
              {EFFECTS.map((effect) => (
                <md-select-option key={effect.id} value={effect.id}>
                  <div slot="headline">{effect.name}</div>
                </md-select-option>
              ))}
            </md-outlined-select>

            <label className="switch-row">
              <span>Play animation</span>
              <md-switch
                aria-label="Play animation"
                selected={playback.playing}
                onInput={(event) => setPlaying((event.currentTarget as HTMLElement & { selected: boolean }).selected)}
              ></md-switch>
            </label>
          </div>
        </md-elevated-card>

        <md-elevated-card className="control-card">
          <div className="control-card-content parameter-stack">
            <h2>Frame Control</h2>
            <label className="slider-row">
              <span>
                Timeline
                <output>{playback.elapsedSeconds.toFixed(3)}s / {playback.durationSeconds.toFixed(2)}s</output>
              </span>
              <md-slider
                aria-label="Preview timeline"
                min={0}
                max={playback.durationSeconds}
                step={1 / 120}
                value={playback.elapsedSeconds}
                labeled
                onInput={(event) => dispatchPlayback({
                  type: 'seek',
                  elapsedSeconds: Number((event.currentTarget as HTMLInputElement).value),
                })}
              ></md-slider>
            </label>
            <div className="button-row">
              <md-filled-button onClick={() => dispatchPlayback({ type: 'setPlaying', playing: !playback.playing })}>
                {playback.playing ? 'Pause' : 'Play'}
              </md-filled-button>
              <md-outlined-button onClick={() => dispatchPlayback({ type: 'seek', elapsedSeconds: 0 })}>
                First frame
              </md-outlined-button>
              <md-outlined-button onClick={() => dispatchPlayback({
                type: 'seek',
                elapsedSeconds: Math.min(playback.durationSeconds, playback.elapsedSeconds + (1 / 60)),
              })}
              >
                Step +1
              </md-outlined-button>
            </div>
          </div>
        </md-elevated-card>

        <md-elevated-card className="control-card">
          <div className="control-card-content parameter-stack">
            <h2>Shader Parameters</h2>
            {activeEffect.parameters.map((control) => (
              <label className="slider-row" key={control.key}>
                <span>
                  {control.label}
                  <output>{state.parameters[control.key].toFixed(control.step >= 1 ? 0 : 2)}</output>
                </span>
                <md-slider
                  min={control.min}
                  max={control.max}
                  step={control.step}
                  value={state.parameters[control.key]}
                  ticks={control.step >= 1}
                  labeled
                  onInput={(event) => setParameter(control.key, Number((event.currentTarget as HTMLInputElement).value))}
                ></md-slider>
              </label>
            ))}
          </div>
        </md-elevated-card>

        <md-elevated-card className="control-card">
          <div className="control-card-content">
            <h2>Preset JSON</h2>
            <md-outlined-text-field
              label="Current preset"
              id="current-preset-json"
              name="current-preset-json"
              type="textarea"
              rows="12"
              value={presetJson}
              readOnly
            ></md-outlined-text-field>
            <md-outlined-button onClick={() => setState(createDefaultEffectState(state.effectId))}>
              Reset parameters
            </md-outlined-button>
          </div>
        </md-elevated-card>
      </aside>
    </main>
  );
}
