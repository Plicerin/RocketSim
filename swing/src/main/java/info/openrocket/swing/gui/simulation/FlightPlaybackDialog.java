package info.openrocket.swing.gui.simulation;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.document.Simulation;
import info.openrocket.core.simulation.FlightData;
import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.simulation.FlightEvent;
import info.openrocket.core.util.MathUtil;
import info.openrocket.swing.gui.figure3d.RocketFigure3d;
import net.miginfocom.swing.MigLayout;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JSplitPane;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.List;

import javax.imageio.ImageIO;

/**
 * Standalone 3D flight playback dialog.
 *
 * Features: JOGL 3D rocket view with orientation-driven camera, 2D trajectory
 * side panel (altitude vs. downrange), interpolated telemetry, flight event
 * markers on a smooth 1000-step timeline, and multiple camera presets.
 */
public class FlightPlaybackDialog extends JDialog {
    private static final long serialVersionUID = 7715644563022674839L;
    private static final int  TICK_MS      = 16;
    private static final int  SLIDER_MAX   = 1000;
    private static final double[] SPEED_MULTIPLIERS = {0.25, 0.5, 1.0, 2.0, 4.0};

    private enum CameraView {
        GROUND("Ground"),
        FOLLOW("Follow"),
        TRACK("Track"),
        DOWNRANGE("Downrange"),
        MANUAL("Manual");

        private final String label;

