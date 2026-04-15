package info.openrocket.core.document;

import info.openrocket.core.l10n.Translator;
import info.openrocket.core.motor.Motor;
import info.openrocket.core.motor.MotorConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.FlightConfigurationId;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.AxialStage;
import info.openrocket.core.rocketcomponent.BodyTube;
import info.openrocket.core.rocketcomponent.InnerTube;
import info.openrocket.core.rocketcomponent.LaunchLug;
import info.openrocket.core.rocketcomponent.NoseCone;
import info.openrocket.core.rocketcomponent.Parachute;
import info.openrocket.core.rocketcomponent.ShockCord;
import info.openrocket.core.rocketcomponent.Transition;
import info.openrocket.core.rocketcomponent.TrapezoidFinSet;
import info.openrocket.core.rocketcomponent.position.AxialMethod;
import info.openrocket.core.startup.Application;

import java.util.List;

public class OpenRocketDocumentFactory {

	private static final Translator trans = Application.getTranslator();

	public static OpenRocketDocument createNewRocket() {
		Rocket rocket = new Rocket();
		rocket.setName("Simple Sustainer");

		AxialStage stage = new AxialStage();
		//// Sustainer
		stage.setName(trans.get("BasicFrame.StageName.Sustainer"));
		rocket.addChild(stage);

		NoseCone noseCone = new NoseCone(Transition.Shape.OGIVE, 0.07, 0.012);
		noseCone.setName("Nose Cone");
		noseCone.setAftShoulderLength(0.02);
		noseCone.setAftShoulderRadius(0.011);
		stage.addChild(noseCone);

		BodyTube bodyTube = new BodyTube(0.20, 0.012, 0.0003);
		bodyTube.setName("Body Tube");
		stage.addChild(bodyTube);

		TrapezoidFinSet finSet = new TrapezoidFinSet(3, 0.05, 0.03, 0.02, 0.05);
		finSet.setName("3 Fin Set");
		finSet.setThickness(0.0032);
		finSet.setAxialMethod(AxialMethod.BOTTOM);
		bodyTube.addChild(finSet);

		LaunchLug launchLug = new LaunchLug();
		launchLug.setName("Launch Lug");
		launchLug.setAxialMethod(AxialMethod.TOP);
		launchLug.setAxialOffset(0.111);
		launchLug.setLength(0.050);
		launchLug.setOuterRadius(0.0022);
		launchLug.setInnerRadius(0.0020);
		bodyTube.addChild(launchLug);

		InnerTube motorMount = new InnerTube();
		motorMount.setName("Motor Mount Tube");
		motorMount.setAxialMethod(AxialMethod.TOP);
		motorMount.setAxialOffset(0.133);
		motorMount.setLength(0.07);
		motorMount.setOuterRadius(0.009);
		motorMount.setThickness(0.0003);
		motorMount.setMotorMount(true);
		bodyTube.addChild(motorMount);

		Parachute parachute = new Parachute();
		parachute.setName("Parachute");
		parachute.setAxialMethod(AxialMethod.TOP);
		parachute.setAxialOffset(0.030);
		parachute.setDiameter(0.30);
		parachute.setLineCount(6);
		parachute.setLineLength(0.30);
		bodyTube.addChild(parachute);

		ShockCord shockCord = new ShockCord();
		shockCord.setName("Shock Cord");
		shockCord.setAxialMethod(AxialMethod.TOP);
		shockCord.setAxialOffset(0.055);
		shockCord.setCordLength(0.40);
		bodyTube.addChild(shockCord);

		// Create a named flight configuration and wire up an Estes C6-5 if it's in the DB
		FlightConfigurationId fcid = new FlightConfigurationId();
		FlightConfiguration flightConfig = rocket.createFlightConfiguration(fcid);
		flightConfig.setName("Estes C6-5");
		rocket.setSelectedConfiguration(fcid);

		List<? extends Motor> motors = Application.getMotorSetDatabase()
				.findMotors(null, null, "Estes", "C6-5", 0.018, Double.NaN);
		if (motors.isEmpty()) {
			motors = Application.getMotorSetDatabase()
					.findMotors(null, null, "Estes", "C6", 0.018, Double.NaN);
		}
		if (!motors.isEmpty()) {
			MotorConfiguration motorConfig = new MotorConfiguration(motorMount, fcid);
			motorConfig.setMotor(motors.get(0));
			motorConfig.setEjectionDelay(5.0);
			motorMount.setMotorConfig(motorConfig, fcid);
		}

		rocket.getSelectedConfiguration().setAllStages();
		OpenRocketDocument doc = new OpenRocketDocument(rocket);
		Simulation simulation = new Simulation(doc, rocket);
		simulation.setName(doc.getNextSimulationName());
		simulation.setFlightConfigurationId(fcid);
		doc.addSimulation(simulation);
		doc.setSaved(true);
		return doc;
	}

	public static OpenRocketDocument createDocumentFromRocket(Rocket r) {
		OpenRocketDocument doc = new OpenRocketDocument(r);
		return doc;
	}

	public static OpenRocketDocument createEmptyRocket() {
		Rocket rocket = new Rocket();
		OpenRocketDocument doc = new OpenRocketDocument(rocket);
		return doc;
	}

}
