# OpenRocket 3D Playback Debug Plan

Date: 2026-04-15 (updated 2026-04-15)
Owner: GitHub Copilot + user

## Scope
This document captures the current state of 3D playback work, known bugs, technical stack details, and a focused plan to stabilize motion quality and camera behavior.

## Technical Stack
- Language: Java 17 (Eclipse Adoptium JDK 17.0.18.8)
- UI: Swing (AWT/Swing dialogs, timers, event-dispatch-thread driven updates)
- 3D Rendering: JOGL 2.6.0
  - GLJPanel path currently preferred for playback stability on Windows
  - GLCanvas path caused GraphicsConfiguration failures on this machine
- Build/Run: Gradle wrapper via PowerShell launch scripts
- OS target in current testing: Windows 11
- Main modules touched:
  - core
  - swing

## Current System Behavior
- Playback dialog renders a 3D rocket scene, trajectory panel, timeline slider, and HUD values.
- Playback uses sampled simulation telemetry and camera presets (Ground, Follow, Track, Downrange, Manual).
- Startup scripts attempt to kill existing OpenRocket Java processes before launching.

## Confirmed Challenges and Bugs

### 1) Motion stutter, hitching, and apparent ghosting/multi-rocket effect
Status: ✅ FIXED — removed position/pose exponential smoothing (was introducing 100ms+ lag), reduced camera tau to snap in ~3 ticks, added 50ms elapsed clamp to prevent EDT stall jumps.

### 2) Rocket exits frame in camera presets (Ground/Follow)
Status: ✅ FIXED — tightened Follow min distance to 10m, camera tau 0.018.

### 3) Playback dialog z-order/focus problems
Status: ✅ FIXED — owner set to SimulationConfigDialog, setAlwaysOnTop(true).

### 4) Multiple app instances from launch scripts
Status: ✅ FIXED — kill pattern broadened to match both OpenRocket and SwingStartup class names plus JAR path fragment.

### 5) JOGL GraphicsConfiguration exception on Windows
Status: ✅ FIXED — playback uses GLJPanel (FBO path) to avoid WGL crash.

### 6) Smoke trail ahead of rocket
Status: ✅ FIXED — trail sample count now excludes future samples; trail bridges from last history sample to current CG then nozzle position using body orientation transform.

### 7) Rocket body orientation wrong (appeared to travel sideways)
Status: ✅ FIXED — switched from velocity-derived azimuth (+PI offset) to orientationPhi/orientationTheta from simulation quaternion. Yaw formula corrected to phi + PI/2.

### 8) CG/CP red dot visible at pad base during playback
Status: ✅ FIXED — setDrawCarets(false) on playback RocketFigure3d instance.

### 9) Scene lacks context and scale
Status: ✅ IMPROVED — added fence around launch pad, 4 tree clusters, 3 more background buildings. Google Maps satellite background deferred to future phase.

## Future Enhancements
- Google Maps / satellite tile background for launch site context
- Smoke particle system (replace line trail with billboarded quads)
- Nose cone ejection + parachute deployment animation (event-driven MVP)
- Automatic video export of playback session

## Risks
- Continued iterative tuning without frame-time instrumentation can hide root cause.
- EDT saturation can reintroduce jitter even with improved formulas.
- Camera behavior can regress across presets if not validated with scenario baselines.

## Plan of Attack

### Phase 1: Stabilize framing and usability (short-term)
1. Add hard framing constraints in Ground and Follow modes.
   - Enforce minimum on-screen rocket bounds near t=0 to t=2s.
   - Clamp camera offsets and target lead by altitude/velocity bands.
2. Keep playback window reliably foregrounded from SimulationConfigDialog flow.
   - Re-verify owner chain and focus transfer after simulation run dialogs close.
3. Validate single-instance launch behavior with repeated script runs.
   - Run 5-10 launch cycles and confirm only one matching app process remains.

Exit criteria:
- Rocket visible at t=0 in Ground and Follow every run.
- Playback opens on top of edit dialog in normal workflow.
- No duplicate app windows after repeated launches.

### Phase 2: Motion smoothness and temporal consistency (short-term)
1. Decouple playback simulation time progression from slider UI updates.
   - Keep slider as display/control input, not driver of frame updates during play.
2. Add frame-time diagnostics in playback dialog.
   - Track dt mean/max and dropped-frame events.
3. Introduce adaptive interpolation for model and camera transforms.
   - Limit per-frame transform deltas to prevent jump discontinuities.
4. Reduce redundant repaint/display triggers across frame updates.

Exit criteria:
- No visible jump discontinuities in contiguous-frame captures.
- Subjective smoothness acceptable in Follow and Ground at 1.0x.

### Phase 3: Robustness and cleanup (mid-term)
1. Implement explicit JOGL fallback strategy (GLCanvas -> GLJPanel).
2. Add regression checklist for camera presets and z-order workflow.
3. Clean encoding issues in RocketPanel comments for reliable compiler output.

Exit criteria:
- Playback initializes reliably on Windows test machine.
- Clean compile output for touched modules.

## Validation Checklist (per build)
- Open simulation -> click 3D Playback.
- Verify playback dialog appears above simulation config.
- Ground at t=0 shows rocket in frame.
- Follow at 1.0x keeps rocket in frame through boost and descent.
- Capture 3 contiguous frames during motion and inspect jump magnitude.
- Relaunch script twice and confirm one active OpenRocket app process.

## Notes on Recent Changes
- Added process-kill logic to launch scripts and expanded command-line matching.
- Switched playback back to GLJPanel-preferred path after GLCanvas exception.
- Added playback state batching and slider-update guard to reduce redundant redraws.
- Applied camera and model smoothing adjustments; behavior still under tuning.