        CameraView(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    // Flight data
    private final FlightDataBranch  branch;
    private final FlightPlaybackStateSampler stateSampler;
    private final List<Double>      timeValues;
    private final List<FlightEvent> events;
    private final double            totalTime;

    // Precomputed trajectory arrays (length == timeValues.size())
    private final double[] trajectoryX;
    private final double[] trajectoryY;
    private final double[] trajectoryXY;
    private final double[] trajectoryAlt;
    private final double   recoveryDeploymentTime;
    private final double   minAlt, maxAlt, minXY, maxXY;

    // UI
    private final RocketFigure3d  rocketFigure3d;
    private final TrajectoryPanel trajectoryPanel;
    private final TimelinePanel   timelinePanel;
    private final JButton         playPauseButton;
    private final JComboBox<CameraView> cameraViewSelector;
    private final JComboBox<String> speedSelector;
    private final JLabel          timeValueLabel;
    private final JLabel          altitudeValueLabel;
    private final JLabel          velocityValueLabel;
    private final JLabel          positionValueLabel;
    private final JLabel          eventValueLabel;

    // Playback state
    private final Timer         timer;
    private final DecimalFormat valueFormat = new DecimalFormat("0.00");
    private boolean playing              = false;
    private CameraView cameraView        = CameraView.FOLLOW;
    private double  playbackTimeSeconds  = 0.0;
    private long    lastTickNanos        = 0L;
    private boolean cameraPoseInitialized = false;
    private double cameraEyeX = 0.0;
    private double cameraEyeY = 0.0;
    private double cameraEyeZ = 0.0;
    private double cameraTargetX = 0.0;
    private double cameraTargetY = 0.0;
    private double cameraTargetZ = 0.0;
    private double lastCameraSampleTime = Double.NaN;
    private double stableForwardX = 1.0;
    private double stableForwardZ = 0.0;
    private boolean autoCaptureEnabled = false;
    private double nextCaptureTime = Double.POSITIVE_INFINITY;
    private int captureIndex = 0;
    private boolean captureFailureLogged = false;
    private boolean updatingTimelineSlider = false;

    private static final double CAPTURE_INTERVAL_SECONDS = 2.0;
    private static final String CAPTURE_DIR = "build/playback-captures";

    // ---- Constructor ----

    public FlightPlaybackDialog(Window parent, OpenRocketDocument document, Simulation simulation) {
        super(parent, "3D Flight Playback \u2014 " + simulation.getName(), ModalityType.MODELESS);

        FlightData data = simulation.getSimulatedData();
        if (data == null || data.getBranchCount() == 0) {
            throw new IllegalArgumentException("Simulation contains no data.");
        }

        this.branch     = data.getBranch(0);
        this.timeValues = branch.get(FlightDataType.TYPE_TIME);
        if (timeValues == null || timeValues.isEmpty()) {
            throw new IllegalArgumentException("Simulation contains no time samples.");
        }
        this.stateSampler = new FlightPlaybackStateSampler(branch);
        this.events    = branch.getEvents();
        this.totalTime = stateSampler.getTotalTime();

        // Precompute trajectory arrays
        List<Double> xList   = branch.get(FlightDataType.TYPE_POSITION_X);
        List<Double> yList   = branch.get(FlightDataType.TYPE_POSITION_Y);
        List<Double> xyList  = branch.get(FlightDataType.TYPE_POSITION_XY);
        List<Double> altList = branch.get(FlightDataType.TYPE_ALTITUDE);
        int n = timeValues.size();
        trajectoryX   = new double[n];
        trajectoryY   = new double[n];
        trajectoryXY  = new double[n];
        trajectoryAlt = new double[n];
        double tmpMinAlt = Double.MAX_VALUE, tmpMaxAlt = -Double.MAX_VALUE;
        double tmpMinXY  = Double.MAX_VALUE, tmpMaxXY  = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double posX = safeGet(xList,   i);
            double posY = safeGet(yList,   i);
            double alt = safeGet(altList, i);
            double xy  = safeGet(xyList,  i);
            trajectoryX[i] = posX;
            trajectoryY[i] = posY;
            trajectoryAlt[i] = alt;
            trajectoryXY[i]  = xy;
            if (alt < tmpMinAlt) tmpMinAlt = alt;
            if (alt > tmpMaxAlt) tmpMaxAlt = alt;
            if (xy  < tmpMinXY)  tmpMinXY  = xy;
            if (xy  > tmpMaxXY)  tmpMaxXY  = xy;
        }
        minAlt = tmpMinAlt;
        maxAlt = (tmpMaxAlt > tmpMinAlt) ? tmpMaxAlt : tmpMinAlt + 1.0;
        minXY  = tmpMinXY;
        maxXY  = (tmpMaxXY > tmpMinXY)   ? tmpMaxXY  : tmpMinXY  + 1.0;

        double deploymentTime = Double.POSITIVE_INFINITY;
        for (FlightEvent event : events) {
            if (event.getType() == FlightEvent.Type.RECOVERY_DEVICE_DEPLOYMENT) {
                deploymentTime = event.getTime();
                break;
            }
        }
        this.recoveryDeploymentTime = deploymentTime;

        // Create components
        this.rocketFigure3d    = new RocketFigure3d(document, true);
        this.rocketFigure3d.setType(RocketFigure3d.TYPE_FINISHED);
        this.rocketFigure3d.setSceneViewEnabled(true);
        this.rocketFigure3d.setCustomBackgroundColor(new Color(170, 205, 235));
        this.rocketFigure3d.setDrawCarets(false);
        this.rocketFigure3d.setFlightTrail(trajectoryX, trajectoryAlt, trajectoryY);
        this.rocketFigure3d.setVisibleTrailSamples(1);
        this.trajectoryPanel   = new TrajectoryPanel();
        this.timelinePanel     = new TimelinePanel();
        this.playPauseButton   = new JButton("Play");
        this.cameraViewSelector = new JComboBox<>(CameraView.values());
        this.cameraViewSelector.setSelectedItem(CameraView.FOLLOW);
        this.speedSelector     = new JComboBox<>(new String[]{"0.25x", "0.5x", "1.0x", "2.0x", "4.0x"});
        this.speedSelector.setSelectedItem("1.0x");
        this.timeValueLabel     = new JLabel("-");
        this.altitudeValueLabel = new JLabel("-");
        this.velocityValueLabel = new JLabel("-");
        this.positionValueLabel = new JLabel("-");
        this.eventValueLabel    = new JLabel("-");
        this.timer = new Timer(TICK_MS, e -> onPlaybackTick());

        buildUi();
        wireEvents();
        updateFromTime(0.0);

        setPreferredSize(new Dimension(1200, 720));
        pack();
        setLocationRelativeTo(parent);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
    }

