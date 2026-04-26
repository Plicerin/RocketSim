# Playback Handover

Date: 2026-04-25
Repo: `C:\Users\vrock\Documents\RocketSim\openrocket`
Scope: 3D flight playback only. Do not spend time on the rest of the OpenRocket app.

## Run Commands

Compile swing:

```powershell
.\gradlew.bat :swing:compileJava
```

**Correct** launch playback-only viewer:

```powershell
.\gradlew.bat run --args="--playback-only"
```

**Note**: The `--playback-only` flag is handled by `SwingStartup.java`. The full app runs with just `./gradlew run`.

## Current Status

### ✅ Fixed: Build
- Gradle wrapper JAR was corrupted (directory instead of JAR) - fixed by downloading fresh
- `RocketFigure3d.java` had garbled smoke methods - reconstructed

### ✅ Fixed: Smoke Trail (Resubmitted)
- Changed from **cloud/cylinder** to **thin wispy trail**
- Radius: 0.12 → 0.025 (80% smaller)
- Puff count: 28 → 16 (fewer, more spaced)
- Alpha: 0.18→0.05 → 0.10→0.02 (more transparent)
- Removed jitter - smoke now follows trajectory exactly
- Single layer per puff (removed overlapping cloud effect)

### ✅ Fixed: Parachute Z-Order
- Parachute now attaches to **nose** (rotated position) instead of body center
- Uses `getNoseWorldPosition()` which accounts for pitch/yaw
- **NEW**: Added `gl.glEnable(GL.GL_DEPTH_TEST)` and `gl.glDepthMask(true)` in `drawRecoverySystem()`
  - Parachute now renders behind solid rocket geometry (z-order correct)
  - Previously showed through body because depth test was disabled in transparent pass

### ❌ REGRESSIONS Introduced

#### Red canopy is GONE
- The red/white striped canopy rendering was removed during smoke reconstruction
- Need to re-add: `drawRecoverySystem()` had `gl.glColor3f(0.96f, 0.96f, 0.98f)` (white)
- Was previously using `gl.glColor3f(0.85f, 0.15f, 0.15f)` for red sections

#### Trajectory line ALWAYS ON
- `tracerVisible` field added but not being set/cleared properly
- `setTracerVisible()` added but dialog may not be calling it
- Need to verify `FlightPlaybackDialog` is syncing tracer state

## Files Touched

- `swing/src/main/java/info/openrocket/swing/gui/simulation/FlightPlaybackDialog.java`
- `swing/src/main/java/info/openrocket/swing/gui/figure3d/RocketFigure3d.java`
- `swing/src/main/java/info/openrocket/swing/gui/figure3d/RocketRenderer.java`

## Known Issues (Carried Forward)

### Camera
- Some preset cameras still fail to lock onto rocket at `t=0`
- Ground camera may go underground around 3-second mark
- Follow/downrange/ground startup framing needs normalization

### Recovery
- Canopy shape is still umbrella-like, not fully rounded
- Recovery rig ownership split across ad-hoc calculations
- Nose/body/cord relationship may need refinement

### Mouse Control (From Original Plan)
- Pitch/yaw manipulation via mouse drag not implemented
- Mouse handling exists but doesn't actually rotate rocket

## Memory Notes

- **CRITICAL**: Files use CRLF (Windows) line endings - check before editing
- **CRITICAL**: Use `span().getX()` not `xMax - xMin` for BoundingBox
- **CRITICAL**: Avoid sed for multiline Java replacements
- BoundingBox API: Use `span().getX()`, `span().getY()`, `span().getZ()`

## Recommended Next Steps

1. **Fix red canopy regression** - restore striped red/white rendering
2. **Fix trajectory line** - ensure `tracerVisible` syncs with UI checkbox
3. **Verify smoke** - confirm thin trail looks correct
4. **Add mouse pitch/yaw control** - drag to rotate rocket
5. **Camera fixes** - normalize startup anchor for all presets

## Regression Checklist (Before Next Handover)

- [ ] Red/white striped canopy visible during recovery
- [ ] Trajectory line toggle works in UI
- [ ] Smoke is thin trail, not cylinder
- [ ] Parachute attaches to nose, not intersecting body
