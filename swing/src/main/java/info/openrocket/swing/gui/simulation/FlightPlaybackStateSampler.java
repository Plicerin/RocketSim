package info.openrocket.swing.gui.simulation;

import info.openrocket.core.simulation.FlightDataBranch;
import info.openrocket.core.simulation.FlightDataType;
import info.openrocket.core.util.MathUtil;

import java.util.List;

/**
 * Samples replay state from a {@link FlightDataBranch} at arbitrary playback times.
 * The sampler caches channel arrays once and provides interpolated state snapshots.
 */
final class FlightPlaybackStateSampler {

    static final class State {
        final double time;
        final double altitude;
        final double velocityTotal;
        final double velocityXY;
        final double velocityZ;
        final double positionX;
        final double positionY;
        final double positionXY;
        final double travelAzimuth;
        final double orientationPhi;
        final double orientationTheta;
        final double accelerationTotal;
        final double mass;
        final double cgLocation;
        final double cpLocation;
        final double thrustForce;
        final double dragForce;
        final double flightPathAngle;

        State(
                final double time,
                final double altitude,
                final double velocityTotal,
                final double velocityXY,
                final double velocityZ,
                final double positionX,
                final double positionY,
                final double positionXY,
                final double travelAzimuth,
                final double orientationPhi,
                final double orientationTheta,
                final double accelerationTotal,
                final double mass,
                final double cgLocation,
                final double cpLocation,
                final double thrustForce,
                final double dragForce,
                final double flightPathAngle) {
            this.time = time;
            this.altitude = altitude;
            this.velocityTotal = velocityTotal;
            this.velocityXY = velocityXY;
            this.velocityZ = velocityZ;
            this.positionX = positionX;
            this.positionY = positionY;
            this.positionXY = positionXY;
            this.travelAzimuth = travelAzimuth;
            this.orientationPhi = orientationPhi;
            this.orientationTheta = orientationTheta;
            this.accelerationTotal = accelerationTotal;
            this.mass = mass;
            this.cgLocation = cgLocation;
            this.cpLocation = cpLocation;
            this.thrustForce = thrustForce;
            this.dragForce = dragForce;
            this.flightPathAngle = flightPathAngle;
        }
    }

    private final double[] timeValues;

    private final double[] altitudeValues;
    private final double[] velocityTotalValues;
    private final double[] velocityXYValues;
    private final double[] velocityZValues;
    private final double[] positionXValues;
    private final double[] positionYValues;
    private final double[] positionXYValues;
    private final double[] travelAzimuthValues;
    private final double[] orientationPhiValues;
    private final double[] orientationThetaValues;
    private final double[] accelerationTotalValues;
    private final double[] massValues;
    private final double[] cgLocationValues;
    private final double[] cpLocationValues;
    private final double[] thrustForceValues;
    private final double[] dragForceValues;

    FlightPlaybackStateSampler(final FlightDataBranch branch) {
        List<Double> times = branch.get(FlightDataType.TYPE_TIME);
        if (times == null || times.isEmpty()) {
            throw new IllegalArgumentException("Simulation contains no time samples.");
        }

        this.timeValues = toArray(times, times.size());
        int n = this.timeValues.length;

        this.altitudeValues = toArray(branch.get(FlightDataType.TYPE_ALTITUDE), n);
        this.velocityTotalValues = toArray(branch.get(FlightDataType.TYPE_VELOCITY_TOTAL), n);
        this.velocityXYValues = toArray(branch.get(FlightDataType.TYPE_VELOCITY_XY), n);
        this.velocityZValues = toArray(branch.get(FlightDataType.TYPE_VELOCITY_Z), n);
        this.positionXValues = toArray(branch.get(FlightDataType.TYPE_POSITION_X), n);
        this.positionYValues = toArray(branch.get(FlightDataType.TYPE_POSITION_Y), n);
        this.positionXYValues = toArray(branch.get(FlightDataType.TYPE_POSITION_XY), n);
        this.travelAzimuthValues = toArray(branch.get(FlightDataType.TYPE_POSITION_DIRECTION), n);
        this.orientationPhiValues = toArray(branch.get(FlightDataType.TYPE_ORIENTATION_PHI), n);
        this.orientationThetaValues = toArray(branch.get(FlightDataType.TYPE_ORIENTATION_THETA), n);
        this.accelerationTotalValues = toArray(branch.get(FlightDataType.TYPE_ACCELERATION_TOTAL), n);
        this.massValues = toArray(branch.get(FlightDataType.TYPE_MASS), n);
        this.cgLocationValues = toArray(branch.get(FlightDataType.TYPE_CG_LOCATION), n);
        this.cpLocationValues = toArray(branch.get(FlightDataType.TYPE_CP_LOCATION), n);
        this.thrustForceValues = toArray(branch.get(FlightDataType.TYPE_THRUST_FORCE), n);
        this.dragForceValues = toArray(branch.get(FlightDataType.TYPE_DRAG_FORCE), n);
    }