    // ---- UI Construction ----

    private void buildUi() {
        JSplitPane viewSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, rocketFigure3d, trajectoryPanel);
        viewSplit.setResizeWeight(0.72);
        viewSplit.setDividerSize(4);

        JPanel controls = new JPanel(new MigLayout("fillx, insets 6", "[][][grow][]", "[][][]"));
        controls.add(playPauseButton,    "cell 0 0");
        controls.add(cameraViewSelector, "cell 1 0");
        controls.add(speedSelector,      "cell 3 0");
        controls.add(timelinePanel,      "cell 0 1 4 1, growx, h 52::");

        JPanel hud = new JPanel(new MigLayout("fillx, insets 0", "[][grow][10:][grow][10:][grow]", "[][]"));
        hud.add(new JLabel("Time:"),     "cell 0 0, alignx right");
        hud.add(timeValueLabel,          "cell 1 0, growx");
        hud.add(new JLabel("Altitude:"), "cell 2 0, alignx right");
        hud.add(altitudeValueLabel,      "cell 3 0, growx");
        hud.add(new JLabel("Speed:"),    "cell 4 0, alignx right");
        hud.add(velocityValueLabel,      "cell 5 0, growx");
        hud.add(new JLabel("Position:"), "cell 0 1, alignx right");
        hud.add(positionValueLabel,      "cell 1 1, growx");
        hud.add(new JLabel("Event:"),    "cell 2 1, alignx right");
        hud.add(eventValueLabel,         "cell 3 1 3 1, growx");
        controls.add(hud, "cell 0 2 4 1, growx");

        JPanel root = new JPanel(new BorderLayout(4, 4));
        root.add(viewSplit,  BorderLayout.CENTER);
        root.add(controls,   BorderLayout.SOUTH);

        setLayout(new BorderLayout());
        add(root, BorderLayout.CENTER);
    }

    // ---- Event Wiring ----

    private void wireEvents() {
        playPauseButton.addActionListener(e -> {
            if (playing) pausePlayback();
            else         startPlayback();
        });

        cameraViewSelector.addActionListener(e -> {
            CameraView selection = (CameraView) cameraViewSelector.getSelectedItem();
            cameraView = selection != null ? selection : CameraView.FOLLOW;
            cameraPoseInitialized = false;
            lastCameraSampleTime = Double.NaN;
            updateFromTime(playbackTimeSeconds);
        });

        timelinePanel.slider.addChangeListener(e -> {
            if (updatingTimelineSlider) {
                return;
            }
            double t = sliderToTime(timelinePanel.slider.getValue());
            playbackTimeSeconds = t;
            updateFromTime(t);
        });
    }

    // ---- Playback Control ----

    private void startPlayback() {
        if (playbackTimeSeconds >= totalTime) {
            playbackTimeSeconds = 0.0;
            updatingTimelineSlider = true;
            timelinePanel.slider.setValue(0);
            updatingTimelineSlider = false;
            updateFromTime(0.0);
        }
        playing = true;
        playPauseButton.setText("Pause");
        lastTickNanos = System.nanoTime();
        timer.start();
    }

    private void pausePlayback() {
        playing = false;
        playPauseButton.setText("Play");
        timer.stop();
    }

