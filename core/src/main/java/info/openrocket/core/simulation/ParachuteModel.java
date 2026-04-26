package info.openrocket.core.simulation;

import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.RecoveryDevice;
import info.openrocket.core.util.Coordinate;
import info.openrocket.core.util.CoordinateIF;
import info.openrocket.core.util.Quaternion;

/**
 * Simulation-side parachute model that tracks inflation progress and a simple
 * shock-cord spring/damper that biases rocket attitude toward a hanging orientation.
 */
public class ParachuteModel {
	private final RecoveryDevice device;
	private final double targetArea;
	private final double cd;
	private final double inflationTime;
	private double inflationProgress = 0.0; // seconds

	// Shock-cord parameters (simple spring-damper rotational model)
	private final double shockCordLength;
	private final double shockStiffness; // spring coefficient (rad/s per rad)
	private final double shockDamping;   // damping coefficient

	public ParachuteModel(RecoveryDevice device, double deploymentVz) {
		this.device = device;
		this.targetArea = device.getArea();
		this.cd = device.getCD();

		// Compute inflation time based on parachute diameter and deployment velocity.
		double base = 0.25;
		double diameterFactor = 0.0;
		if (device instanceof Parachute) {
			Parachute p = (Parachute) device;
			diameterFactor = Math.max(0.0, p.getDiameter());
		}
		// Faster deployments (higher vz magnitude) cause quicker inflation up to a point,
		// larger chutes take slightly longer to inflate.
		double vzFactor = Math.min(3.0, Math.abs(deploymentVz));
		this.inflationTime = Math.max(0.2, base + diameterFactor * 0.6 + vzFactor * 0.02);

		// Shock cord defaults: use parachute line length as an approximation for cord length
		double defaultLine = (device instanceof Parachute) ? ((Parachute) device).getLineLength() : 0.3;
		this.shockCordLength = Math.max(0.05, defaultLine);

		this.shockStiffness = 6.0 / Math.max(0.1, shockCordLength); // tune: shorter cords stiffer
		this.shockDamping = 2.8; // tuned damping
	}

	/**
	 * Advance the model by dt seconds (updates inflation progress)
	 */
	public void update(double dt) {
		if (inflationProgress < inflationTime) {
			inflationProgress += dt;
			if (inflationProgress > inflationTime)
				inflationProgress = inflationTime;
		}
	}

	/**
	 * Simple shock-cord rotational dynamics that biases the rocket's attitude to hang
	 * under the parachute. This applies small angular acceleration (via modifying
	 * rotation velocity) and integrates the quaternion orientation.
	 */
	public void applyShockCordDynamics(SimulationStatus status, double dt) {
		if (dt <= 0) return;

		Quaternion q = status.getRocketOrientationQuaternion();
		// Body-space nose direction is (-1,0,0) (model +X is nose->tail)
		CoordinateIF bodyNose = new Coordinate(-1, 0, 0);
		CoordinateIF worldNose = q.rotate(bodyNose);

		// Desired nose direction when hanging: downwards (0,0,-1)
		CoordinateIF desired = new Coordinate(0, 0, -1);

		// Compute rotation from worldNose -> desired: axis = cross(nose, desired), angle = acos(dot)
		double dot = Math.max(-1.0, Math.min(1.0, worldNose.normalize().dot(desired.normalize())));
		double angle = Math.acos(dot);
		if (angle < 1e-6) {
			// already aligned; apply damping to rotation velocity
			CoordinateIF rot = status.getRocketRotationVelocity();
			rot = rot.multiply(Math.exp(-shockDamping * dt));
			status.setRocketRotationVelocity(rot);
			return;
		}

		CoordinateIF axis = new Coordinate(
			worldNose.getY() * desired.getZ() - worldNose.getZ() * desired.getY(),
			worldNose.getZ() * desired.getX() - worldNose.getX() * desired.getZ(),
			worldNose.getX() * desired.getY() - worldNose.getY() * desired.getX());

		// Prevent zero-axis
		double axisLen = axis.length();
		if (axisLen < 1e-6) {
			return;
		}
		axis = axis.multiply(1.0 / axisLen);

		// Simple proportional moment toward alignment
		double gain = shockStiffness * getInflationFactor();
		CoordinateIF angularAccel = axis.multiply(gain * angle);

		// Integrate into rotation velocity (rad/s) and damp
		CoordinateIF rotVel = status.getRocketRotationVelocity();
		rotVel = rotVel.add(angularAccel.multiply(dt));
		rotVel = rotVel.multiply(Math.exp(-shockDamping * dt));
		status.setRocketRotationVelocity(rotVel);

		// Integrate orientation using small-angle approximation via quaternion rotation
		CoordinateIF deltaRotation = rotVel.multiply(dt);
		Quaternion deltaQ = Quaternion.rotation(deltaRotation);
		Quaternion newQ = deltaQ.multiplyRight(q).normalizeIfNecessary();
		status.setRocketOrientationQuaternion(newQ);
	}

	/**
	 * Returns inflation factor in range [0,1]. 0 = packed; 1 = fully inflated.
	 */
	public double getInflationFactor() {
		if (inflationTime <= 0) return 1.0;
		return Math.max(0.0, Math.min(1.0, inflationProgress / inflationTime));
	}

	public double getTargetArea() {
		return targetArea;
	}

	public double getCd() {
		return cd;
	}
}
