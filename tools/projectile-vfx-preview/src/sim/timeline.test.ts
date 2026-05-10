import { describe, expect, it } from 'vitest';
import { createInitialTimelineState, getCurrentFrame, timelineReducer } from './timeline';

describe('timelineReducer', () => {
  it('play sets playing state', () => {
    expect(timelineReducer(createInitialTimelineState(), { type: 'play' }).playing).toBe(true);
  });

  it('pause clears playing state', () => {
    expect(timelineReducer({ ...createInitialTimelineState(), playing: true }, { type: 'pause' }).playing).toBe(false);
  });

  it('stepForward advances one frame', () => {
    const state = timelineReducer(createInitialTimelineState(), { type: 'stepForward' });

    expect(state.timeSeconds).toBeCloseTo(1 / 60);
  });

  it('stepBackward rewinds one frame', () => {
    const initial = { ...createInitialTimelineState(), timeSeconds: 1 / 60 };
    const state = timelineReducer(initial, { type: 'stepBackward' });

    expect(state.timeSeconds).toBe(0);
  });

  it('seek clamps to duration', () => {
    const state = timelineReducer(createInitialTimelineState({ fps: 60, durationSeconds: 2 }), { type: 'seek', timeSeconds: 9 });

    expect(state.timeSeconds).toBe(2);
  });

  it('stops playback at the non-looping end frame', () => {
    const state = timelineReducer({ ...createInitialTimelineState({ fps: 60, durationSeconds: 2 }), playing: true, timeSeconds: 1.95 }, { type: 'tick', deltaSeconds: 0.1, loop: false });

    expect(state.playing).toBe(false);
    expect(state.timeSeconds).toBe(2);
  });

  it('restarts from the first frame when play is pressed at the end', () => {
    const state = timelineReducer({ ...createInitialTimelineState({ fps: 60, durationSeconds: 2.5 }), timeSeconds: 2.5 }, { type: 'toggle' });

    expect(state.playing).toBe(true);
    expect(state.timeSeconds).toBe(0);
  });

  it('defaults to 60 FPS', () => {
    const state = createInitialTimelineState();

    expect(state.fps).toBe(60);
    expect(getCurrentFrame(state)).toBe(0);
  });
});