    private void onPlaybackTick() {
        if (!playing) return;
        long now = System.nanoTime();
        double elapsed = (now - lastTickNanos) / 1_000_000_000.0;
        elapsed = Math.min(elapsed, 0.050); // clamp: prevent jump after EDT stall or GC pause
        lastTickNanos = now;

        double speed = SPEED_MULTIPLIERS[speedSelector.getSelectedIndex()];
        playbackTimeSeconds += elapsed * speed;

        if (playbackTimeSeconds >= totalTime) {
            playbackTimeSeconds = totalTime;
            updatingTimelineSlider = true;
            timelinePanel.slider.setValue(SLIDER_MAX);
            updatingTimelineSlider = false;
            updateFromTime(totalTime);
            pausePlayback();
            return;
        }
        updatingTimelineSlider = true;
        timelinePanel.slider.setValue(timeToSlider(playbackTimeSeconds));
        updatingTimelineSlider = false;
        updateFromTime(playbackTimeSeconds);
    }

    // ---- Slider <> Time Conversion ----

    private int timeToSlider(double time) {
        if (totalTime <= 0) return 0;
        return (int) Math.round(Math.min(1.0, Math.max(0.0, time / totalTime)) * SLIDER_MAX);
    }

    private double sliderToTime(int val) {
        return val * totalTime / SLIDER_MAX;
    }

    // ---- Interpolation ----

    /** Interpolates an already-extracted primitive array at the given time. */
    private double interpolatedArr(double[] arr, double time) {
        return stateSampler.interpolate(arr, time);
    }

    // ---- State Update ----

    private void updateFromTime(double time) {
        FlightPlaybackStateSampler.State state = stateSampler.sample(time);

        // Use the sampler's linear interpolation directly — no extra smoothing lag.
        double worldX = Double.isNaN(state.positionX) ? 0.0 : state.positionX;
        double worldY = Double.isNaN(state.altitude) ? 0.08 : Math.max(0.08, state.altitude);
        double worldZ = Double.isNaN(state.positionY) ? 0.0 : state.positionY;

        // Rocket body orientation from simulation quaternion (orientationPhi = nose azimuth 0=north,
        // orientationTheta = nose elevation PI/2=straight-up).
        // The model +X axis is nose→tail. After the GL rotation sequence (yaw around Y, pitch around Z):
        //   nozzle offset = halfSpan * (-cos(theta)*sin(phi), -sin(theta), -cos(theta)*cos(phi))
        // which exactly equals the tail direction in world space — verified analytically.
        //   rocketYaw   = phi + PI/2
        //   rocketPitch = -theta
        // Fallback to velocity-derived angles when orientation data is absent.
        final double rocketYaw;
        double rocketPitch;
        if (!Double.isNaN(state.orientationPhi) && !Double.isNaN(state.orientationTheta)) {
            rocketYaw   = state.orientationPhi + Math.PI / 2.0;
            rocketPitch = -state.orientationTheta;
        } else {
            rocketYaw   = Double.isNaN(state.travelAzimuth)  ? 0.0 : (state.travelAzimuth  + Math.PI / 2.0);
            rocketPitch = Double.isNaN(state.flightPathAngle) ? 0.0 : (-state.flightPathAngle);
        }
        // Use samples strictly before current time so the history trail never
        // extends past the current interpolated rocket position. getDataIndexOfTime
        // returns the first index where t[i] >= time; we exclude that sample (+1 is removed).
        int trailSamples = branch.getDataIndexOfTime(time);
        if (trailSamples < 0) {
            trailSamples = timeValues.size();
        }
        // trailSamples is now the count of samples that are all at t <= current time.
        boolean recoveryVisible = time >= recoveryDeploymentTime;

        if (recoveryVisible && state.velocityZ < -0.5) {
            // During chute descent keep the body in a believable hanging attitude.
            double descentBlend = MathUtil.clamp((-state.velocityZ) / 12.0, 0.0, 1.0);
            rocketPitch = MathUtil.interpolate(rocketPitch, Math.toRadians(-68.0), descentBlend);
        }

        boolean useLookAt = applyCameraView(state, worldX, worldY, worldZ);
        rocketFigure3d.setPlaybackFrame(
            worldX, worldY, worldZ,
            worldX, worldY, worldZ,
            rocketYaw, rocketPitch, 0.0,
            trailSamples,
            recoveryVisible, 0.45, recoveryVisible ? 0.18 : 0.0,
            useLookAt,
            cameraEyeX, cameraEyeY, cameraEyeZ,
            cameraTargetX, cameraTargetY, cameraTargetZ
        );

        timeValueLabel    .setText(formatValue(state.time)          + " s");
        altitudeValueLabel.setText(formatValue(state.altitude)      + " m");
        velocityValueLabel.setText(formatValue(state.velocityTotal) + " m/s");
        positionValueLabel.setText(formatValue(state.positionXY)    + " m downrange");

        // Most recent flight event up to current time
        String evText = "\u2014";
        for (FlightEvent ev : events) {
            if (ev.getTime() <= time) evText = ev.getType().toString();
            else break;
        }
        eventValueLabel.setText(evText);

        trajectoryPanel.setCurrentTime(time);
        maybeCaptureFrame(time);
    }