    double getTotalTime() {
        return timeValues[timeValues.length - 1];
    }

    State sample(final double time) {
        double velocityXY = interpolate(velocityXYValues, time);
        double velocityZ = interpolate(velocityZValues, time);
        double flightPathAngle = Double.NaN;
        if (!Double.isNaN(velocityXY) && !Double.isNaN(velocityZ)) {
            flightPathAngle = MathUtil.clamp(Math.atan2(velocityZ, velocityXY),
                    Math.toRadians(-80.0), Math.toRadians(80.0));
        }

        return new State(
                time,
                interpolate(altitudeValues, time),
                interpolate(velocityTotalValues, time),
                velocityXY,
                velocityZ,
                interpolate(positionXValues, time),
                interpolate(positionYValues, time),
                interpolate(positionXYValues, time),
                interpolateAngle(travelAzimuthValues, time),
                interpolateAngle(orientationPhiValues, time),
                interpolateAngle(orientationThetaValues, time),
                interpolate(accelerationTotalValues, time),
                interpolate(massValues, time),
                interpolate(cgLocationValues, time),
                interpolate(cpLocationValues, time),
                interpolate(thrustForceValues, time),
                interpolate(dragForceValues, time),
                flightPathAngle);
    }

    double interpolate(final double[] values, final double time) {
        if (values.length == 0) {
            return Double.NaN;
        }

        Bracket bracket = bracket(time);
        if (bracket.high < 0) {
            return values[Math.min(values.length - 1, timeValues.length - 1)];
        }
        if (bracket.high == 0) {
            return values[0];
        }

        double v0 = valueAt(values, bracket.low);
        double v1 = valueAt(values, bracket.high);
        if (Double.isNaN(v0) || Double.isNaN(v1)) {
            return Double.NaN;
        }
        return v0 + bracket.alpha * (v1 - v0);
    }

    double interpolateAngle(final double[] values, final double time) {
        if (values.length == 0) {
            return Double.NaN;
        }

        Bracket bracket = bracket(time);
        if (bracket.high < 0) {
            return values[Math.min(values.length - 1, timeValues.length - 1)];
        }
        if (bracket.high == 0) {
            return values[0];
        }

        double v0 = valueAt(values, bracket.low);
        double v1 = valueAt(values, bracket.high);
        if (Double.isNaN(v0) || Double.isNaN(v1)) {
            return Double.NaN;
        }

        double delta = MathUtil.reducePi(v1 - v0);
        return MathUtil.reduce2Pi(v0 + bracket.alpha * delta);
    }

    private Bracket bracket(final double time) {
        if (Double.isNaN(time)) {
            return Bracket.END;
        }
        if (time <= timeValues[0]) {
            return new Bracket(0, 0, 0.0);
        }

        int hi = upperBound(time);
        if (hi < 0) {
            return Bracket.END;
        }

        int lo = hi - 1;
        double t0 = timeValues[lo];
        double t1 = timeValues[hi];
        double dt = t1 - t0;
        if (dt <= 0.0) {
            return new Bracket(lo, hi, 1.0);
        }
        double alpha = MathUtil.clamp((time - t0) / dt, 0.0, 1.0);
        return new Bracket(lo, hi, alpha);
    }

    private int upperBound(final double time) {
        int lo = 0;
        int hi = timeValues.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (timeValues[mid] >= time) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return (lo >= timeValues.length) ? -1 : lo;
    }

    private static double valueAt(final double[] arr, final int index) {
        if (index < 0 || index >= arr.length) {
            return Double.NaN;
        }
        return arr[index];
    }

    private static double[] toArray(final List<Double> values, final int targetSize) {
        int size = Math.max(0, targetSize);
        double[] arr = new double[size];
        for (int i = 0; i < size; i++) {
            if (values == null || i >= values.size()) {
                arr[i] = Double.NaN;
                continue;
            }
            Double value = values.get(i);
            arr[i] = value == null ? Double.NaN : value;
        }
        return arr;
    }

    private static final class Bracket {
        static final Bracket END = new Bracket(-1, -1, 1.0);

        final int low;
        final int high;
        final double alpha;

        Bracket(final int low, final int high, final double alpha) {
            this.low = low;
            this.high = high;
            this.alpha = alpha;
        }
    }
}