    private boolean applyCameraView(FlightPlaybackStateSampler.State state, double worldX, double worldY, double worldZ) {
        if (cameraView == CameraView.MANUAL) {
            return false;
        }

        double rocketAzimuth = state.orientationPhi;
        double rocketZenith = state.orientationTheta;
        double travelAzimuth = state.travelAzimuth;
        if (Double.isNaN(travelAzimuth)) {
            travelAzimuth = rocketAzimuth;
        }

        double forwardX = Math.sin(travelAzimuth);
        double forwardZ = Math.cos(travelAzimuth);
        double forwardLen = Math.hypot(forwardX, forwardZ);
        boolean reliableHeading = !Double.isNaN(forwardX) && !Double.isNaN(forwardZ)
                && forwardLen > 1.0e-6 && state.velocityXY > 1.5;
        if (reliableHeading) {
            stableForwardX = forwardX / forwardLen;
            stableForwardZ = forwardZ / forwardLen;
        }
        forwardX = stableForwardX;
        forwardZ = stableForwardZ;
        if (Double.isNaN(forwardX) || Double.isNaN(forwardZ)) {
            forwardX = 1.0;
            forwardZ = 0.0;
            stableForwardX = forwardX;
            stableForwardZ = forwardZ;
        }
        double sideX = -forwardZ;
        double sideZ = forwardX;

        double distance = MathUtil.clamp(10.0 + worldY * 0.055, 10.0, 65.0);
        double eyeX;
        double eyeY;
        double eyeZ;
        double targetX = worldX;
        double targetY = worldY + 0.8;
        double targetZ = worldZ;

        switch (cameraView) {
            case GROUND:
                // Keep launch centered and clearly visible from a fixed observer near pad level.
                double nearDistance = MathUtil.clamp(12.0 + worldY * 0.02, 12.0, 18.0);
                eyeX = worldX - forwardX * (nearDistance * 0.95) + sideX * (nearDistance * 0.70);
                eyeY = Math.max(2.8, worldY * 0.14 + 2.2);
                eyeZ = worldZ - forwardZ * (nearDistance * 0.95) + sideZ * (nearDistance * 0.70);
                targetX = worldX;
                targetY = worldY + 0.9;
                targetZ = worldZ;
                break;
            case TRACK:
                eyeX = worldX - forwardX * (distance * 0.35) + sideX * (distance * 0.90);
                eyeY = worldY + 4.0;
                eyeZ = worldZ - forwardZ * (distance * 0.35) + sideZ * (distance * 0.90);
                targetY = worldY + 1.0;
                break;
            case DOWNRANGE:
                eyeX = worldX + forwardX * (distance * 1.25) + sideX * (distance * 0.18);
                eyeY = worldY + 6.0;
                eyeZ = worldZ + forwardZ * (distance * 1.25) + sideZ * (distance * 0.18);
                targetY = worldY + 1.6;
                break;
            case FOLLOW:
            default:
                double lead = MathUtil.clamp(state.velocityXY * 0.10, 0.0, 6.0);
                targetX = worldX + forwardX * lead;
                targetY = worldY + Math.max(1.2, Math.min(2.6, state.velocityTotal * 0.006));
                targetZ = worldZ + forwardZ * lead;
                eyeX = targetX - forwardX * (distance * 0.88) + sideX * (distance * 0.10);
                eyeY = targetY + Math.max(3.2, distance * 0.12);
                eyeZ = targetZ - forwardZ * (distance * 0.88) + sideZ * (distance * 0.10);
                break;
        }

        setSmoothedCameraLookAt(state.time, eyeX, eyeY, eyeZ, targetX, targetY, targetZ);
        return true;
    }

    private void setSmoothedCameraLookAt(double time,
                                         double desiredEyeX,
                                         double desiredEyeY,
                                         double desiredEyeZ,
                                         double desiredTargetX,
                                         double desiredTargetY,
                                         double desiredTargetZ) {
        if (!cameraPoseInitialized) {
            cameraEyeX = desiredEyeX;
            cameraEyeY = desiredEyeY;
            cameraEyeZ = desiredEyeZ;
            cameraTargetX = desiredTargetX;
            cameraTargetY = desiredTargetY;
            cameraTargetZ = desiredTargetZ;
            cameraPoseInitialized = true;
            lastCameraSampleTime = time;
            return;
        }

        double dt = Double.isNaN(lastCameraSampleTime) ? 0.0 : Math.max(0.0, time - lastCameraSampleTime);
        lastCameraSampleTime = time;

        // Tight tau so camera snaps to desired position in ~2-3 ticks (30-50ms), not 460ms.
        double tau = (cameraView == CameraView.FOLLOW) ? 0.018 : 0.025;
        double maxAlpha = (cameraView == CameraView.FOLLOW) ? 0.95 : 0.92;
        double alpha = computeSmoothingAlpha(dt, tau, maxAlpha);

        cameraEyeX = MathUtil.interpolate(cameraEyeX, desiredEyeX, alpha);
        cameraEyeY = MathUtil.interpolate(cameraEyeY, desiredEyeY, alpha);
        cameraEyeZ = MathUtil.interpolate(cameraEyeZ, desiredEyeZ, alpha);
        cameraTargetX = MathUtil.interpolate(cameraTargetX, desiredTargetX, alpha);
        cameraTargetY = MathUtil.interpolate(cameraTargetY, desiredTargetY, alpha);
        cameraTargetZ = MathUtil.interpolate(cameraTargetZ, desiredTargetZ, alpha);

    }

    private double computeSmoothingAlpha(double dt, double tau, double maxAlpha) {
        if (dt <= 0.0) {
            return 0.0;
        }
        double alpha = 1.0 - Math.exp(-dt / tau);
        return MathUtil.clamp(alpha, 0.0, maxAlpha);
    }

    public void setAutoCaptureEnabled(boolean enabled) {
        autoCaptureEnabled = enabled;
        captureIndex = 0;
        if (!enabled) {
            nextCaptureTime = Double.POSITIVE_INFINITY;
            return;
        }
        nextCaptureTime = playbackTimeSeconds;
        maybeCaptureFrame(playbackTimeSeconds);
        nextCaptureTime = playbackTimeSeconds + CAPTURE_INTERVAL_SECONDS;
    }

    public void startAutoPlayback() {
        if (!playing) {
            startPlayback();
        }
    }

    private void maybeCaptureFrame(double time) {
        if (!autoCaptureEnabled || time + 1.0e-6 < nextCaptureTime) {
            return;
        }

        captureCurrentFrame(time);
        nextCaptureTime += CAPTURE_INTERVAL_SECONDS;
    }

    private void captureCurrentFrame(double time) {
        try {
            int width = Math.max(1, rocketFigure3d.getWidth());
            int height = Math.max(1, rocketFigure3d.getHeight());

            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = image.createGraphics();
            rocketFigure3d.paint(g2);
            g2.dispose();

            File dir = new File(CAPTURE_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                return;
            }

            File out = new File(dir, String.format("frame-%03d-t%06.2f.png", captureIndex++, time));
            boolean wrote = ImageIO.write(image, "png", out);
            if (!wrote && !captureFailureLogged) {
                captureFailureLogged = true;
                System.out.println("PLAYBACK_CAPTURE_WARNING PNG writer unavailable.");
            }
        } catch (IOException ignore) {
            if (!captureFailureLogged) {
                captureFailureLogged = true;
                System.out.println("PLAYBACK_CAPTURE_WARNING Unable to write capture frames.");
            }
        } catch (RuntimeException ignore) {
            if (!captureFailureLogged) {
                captureFailureLogged = true;
                System.out.println("PLAYBACK_CAPTURE_WARNING Unable to render capture frames.");
            }
        }
    }

    private double groundViewYaw(double travelAzimuth) {
        if (Double.isNaN(travelAzimuth)) {
            return Math.toRadians(120.0);
        }
        return MathUtil.reduce2Pi(travelAzimuth + Math.toRadians(30.0));
    }

    private double groundViewPitch() {
        return Math.toRadians(16.0);
    }

    private double sideViewYaw(double travelAzimuth) {
        if (Double.isNaN(travelAzimuth)) {
            return Math.PI / 2.0;
        }
        return MathUtil.reduce2Pi(travelAzimuth + Math.PI / 2.0);
    }

    private double downrangeViewYaw(double travelAzimuth) {
        if (Double.isNaN(travelAzimuth)) {
            return Math.PI / 2.0;
        }
        return MathUtil.reduce2Pi(travelAzimuth);
    }

    private double flightPathAngle(FlightPlaybackStateSampler.State state) {
        if (Double.isNaN(state.flightPathAngle)) {
            return 0.0;
        }
        return state.flightPathAngle;
    }

    private String formatValue(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) return "-";
        return valueFormat.format(value);
    }

    // ---- Utility ----

    private static double safeGet(List<Double> list, int i) {
        if (list == null || i >= list.size()) return 0.0;
        Double v = list.get(i);
        return (v == null) ? 0.0 : v;
    }

    private Color eventColor(FlightEvent.Type type) {
        switch (type) {
            case LAUNCH: case LIFTOFF: case LAUNCHROD: case IGNITION:
            case EJECTION_CHARGE:      return new Color(50, 200, 50);
            case BURNOUT:              return new Color(255, 140, 0);
            case APOGEE:               return new Color(220, 30,  30);
            case RECOVERY_DEVICE_DEPLOYMENT: return new Color(60, 120, 230);
            case STAGE_SEPARATION:     return new Color(180, 50, 180);
            case GROUND_HIT:           return new Color(140, 90,  40);
            default:                   return Color.GRAY;
        }
    }

    // ---- Inner Class: TrajectoryPanel ----

    class TrajectoryPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private double currentTime = 0.0;

        TrajectoryPanel() {
            setBackground(new Color(18, 18, 28));
            setPreferredSize(new Dimension(320, 400));
        }

        void setCurrentTime(double t) {
            this.currentTime = t;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            int padLeft = 42, padRight = 10, padTop = 22, padBottom = 18;
            int pw = w - padLeft - padRight;
            int ph = h - padTop  - padBottom;
            if (pw < 10 || ph < 10) return;

            double altRange = maxAlt - minAlt;
            double xyRange  = maxXY  - minXY;

            // Background grid
            g2.setColor(new Color(40, 44, 60));
            for (int tick = 0; tick <= 4; tick++) {
                int y = padTop + tick * ph / 4;
                g2.drawLine(padLeft, y, padLeft + pw, y);
            }

            // Axis box
            g2.setColor(new Color(90, 95, 120));
            g2.drawRect(padLeft, padTop, pw, ph);

            // Axis labels
            g2.setColor(new Color(170, 175, 210));
            g2.setFont(g2.getFont().deriveFont(9.5f));
            g2.drawString(String.format("%.0f m", maxAlt), 2, padTop + 10);
            g2.drawString(String.format("%.0f",   minAlt), 2, padTop + ph);
            // Rotated Y-axis label
            Graphics2D g2r = (Graphics2D) g2.create();
            g2r.rotate(-Math.PI / 2, 9, padTop + ph / 2);
            g2r.setColor(new Color(140, 145, 180));
            g2r.setFont(g2r.getFont().deriveFont(9f));
            g2r.drawString("Altitude (m)", 9 - 30, padTop + ph / 2 + 4);
            g2r.dispose();

            // Title
            g2.setColor(new Color(200, 205, 230));
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 10.5f));
            g2.drawString("Trajectory \u2014 Downrange vs Altitude", padLeft + 2, padTop - 6);

            // Trajectory path
            int n = trajectoryXY.length;
            if (n > 1) {
                int[] xs = new int[n];
                int[] ys = new int[n];
                for (int i = 0; i < n; i++) {
                    xs[i] = padLeft + (int) ((trajectoryXY[i]  - minXY)  / xyRange  * pw);
                    ys[i] = padTop  + (int) ((1.0 - (trajectoryAlt[i] - minAlt) / altRange) * ph);
                }
                g2.setColor(new Color(70, 130, 200, 160));
                g2.setStroke(new BasicStroke(1.6f));
                for (int i = 1; i < n; i++) {
                    g2.drawLine(xs[i - 1], ys[i - 1], xs[i], ys[i]);
                }
            }

            // Event dots on trajectory
            for (FlightEvent ev : events) {
                double t = ev.getTime();
                if (t < 0 || t > totalTime) continue;
                double alt = interpolatedArr(trajectoryAlt, t);
                double xy  = interpolatedArr(trajectoryXY,  t);
                int ex = padLeft + (int) ((xy  - minXY)  / xyRange  * pw);
                int ey = padTop  + (int) ((1.0 - (alt - minAlt) / altRange) * ph);
                g2.setColor(eventColor(ev.getType()));
                g2.fillOval(ex - 4, ey - 4, 8, 8);
            }

            // Current position marker
            double curAlt = interpolatedArr(trajectoryAlt, currentTime);
            double curXY  = interpolatedArr(trajectoryXY,  currentTime);
            int cx = padLeft + (int) ((curXY  - minXY)  / xyRange  * pw);
            int cy = padTop  + (int) ((1.0 - (curAlt - minAlt) / altRange) * ph);
            g2.setColor(new Color(255, 230, 60));
            g2.setStroke(new BasicStroke(2.2f));
            g2.drawOval(cx - 6, cy - 6, 12, 12);
            g2.setColor(Color.WHITE);
            g2.fillOval(cx - 4, cy - 4, 8, 8);
        }
    }

    // ---- Inner Class: TimelinePanel (slider + event markers) ----

    class TimelinePanel extends JPanel {
        private static final long serialVersionUID = 1L;
        final JSlider slider;

        TimelinePanel() {
            setLayout(new BorderLayout(0, 0));
            setOpaque(false);
            slider = new JSlider(0, SLIDER_MAX, 0);
            slider.setOpaque(false);
            add(slider, BorderLayout.CENTER);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (totalTime <= 0 || events.isEmpty()) return;

            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Approximate slider track extents (Swing default inset is ~11px each side)
            int trackLeft  = 11;
            int trackRight = getWidth() - 11;
            int trackWidth = trackRight - trackLeft;
            int markerBase = getHeight() - 4;

            for (FlightEvent ev : events) {
                double t = ev.getTime();
                if (t < 0 || t > totalTime) continue;
                int x = trackLeft + (int) (t / totalTime * trackWidth);
                Color c = eventColor(ev.getType());
                g2.setColor(c);
                // Downward-pointing triangle
                int[] px = {x - 4, x + 4, x};
                int[] py = {markerBase - 9, markerBase - 9, markerBase};
                g2.fillPolygon(px, py, 3);
                g2.setColor(c.darker());
                g2.drawPolygon(px, py, 3);
            }
        }
    }
}