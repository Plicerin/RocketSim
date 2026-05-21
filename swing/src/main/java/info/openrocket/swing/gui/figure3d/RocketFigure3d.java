package info.openrocket.swing.gui.figure3d;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.SplashScreen;
import java.awt.event.MouseEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL2;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLCapabilities;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.GLProfile;
import com.jogamp.opengl.GLRunnable;
import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.opengl.awt.GLJPanel;
import com.jogamp.opengl.fixedfunc.GLLightingFunc;
import com.jogamp.opengl.fixedfunc.GLMatrixFunc;
import com.jogamp.opengl.glu.GLU;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.event.MouseInputAdapter;

import info.openrocket.core.preferences.ApplicationPreferences;
import info.openrocket.core.util.Coordinate;
import info.openrocket.core.util.CoordinateIF;
import info.openrocket.swing.gui.util.GUIUtil;
import info.openrocket.swing.gui.theme.UITheme;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jogamp.opengl.util.awt.AWTGLReadBufferUtil;
import com.jogamp.opengl.util.awt.Overlay;

import info.openrocket.core.document.OpenRocketDocument;
import info.openrocket.core.rocketcomponent.FlightConfiguration;
import info.openrocket.core.rocketcomponent.Rocket;
import info.openrocket.core.rocketcomponent.RocketComponent;
import info.openrocket.core.startup.Application;
import info.openrocket.core.util.MathUtil;
import info.openrocket.core.util.BoundingBox;

import info.openrocket.swing.gui.figureelements.CGCaret;
import info.openrocket.swing.gui.figureelements.CPCaret;
import info.openrocket.swing.gui.figureelements.FigureElement;
import info.openrocket.swing.gui.main.Splash;

/*
 * @author Bill Kuker <bkuker@billkuker.com>
 */
public class RocketFigure3d extends JPanel implements GLEventListener {
	
	public static final int TYPE_FIGURE = 2;
	public static final int TYPE_UNFINISHED = 3;
	public static final int TYPE_FINISHED = 4;
	
	private static final long serialVersionUID = 1L;
	private static final Logger log = LoggerFactory.getLogger(RocketFigure3d.class);
	
	static {
		//this allows the GL canvas and things like the motor selection
		//drop down to z-order themselves.
		JPopupMenu.setDefaultLightWeightPopupEnabled(false);
	}
	
	private static final double fovY = 15.0;
	private static double fovX = Double.NaN;
	private static final int CARET_SIZE = 20;
	
	private final OpenRocketDocument document;
	private final Rocket rkt;
	private final boolean preferFboCanvas;
	private Component canvas;
	
	
	private Overlay extrasOverlay, caretOverlay;
	private BufferedImage cgCaretRaster, cpCaretRaster;
	private volatile boolean redrawExtras = true;
	private boolean drawCarets = true;
	
	private final ArrayList<FigureElement> relativeExtra = new ArrayList<>();
	private final ArrayList<FigureElement> absoluteExtra = new ArrayList<>();
	
	private double roll = 0;
	private double yaw = 0;
	private double viewDistanceScale = 1.0;
	private double modelYaw = 0;
	private double modelPitch = 0;
	private double modelRoll = 0;
	private double modelOffsetX = 0;
	private double modelOffsetY = 0;
	private double modelOffsetZ = 0;
	private double sceneTargetX = 0;
	private double sceneTargetY = 0;
	private double sceneTargetZ = 0;
	private boolean sceneViewEnabled = false;
	private boolean useCameraLookAt = false;
	private double cameraEyeX = 0;
	private double cameraEyeY = 0;
	private double cameraEyeZ = 0;
	private double cameraTargetX = 0;
	private double cameraTargetY = 0;
	private double cameraTargetZ = 0;
	private double[] trailX = new double[0];
	private double[] trailY = new double[0];
	private double[] trailZ = new double[0];
	private int visibleTrailSamples = 0;
	private boolean recoverySystemVisible = false;
	private double recoveryLineLength = 0.0;
	private double recoveryCanopyRadius = 0.0;
	
	Point pickPoint = null;
	MouseEvent pickEvent;
	
	float[] lightPosition = new float[] { 1, 4, 1, 0 };
	
	RocketRenderer rr = new FigureRenderer();

	private static Color backgroundColor;
	private Color customBackgroundColor = null;

	static {
		initColors();
	}

	public RocketFigure3d(final OpenRocketDocument document) {
		this(document, false);
	}

	public RocketFigure3d(final OpenRocketDocument document, final boolean preferFboCanvas) {
		this.document = document;
		this.rkt = document.getRocket();
		this.preferFboCanvas = preferFboCanvas;
		this.setLayout(new BorderLayout());
		
		//Only initialize GL if 3d is enabled.
		if (is3dEnabled()) {
			//Fixes a linux / X bug: Splash must be closed before GL Init
			SplashScreen splash = Splash.getSplashScreen();
			if (splash != null && splash.isVisible())
				splash.close();
			
			initGLCanvas();
		}
	}

	private static void initColors() {
		updateColors();
		UITheme.Theme.addUIThemeChangeListener(RocketFigure3d::updateColors);
	}

	public static void updateColors() {
		backgroundColor = UITheme.getColor(UITheme.Keys.BACKGROUND);
	}

	/**
	 * Get the current background color (custom or theme default).
	 * @return the background color
	 */
	private Color getBackgroundColor() {
		return customBackgroundColor != null ? customBackgroundColor : backgroundColor;
	}

	/**
	 * Set a custom background color for this 3D figure. If null, uses the theme default.
	 * @param color the custom background color, or null to use theme default
	 */
	public void setCustomBackgroundColor(Color color) {
		this.customBackgroundColor = color;
		if (canvas != null && canvas instanceof GLAutoDrawable) {
			((GLAutoDrawable) canvas).invoke(true, drawable -> {
				display(drawable);
				return false;
			});
		}
	}

	public void flushTextureCaches() {
		((GLAutoDrawable) canvas).invoke(true, new GLRunnable() {
			@Override
			public boolean run(GLAutoDrawable drawable) {
				rr.flushTextureCache(drawable);
				return false;
			}
		});
	}
	
	/**
	 * Return true if 3d view is enabled. This may be toggled by the user at
	 * launch time.
	 * @return
	 */
	public static boolean is3dEnabled() {
		//Allow disable by command line, if program won't even start
		if (System.getProperty("openrocket.3d.disable") != null)
			return false;
		//return by preference
		return Application.getPreferences().getBoolean(ApplicationPreferences.OPENGL_ENABLED, true);
	}
	
	private void initGLCanvas() {
		log.debug("Initializing RocketFigure3D OpenGL Canvas");
		try {
			log.debug("Setting up GL capabilities...");
			
			log.trace("GL - Getting Default Profile");
			final GLProfile glp = GLProfile.get(GLProfile.GL2);
			
			log.trace("GL - creating GLCapabilities");
			final GLCapabilities caps = new GLCapabilities(glp);
			
			if (Application.getPreferences().getBoolean(ApplicationPreferences.OPENGL_ENABLE_AA, true)) {
				log.trace("GL - setSampleBuffers");
				caps.setSampleBuffers(true);
				
				log.trace("GL - setNumSamples");
				caps.setNumSamples(6);
			} else {
				log.trace("GL - Not enabling AA by user pref");
			}
			
			if (preferFboCanvas || Application.getPreferences().getBoolean(ApplicationPreferences.OPENGL_USE_FBO, false)) {
				log.trace("GL - Creating GLJPanel");
				canvas = new GLJPanel(caps);
			} else {
				log.trace("GL - Creating GLCanvas");
				canvas = new GLCanvas(caps);
			}
			
			log.trace("GL - Registering as GLEventListener on canvas");
			((GLAutoDrawable) canvas).addGLEventListener(this);
			
			log.trace("GL - Adding canvas to this JPanel");
			this.add(canvas, BorderLayout.CENTER);
			
			log.trace("GL - Setting up mouse listeners");
			setupMouseListeners();
			
			log.trace("GL - Rasterizing Carets");
			rasterizeCarets();
			
		} catch (Throwable t) {
			log.error("An error occurred creating 3d View", t);
			canvas = null;
			this.add(new JLabel("Unable to load 3d Libraries: "
					+ t.getMessage()));
		}
	}
	
	/**
	 * Set up the standard rendering hints on the Graphics2D
	 */
	private static void setRenderingHints(Graphics2D g) {
		g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,
				RenderingHints.VALUE_STROKE_NORMALIZE);
		g.setRenderingHint(RenderingHints.KEY_RENDERING,
				RenderingHints.VALUE_RENDER_QUALITY);
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
	}
	
	/**
	 * Rasterize the carets into 2 buffered images that I can blit onto the
	 * 3d display every redraw without all of the caret shape rendering overhead
	 */
	private void rasterizeCarets() {
		Graphics2D g2d;
		
		//Rasterize a CG Caret
		cgCaretRaster = new BufferedImage(CARET_SIZE, CARET_SIZE, BufferedImage.TYPE_4BYTE_ABGR);
		g2d = cgCaretRaster.createGraphics();
		setRenderingHints(g2d);
		
		g2d.setBackground(new Color(0, 0, 0, 0));
		g2d.clearRect(0, 0, CARET_SIZE, CARET_SIZE);
		
		new CGCaret((double) CARET_SIZE / 2, (double) CARET_SIZE / 2).paint(g2d, 1.0);
		
		g2d.dispose();
		
		//Rasterize a CP Caret
		cpCaretRaster = new BufferedImage(CARET_SIZE, CARET_SIZE, BufferedImage.TYPE_4BYTE_ABGR);
		g2d = cpCaretRaster.createGraphics();
		setRenderingHints(g2d);
		
		g2d.setBackground(new Color(0, 0, 0, 0));
		g2d.clearRect(0, 0, CARET_SIZE, CARET_SIZE);
		
		new CPCaret((double) CARET_SIZE / 2, (double) CARET_SIZE / 2).paint(g2d, 1.0);
		
		g2d.dispose();
		
	}
	
	private void setupMouseListeners() {
		MouseInputAdapter a = new MouseInputAdapter() {
			int lastX;
			int lastY;
			MouseEvent pressEvent;
			
			@Override
			public void mousePressed(final MouseEvent e) {
				lastX = e.getX();
				lastY = e.getY();
				pressEvent = e;
			}
			
			@Override
			public void mouseClicked(final MouseEvent e) {
				// Store the click point in AWT (top-left origin) coordinates and convert to
				// OpenGL surface coordinates during rendering.  This is important on HiDPI
				// displays (notably macOS Retina), where component coordinates and the GL
				// drawable surface size can differ.
				pickPoint = e.getPoint();
				pickEvent = e;
				internalRepaint();
			}
			
			@Override
			public void mouseDragged(final MouseEvent e) {
				//You can get a drag without a press while a modal dialog is shown
				if (pressEvent == null)
					return;
				
				int dx = lastX - e.getX();
				int dy = lastY - e.getY();
				lastX = e.getX();
				lastY = e.getY();
				
				if (pressEvent.getButton() == MouseEvent.BUTTON1) {
					if (Math.abs(dx) > Math.abs(dy)) {
						setYaw(yaw - dx / 100.0);
					} else {
						if (yaw > Math.PI / 2.0 && yaw < 3.0 * Math.PI / 2.0) {
							dy = -dy;
						}
						setRoll(roll - dy / 100.0);
					}
				} else {
					lightPosition[0] -= 0.1f * dx;
					lightPosition[1] += 0.1f * dy;
					internalRepaint();
				}
			}
		};
		canvas.addMouseMotionListener(a);
		canvas.addMouseListener(a);
	}
	
	
	@Override
	public void display(final GLAutoDrawable drawable) {
		GL2 gl = drawable.getGL().getGL2();
		GLU glu = new GLU();

		Color bgColor = getBackgroundColor();
		gl.glClearColor(bgColor.getRed()/ 255.0f, bgColor.getGreen()/ 255.0f,
				bgColor.getBlue()/ 255.0f, bgColor.getAlpha()/ 255.0f);
		gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
		
		setupView(gl, glu);
		drawSceneEnvironment(gl);
		
		final FlightConfiguration configuration = rkt.getSelectedConfiguration();
		if (pickPoint != null) {
			gl.glPushMatrix();
			applyRocketTransform(gl);
			gl.glDisable(GL.GL_MULTISAMPLE);
			gl.glDisable(GLLightingFunc.GL_LIGHTING);
			
			final Point surfacePickPoint = toSurfacePickPoint(drawable, pickPoint);
			final RocketComponent picked = rr.pick(drawable, configuration,
					surfacePickPoint, pickEvent.isShiftDown() ? selection : null);
			if (csl != null) {
				final MouseEvent e = pickEvent;
				SwingUtilities.invokeLater(new Runnable() {
					@Override
					public void run() {
						if (picked == null) {
							csl.componentClicked(new RocketComponent[] {}, e);
						} else {
							csl.componentClicked(new RocketComponent[] { picked }, e);
						}
					}
				});
				
			}
			pickPoint = null;
			gl.glPopMatrix();

			gl.glClearColor(bgColor.getRed()/ 255.0f, bgColor.getGreen()/ 255.0f,
					bgColor.getBlue()/ 255.0f, bgColor.getAlpha()/ 255.0f);
			gl.glClear(GL.GL_COLOR_BUFFER_BIT | GL.GL_DEPTH_BUFFER_BIT);
			setupView(gl, glu);
			drawSceneEnvironment(gl);

			gl.glEnable(GL.GL_MULTISAMPLE);
			gl.glEnable(GLLightingFunc.GL_LIGHTING);

			updateFigure();
		}
		gl.glPushMatrix();
		applyRocketTransform(gl);
		rr.render(drawable, configuration, selection);
		gl.glPopMatrix();
		
		drawExtras(drawable, gl, glu);
		if (drawCarets) {
			drawCarets(drawable, gl, glu);
		}
		
		// GLJPanel with GLSL Flipper relies on this:
		gl.glFrontFace(GL.GL_CCW);
		
	}

	/**
	 * Convert an AWT component coordinate (origin at top-left, in Swing "user space")
	 * to an OpenGL drawable surface coordinate (origin at bottom-left, in pixel space).
	 *
	 * macOS HiDPI (Retina) is a common case where {@code canvas.getWidth()/getHeight()}
	 * differ from {@code drawable.getSurfaceWidth()/getSurfaceHeight()}.
	 */
	private Point toSurfacePickPoint(final GLAutoDrawable drawable, final Point awtPoint) {
		if (awtPoint == null || canvas == null) {
			return awtPoint;
		}

		final int componentWidth = canvas.getWidth();
		final int componentHeight = canvas.getHeight();
		final int surfaceWidth = drawable.getSurfaceWidth();
		final int surfaceHeight = drawable.getSurfaceHeight();

		if (componentWidth <= 0 || componentHeight <= 0 || surfaceWidth <= 0 || surfaceHeight <= 0) {
			return awtPoint;
		}

		final double scaleX = (double) surfaceWidth / (double) componentWidth;
		final double scaleY = (double) surfaceHeight / (double) componentHeight;

		final int x = (int) Math.floor((awtPoint.x + 0.5) * scaleX);
		final int yTop = (int) Math.floor((awtPoint.y + 0.5) * scaleY);
		final int y = surfaceHeight - 1 - yTop;

		return new Point(
				MathUtil.clamp(x, 0, surfaceWidth - 1),
				MathUtil.clamp(y, 0, surfaceHeight - 1));
	}
	
	/**
	 * Creates a Graphics2D object for the overlay. The resultant Graphics2D
	 * object has the rendering hints set and the transform to match the
	 * current GraphicsConfiguration this component is rendered on. This takes
	 * into account things such as DPI Scaling.
	 *
	 * @param overlay the overlay to use when creating the Graphics2D object.
	 * @return Graphics2D a Graphics2D object for the overlay
	 */
	private Graphics2D createOverlayGraphics(final Overlay overlay) {
		final Graphics2D og2d = overlay.createGraphics();
		setRenderingHints(og2d);
		GraphicsConfiguration gconf = getGraphicsConfiguration();
		if (gconf != null) {
			og2d.setTransform(gconf.getDefaultTransform());
		}
		return og2d;
	}
	
	private void drawCarets(final GLAutoDrawable drawable, final GL2 gl, final GLU glu) {
		final Graphics2D og2d = createOverlayGraphics(caretOverlay);
		
		og2d.setBackground(new Color(0, 0, 0, 0));
		og2d.clearRect(0, 0, drawable.getSurfaceWidth(), drawable.getSurfaceHeight());
		caretOverlay.markDirty(0, 0, drawable.getSurfaceWidth(), drawable.getSurfaceHeight());
		
		// The existing relative Extras don't really work right for 3d.
		CoordinateIF pCP = project(cp, gl, glu);
		CoordinateIF pCG = project(cg, gl, glu);
		
		final int d = CARET_SIZE / 2;
		double height = canvas.getHeight();
		
		/* Need to take the displayScaling into account. If the scaling is not
		 * taken into account here, the CG and CP carets are placed in the wrong
		 * location. I *think* that's because the Coordinates returned by project(...)
		 * are already appropriately scaled - but not 100% certain on that. The
		 * following does work though.
		 */
		double displayScale = getGraphicsConfiguration().getDefaultTransform().getScaleX();
		AffineTransform cgTransform = AffineTransform.getTranslateInstance(((pCG.getX() / displayScale) - d), height - ((pCG.getY() / displayScale) + d));
		AffineTransform cpTransform = AffineTransform.getTranslateInstance(((pCP.getX() / displayScale) - d), height - ((pCP.getY() / displayScale) + d));
		
		//z order the carets 
		if (pCG.getZ() < pCP.getZ()) {
			//Subtract half of the caret size, so they are centered ( The +/- d in each translate)
			//Flip the sense of the Y coordinate from GL to normal (Y+ up/down)
			og2d.drawRenderedImage(cpCaretRaster, cpTransform);
			og2d.drawRenderedImage(cgCaretRaster, cgTransform);
		} else {
			og2d.drawRenderedImage(cgCaretRaster, cgTransform);
			og2d.drawRenderedImage(cpCaretRaster, cpTransform);
		}
		og2d.dispose();
		
		gl.glEnable(GL.GL_BLEND);
		caretOverlay.drawAll();
		gl.glDisable(GL.GL_BLEND);
	}
	
	/**
	 * Draw the extras overlay to the gl canvas.
	 * Re-blits the overlay every frame. Only re-renders the overlay
	 * when needed.
	 */
	private void drawExtras(final GLAutoDrawable drawable, final GL2 gl, final GLU glu) {
		//Only re-render if needed
		//	redrawExtras: Some external change (new simulation data) means
		//		the data is out of date.
		//	extrasOverlay.contentsLost(): For some reason the buffer with this
		//		data is lost.
		if (redrawExtras || extrasOverlay.contentsLost()) {
			log.debug("Redrawing Overlay");
			
			final Graphics2D og2d = createOverlayGraphics(extrasOverlay);
			
			og2d.setBackground(new Color(0, 0, 0, 0));
			og2d.clearRect(0, 0, drawable.getSurfaceWidth(), drawable.getSurfaceHeight());
			extrasOverlay.markDirty(0, 0, drawable.getSurfaceWidth(), drawable.getSurfaceHeight());
			
			for (FigureElement e : relativeExtra) {
				e.paint(og2d, 1);
			}
			Rectangle rect = this.getVisibleRect();
			
			for (FigureElement e : absoluteExtra) {
				e.paint(og2d, 1.0, rect);
			}
			og2d.dispose();
			
			redrawExtras = false;
		}
		
		//Re-blit to gl canvas every time
		gl.glEnable(GL.GL_BLEND);
		extrasOverlay.drawAll();
		gl.glDisable(GL.GL_BLEND);
	}
	
	@Override
	public void dispose(final GLAutoDrawable drawable) {
		log.trace("GL - dispose() called");
		rr.dispose(drawable);
	}
	
	@Override
	public void init(final GLAutoDrawable drawable) {
		log.trace("GL - init()");
		
		final GL2 gl = drawable.getGL().getGL2();
		gl.glClearDepth(1.0f); // clear z-buffer to the farthest
		
		gl.glDepthFunc(GL.GL_LESS); // the type of depth test to do
		
		float amb = 0.5f;
		float dif = 1.0f;
		gl.glLightfv(GLLightingFunc.GL_LIGHT1, GLLightingFunc.GL_AMBIENT,
				new float[] { amb, amb, amb, 1 }, 0);
		gl.glLightfv(GLLightingFunc.GL_LIGHT1, GLLightingFunc.GL_DIFFUSE,
				new float[] { dif, dif, dif, 1 }, 0);
		gl.glLightfv(GLLightingFunc.GL_LIGHT1, GLLightingFunc.GL_SPECULAR,
				new float[] { dif, dif, dif, 1 }, 0);
		
		gl.glEnable(GLLightingFunc.GL_LIGHT1);
		gl.glEnable(GLLightingFunc.GL_LIGHTING);
		gl.glShadeModel(GLLightingFunc.GL_SMOOTH);
		
		gl.glEnable(GLLightingFunc.GL_NORMALIZE);
		
		rr.init(drawable);
		
		extrasOverlay = new Overlay(drawable);
		caretOverlay = new Overlay(drawable);
	}
	
	@Override
	public void reshape(final GLAutoDrawable drawable, final int x, final int y, final int w, final int h) {
		log.trace("GL - reshape()");
		final GL2 gl = drawable.getGL().getGL2();
		final GLU glu = new GLU();
		
		final double ratio = (double) w / (double) h;
		fovX = fovY * ratio;
		
		// Make sure to set the viewport size to cover the full size
		gl.glViewport(0, 0, w, h);
		gl.glMatrixMode(GLMatrixFunc.GL_PROJECTION);
		gl.glLoadIdentity();
		glu.gluPerspective(fovY, ratio, 0.12f, 2500.0f);
		gl.glMatrixMode(GLMatrixFunc.GL_MODELVIEW);
		
		redrawExtras = true;
	}
	
	private BoundingBox cachedBounds = null;
	
	/**
	 * Calculates the bounds for the current configuration
	 * 
	 * @return
	 */
	private BoundingBox calculateBounds() {
		if (cachedBounds == null) {
			final FlightConfiguration configuration = rkt.getSelectedConfiguration();
			cachedBounds = configuration.getBoundingBox();
		}
		return cachedBounds;
	}
	
	private void setupView(final GL2 gl, final GLU glu) {
		gl.glLoadIdentity();
		
		gl.glLightfv(GLLightingFunc.GL_LIGHT1, GLLightingFunc.GL_POSITION,
				lightPosition, 0);

		// Get the bounds
		final BoundingBox b = calculateBounds();
		
		// Calculate the distance needed to fit the bounds in both the X and Y
		// direction
		// Add 10% for space around it.
		final double maxR = Math.max( Math.hypot(b.min.getY(), b.min.getZ()),
				Math.hypot(b.max.getY(), b.max.getZ()));
		final double dX = (b.span().getX() * 1.2 / 2.0)
				/ Math.tan(Math.toRadians(fovX / 2.0));
		final double dY = (2*maxR * 1.2 / 2.0)
				/ Math.tan(Math.toRadians(fovY / 2.0));
		
		// Move back the greater of the 2 distances
		if (useCameraLookAt) {
			glu.gluLookAt(cameraEyeX, cameraEyeY, cameraEyeZ,
					cameraTargetX, cameraTargetY, cameraTargetZ,
					0, 1, 0);
		} else {
			glu.gluLookAt(0, 0, Math.max(dX, dY) * viewDistanceScale, 0, 0, 0, 0, 1, 0);
			gl.glRotated(yaw * (180.0 / Math.PI), 0, 1, 0);
			gl.glRotated(roll * (180.0 / Math.PI), 1, 0, 0);
			gl.glTranslated(-sceneTargetX, -sceneTargetY, -sceneTargetZ);
		}
		
		//Change to LEFT Handed coordinates
		gl.glScaled(1, 1, -1);
		gl.glFrontFace(GL.GL_CW);
		
		//Flip textures for LEFT handed coords
		gl.glMatrixMode(GL.GL_TEXTURE);
		gl.glLoadIdentity();
		gl.glScaled(-1, 1, 1);
		gl.glTranslated(-1, 0, 0);
		gl.glMatrixMode(GLMatrixFunc.GL_MODELVIEW);
	}

	private void applyRocketTransform(final GL2 gl) {
		final BoundingBox b = calculateBounds();

		gl.glTranslated(modelOffsetX, modelOffsetY, modelOffsetZ);
		gl.glRotated(modelYaw * (180.0 / Math.PI), 0, 1, 0);
		gl.glRotated(modelPitch * (180.0 / Math.PI), 0, 0, 1);
		gl.glRotated(modelRoll * (180.0 / Math.PI), 1, 0, 0);
		gl.glTranslated(-b.min.getX() - b.span().getX() / 2.0, 0, 0);
	}

	private void drawSceneEnvironment(final GL2 gl) {
		if (!sceneViewEnabled) {
			return;
		}

		gl.glPushAttrib(GL2.GL_ENABLE_BIT | GL2.GL_CURRENT_BIT | GL2.GL_LIGHTING_BIT | GL2.GL_LINE_BIT);
		try {
			gl.glDisable(GLLightingFunc.GL_LIGHTING);
			gl.glDisable(GL.GL_CULL_FACE);
			gl.glLineWidth(1.0f);

			drawGroundPlane(gl);
			drawLaunchPad(gl);
			drawBackgroundStructures(gl);
			drawFlightTrail(gl);
			drawRecoverySystem(gl);
		} finally {
			gl.glPopAttrib();
		}
	}

	private void drawFlightTrail(final GL2 gl) {
		if (visibleTrailSamples < 2 || trailX.length == 0) {
			return;
		}

		// Compute current nozzle world position so the trail connects to the nozzle,
		// not to the model's geometric center (which is at modelOffset and is ~span/2
		// ahead of the nozzle in the direction of travel).
		final BoundingBox tb = calculateBounds();
		final double halfSpan = (tb != null) ? tb.span().getX() / 2.0 : 0.0;
		// Nozzle is at (+halfSpan, 0, 0) in centred model space. Apply pitch then yaw.
		// R_pitch (around Z by modelPitch): (halfSpan, 0) -> (halfSpan*cos, halfSpan*sin)
		// R_yaw   (around Y by modelYaw):   x_yaw = x_p*cos(yaw), z_yaw = -x_p*sin(yaw)
		final double cosPitch = Math.cos(modelPitch);
		final double sinPitch = Math.sin(modelPitch);
		final double cosYaw   = Math.cos(modelYaw);
		final double sinYaw   = Math.sin(modelYaw);
		final double xPitch = halfSpan * cosPitch;
		final double yPitch = halfSpan * sinPitch;
		final double nozzleX = modelOffsetX + xPitch * cosYaw;
		final double nozzleY = modelOffsetY + yPitch;
		final double nozzleZ = modelOffsetZ - xPitch * sinYaw;

		gl.glColor3f(0.93f, 0.95f, 0.98f);
		gl.glLineWidth(3.5f);
		gl.glBegin(GL2.GL_LINE_STRIP);
		for (int i = 0; i < visibleTrailSamples && i < trailX.length; i++) {
			gl.glVertex3d(trailX[i], Math.max(0.05, trailY[i]), trailZ[i]);
		}
		// Bridge from last history sample to current interpolated CG position, then to nozzle.
		// This closes the gap between the last discrete sim sample and the current frame.
		gl.glVertex3d(modelOffsetX, modelOffsetY, modelOffsetZ);
		gl.glVertex3d(nozzleX, nozzleY, nozzleZ);
		gl.glEnd();
	}

	private void drawRecoverySystem(final GL2 gl) {
		if (!recoverySystemVisible || recoveryCanopyRadius <= 0.0 || recoveryLineLength <= 0.0) {
			return;
		}

		// Enable depth test so parachute renders behind solid rocket geometry
		gl.glEnable(GL.GL_DEPTH_TEST);
		gl.glDepthMask(true);

		final double R = recoveryCanopyRadius;
		final int gores = 8;
		final int latBands = 10;

		// Attachment point (nose)
		final double attachX = modelOffsetX;
		final double attachY = modelOffsetY;
		final double attachZ = modelOffsetZ;

		// Skirt ring (bottom of canopy)
		final double skirtY = attachY + recoveryLineLength;

		// --- Shroud lines (white) ---
		gl.glColor3f(0.96f, 0.96f, 0.98f);
		gl.glLineWidth(1.5f);
		gl.glBegin(GL2.GL_LINES);
		for (int i = 0; i < gores; i++) {
			double angle = (2.0 * Math.PI * i) / gores;
			gl.glVertex3d(attachX, attachY, attachZ);
			gl.glVertex3d(attachX + Math.cos(angle) * R, skirtY, attachZ + Math.sin(angle) * R);
		}
		gl.glEnd();

		// --- Hemisphere canopy with red/white striped gores ---
		// Disable face culling so both sides of canopy are visible
		gl.glPushAttrib(GL2.GL_ENABLE_BIT);
		gl.glDisable(GL2.GL_CULL_FACE);

		for (int gore = 0; gore < gores; gore++) {
			// Even gores: red, odd gores: white
			if (gore % 2 == 0) {
				gl.glColor3f(0.85f, 0.15f, 0.15f);
			} else {
				gl.glColor3f(0.96f, 0.96f, 0.98f);
			}

			double angle0 = (2.0 * Math.PI * gore) / gores;
			double angle1 = (2.0 * Math.PI * (gore + 1)) / gores;

			gl.glBegin(GL2.GL_TRIANGLE_STRIP);
			for (int lat = 0; lat <= latBands; lat++) {
				double phi = (Math.PI / 2.0) * lat / latBands;
				double cosPhi = Math.cos(phi);
				double sinPhi = Math.sin(phi);
				double y = skirtY + R * sinPhi;

				gl.glVertex3d(attachX + Math.cos(angle0) * R * cosPhi, y,
						attachZ + Math.sin(angle0) * R * cosPhi);
				gl.glVertex3d(attachX + Math.cos(angle1) * R * cosPhi, y,
						attachZ + Math.sin(angle1) * R * cosPhi);
			}
			gl.glEnd();
		}

		gl.glPopAttrib();
	}

	private void drawGroundPlane(final GL2 gl) {
		final float halfSize = 180.0f;
		gl.glColor3f(0.31f, 0.39f, 0.25f);
		gl.glBegin(GL2.GL_QUADS);
		gl.glVertex3d(-halfSize, 0.0, -halfSize);
		gl.glVertex3d(halfSize, 0.0, -halfSize);
		gl.glVertex3d(halfSize, 0.0, halfSize);
		gl.glVertex3d(-halfSize, 0.0, halfSize);
		gl.glEnd();

		gl.glColor3f(0.42f, 0.50f, 0.34f);
		gl.glBegin(GL2.GL_LINES);
		for (int i = -180; i <= 180; i += 10) {
			gl.glVertex3d(i, 0.02, -halfSize);
			gl.glVertex3d(i, 0.02, halfSize);
			gl.glVertex3d(-halfSize, 0.02, i);
			gl.glVertex3d(halfSize, 0.02, i);
		}
		gl.glEnd();
	}

	private void drawLaunchPad(final GL2 gl) {
		gl.glColor3f(0.25f, 0.25f, 0.28f);
		gl.glBegin(GL2.GL_QUADS);
		gl.glVertex3d(-0.9, 0.0, -0.9);
		gl.glVertex3d(0.9, 0.0, -0.9);
		gl.glVertex3d(0.9, 0.0, 0.9);
		gl.glVertex3d(-0.9, 0.0, 0.9);
		gl.glEnd();

		gl.glColor3f(0.72f, 0.72f, 0.74f);
		gl.glLineWidth(2.0f);
		gl.glBegin(GL2.GL_LINES);
		gl.glVertex3d(0.0, 0.0, 0.0);
		gl.glVertex3d(0.0, 2.0, 0.0);
		gl.glEnd();
	}

	private void drawBackgroundStructures(final GL2 gl) {
		// Buildings
		drawSimpleBlock(gl, 18.0, 0.0, -24.0, 7.0, 5.0, 6.0, 0.75f, 0.72f, 0.67f);
		drawSimpleBlock(gl, 30.0, 0.0, -28.0, 9.0, 7.0, 7.0, 0.73f, 0.78f, 0.84f);
		drawSimpleBlock(gl, -26.0, 0.0, -22.0, 8.0, 6.0, 7.0, 0.76f, 0.70f, 0.64f);
		drawSimpleBlock(gl, -40.0, 0.0, -35.0, 14.0, 10.0, 10.0, 0.68f, 0.71f, 0.76f);
		drawSimpleBlock(gl, 55.0, 0.0, -10.0, 12.0, 4.0, 10.0, 0.70f, 0.68f, 0.62f);
		drawSimpleBlock(gl, -55.0, 0.0, -40.0, 8.0, 3.5, 8.0, 0.72f, 0.74f, 0.68f);
		drawSimpleBlock(gl, 12.0, 0.0, 22.0, 6.0, 4.0, 5.0, 0.74f, 0.70f, 0.65f);
		// Safety fence around launch pad
		drawFence(gl);
		// Tree clusters
		drawTree(gl, -12.0, -8.0, 5.0);
		drawTree(gl, -14.0, -9.0, 4.5);
		drawTree(gl, -10.5, -7.5, 5.2);
		drawTree(gl, 14.0, 10.0, 5.0);
		drawTree(gl, 16.0, 11.0, 4.8);
		drawTree(gl, -35.0, -15.0, 6.0);
		drawTree(gl, -37.0, -14.0, 5.5);
		drawTree(gl, -33.0, -16.0, 5.8);
		drawTree(gl, 40.0, -18.0, 6.5);
		drawTree(gl, 42.0, -20.0, 5.5);
		drawTree(gl, 44.0, -17.0, 6.0);
		drawTree(gl, -50.0, 25.0, 7.0);
		drawTree(gl, -48.0, 26.0, 6.5);
		drawTree(gl, -52.0, 24.0, 6.8);
	}

	private void drawFence(final GL2 gl) {
		gl.glColor3f(0.82f, 0.80f, 0.73f);
		gl.glLineWidth(1.0f);
		gl.glBegin(GL2.GL_LINE_LOOP);
		gl.glVertex3d(-4.5, 0.0, -4.5);
		gl.glVertex3d( 4.5, 0.0, -4.5);
		gl.glVertex3d( 4.5, 0.0,  4.5);
		gl.glVertex3d(-4.5, 0.0,  4.5);
		gl.glEnd();
		gl.glBegin(GL2.GL_LINE_LOOP);
		gl.glVertex3d(-4.5, 1.2, -4.5);
		gl.glVertex3d( 4.5, 1.2, -4.5);
		gl.glVertex3d( 4.5, 1.2,  4.5);
		gl.glVertex3d(-4.5, 1.2,  4.5);
		gl.glEnd();
		gl.glBegin(GL2.GL_LINES);
		for (double p = -4.5; p <= 4.5; p += 1.5) {
			gl.glVertex3d(p, 0.0, -4.5); gl.glVertex3d(p, 1.2, -4.5);
			gl.glVertex3d(p, 0.0,  4.5); gl.glVertex3d(p, 1.2,  4.5);
			gl.glVertex3d(-4.5, 0.0, p); gl.glVertex3d(-4.5, 1.2, p);
			gl.glVertex3d( 4.5, 0.0, p); gl.glVertex3d( 4.5, 1.2, p);
		}
		gl.glEnd();
	}

	private void drawTree(final GL2 gl, final double cx, final double cz, final double height) {
		final double trunkH = height * 0.35;
		final double crownH = height * 0.65;
		final double crownR = height * 0.22;
		final int segs = 8;
		// Trunk
		gl.glColor3f(0.42f, 0.30f, 0.20f);
		gl.glBegin(GL2.GL_QUADS);
		for (int i = 0; i < segs; i++) {
			double a0 = Math.PI * 2.0 * i / segs;
			double a1 = Math.PI * 2.0 * (i + 1) / segs;
			double r = 0.12;
			gl.glVertex3d(cx + Math.cos(a0) * r, 0.0,   cz + Math.sin(a0) * r);
			gl.glVertex3d(cx + Math.cos(a1) * r, 0.0,   cz + Math.sin(a1) * r);
			gl.glVertex3d(cx + Math.cos(a1) * r, trunkH, cz + Math.sin(a1) * r);
			gl.glVertex3d(cx + Math.cos(a0) * r, trunkH, cz + Math.sin(a0) * r);
		}
		gl.glEnd();
		// Crown (cone)
		gl.glColor3f(0.23f, 0.42f, 0.20f);
		gl.glBegin(GL2.GL_TRIANGLES);
		for (int i = 0; i < segs; i++) {
			double a0 = Math.PI * 2.0 * i / segs;
			double a1 = Math.PI * 2.0 * (i + 1) / segs;
			gl.glVertex3d(cx, trunkH + crownH, cz);
			gl.glVertex3d(cx + Math.cos(a1) * crownR, trunkH, cz + Math.sin(a1) * crownR);
			gl.glVertex3d(cx + Math.cos(a0) * crownR, trunkH, cz + Math.sin(a0) * crownR);
		}
		gl.glEnd();
	}

	private void drawSimpleBlock(final GL2 gl, final double cx, final double cy, final double cz,
			final double sx, final double sy, final double sz,
			final float r, final float g, final float b) {
		final double x0 = cx - sx / 2.0;
		final double x1 = cx + sx / 2.0;
		final double y0 = cy;
		final double y1 = cy + sy;
		final double z0 = cz - sz / 2.0;
		final double z1 = cz + sz / 2.0;

		gl.glColor3f(r, g, b);
		gl.glBegin(GL2.GL_QUADS);
		gl.glVertex3d(x0, y0, z0);
		gl.glVertex3d(x1, y0, z0);
		gl.glVertex3d(x1, y1, z0);
		gl.glVertex3d(x0, y1, z0);

		gl.glVertex3d(x1, y0, z1);
		gl.glVertex3d(x0, y0, z1);
		gl.glVertex3d(x0, y1, z1);
		gl.glVertex3d(x1, y1, z1);

		gl.glVertex3d(x0, y0, z1);
		gl.glVertex3d(x0, y0, z0);
		gl.glVertex3d(x0, y1, z0);
		gl.glVertex3d(x0, y1, z1);

		gl.glVertex3d(x1, y0, z0);
		gl.glVertex3d(x1, y0, z1);
		gl.glVertex3d(x1, y1, z1);
		gl.glVertex3d(x1, y1, z0);

		gl.glVertex3d(x0, y1, z0);
		gl.glVertex3d(x1, y1, z0);
		gl.glVertex3d(x1, y1, z1);
		gl.glVertex3d(x0, y1, z1);
		gl.glEnd();
	}
	
	/**
	 * Call when the rocket has changed
	 */
	public void updateFigure() {
		log.debug("3D Figure Updated");
		cachedBounds = null;
		if (canvas != null) {
			((GLAutoDrawable) canvas).invoke(true, new GLRunnable() {
				@Override
				public boolean run(GLAutoDrawable drawable) {
					rr.updateFigure(drawable);
					return false;
				}
			});
		}
	}
	
	private void internalRepaint() {
		if (canvas != null) {
			((GLAutoDrawable) canvas).display();
		}
		super.repaint();
	}
	
	@Override
	public void repaint() {
		redrawExtras = true;
		internalRepaint();
	}
	
	private Set<RocketComponent> selection = new HashSet<>();
	
	public void setSelection(final RocketComponent[] selection) {
		this.selection.clear();
		if (selection != null) {
			this.selection.addAll(Arrays.asList(selection));
		}
		internalRepaint();
	}
	
	private void setRoll(final double rot) {
		if (MathUtil.equals(roll, rot))
			return;
		this.roll = MathUtil.reduce2Pi(rot);
		internalRepaint();
	}
	
	private void setYaw(final double rot) {
		if (MathUtil.equals(yaw, rot))
			return;
		this.yaw = MathUtil.reduce2Pi(rot);
		internalRepaint();
	}

	/**
	 * Set camera view angles programmatically.
	 *
	 * @param yawRadians yaw angle in radians
	 * @param rollRadians pitch angle in radians
	 */
	public void setViewAngles(final double yawRadians, final double rollRadians) {
		setViewState(yawRadians, rollRadians, viewDistanceScale);
	}

	/**
	 * Set camera view state programmatically.
	 *
	 * @param yawRadians yaw angle in radians
	 * @param rollRadians pitch angle in radians
	 * @param distanceScale scale factor applied to the default camera distance
	 */
	public void setViewState(final double yawRadians, final double rollRadians, final double distanceScale) {
		final double normalizedYaw = MathUtil.reduce2Pi(yawRadians);
		final double normalizedRoll = MathUtil.reduce2Pi(rollRadians);
		final double clampedDistanceScale = MathUtil.clamp(distanceScale, 0.35, 4.0);

		if (MathUtil.equals(yaw, normalizedYaw) &&
				MathUtil.equals(roll, normalizedRoll) &&
				MathUtil.equals(viewDistanceScale, clampedDistanceScale)) {
			return;
		}

		yaw = normalizedYaw;
		roll = normalizedRoll;
		viewDistanceScale = clampedDistanceScale;
		internalRepaint();
	}

	/**
	 * Set rocket model orientation programmatically for playback.
	 *
	 * @param yawRadians heading rotation around vertical axis
	 * @param pitchRadians elevation rotation of the rocket body
	 * @param rollRadians bank rotation around rocket longitudinal axis
	 */
	public void setModelPose(final double yawRadians, final double pitchRadians, final double rollRadians) {
		final double normalizedYaw = Double.isNaN(yawRadians) ? 0.0 : MathUtil.reduce2Pi(yawRadians);
		final double normalizedPitch = Double.isNaN(pitchRadians) ? 0.0 : MathUtil.reducePi(pitchRadians);
		final double normalizedRoll = Double.isNaN(rollRadians) ? 0.0 : MathUtil.reduce2Pi(rollRadians);

		if (MathUtil.equals(modelYaw, normalizedYaw) &&
				MathUtil.equals(modelPitch, normalizedPitch) &&
				MathUtil.equals(modelRoll, normalizedRoll)) {
			return;
		}

		modelYaw = normalizedYaw;
		modelPitch = normalizedPitch;
		modelRoll = normalizedRoll;
		internalRepaint();
	}

	public void setSceneViewEnabled(final boolean enabled) {
		if (sceneViewEnabled == enabled) {
			return;
		}
		sceneViewEnabled = enabled;
		internalRepaint();
	}

	public void setSceneCameraTarget(final double targetX, final double targetY, final double targetZ) {
		if (MathUtil.equals(sceneTargetX, targetX) &&
				MathUtil.equals(sceneTargetY, targetY) &&
				MathUtil.equals(sceneTargetZ, targetZ)) {
			return;
		}

		sceneTargetX = targetX;
		sceneTargetY = targetY;
		sceneTargetZ = targetZ;
		internalRepaint();
	}

	public void setModelPosition(final double x, final double y, final double z) {
		if (MathUtil.equals(modelOffsetX, x) &&
				MathUtil.equals(modelOffsetY, y) &&
				MathUtil.equals(modelOffsetZ, z)) {
			return;
		}

		modelOffsetX = x;
		modelOffsetY = y;
		modelOffsetZ = z;
		internalRepaint();
	}

	public void setCameraLookAt(final double eyeX, final double eyeY, final double eyeZ,
			final double targetX, final double targetY, final double targetZ) {
		if (useCameraLookAt &&
				MathUtil.equals(cameraEyeX, eyeX) &&
				MathUtil.equals(cameraEyeY, eyeY) &&
				MathUtil.equals(cameraEyeZ, eyeZ) &&
				MathUtil.equals(cameraTargetX, targetX) &&
				MathUtil.equals(cameraTargetY, targetY) &&
				MathUtil.equals(cameraTargetZ, targetZ)) {
			return;
		}

		useCameraLookAt = true;
		cameraEyeX = eyeX;
		cameraEyeY = eyeY;
		cameraEyeZ = eyeZ;
		cameraTargetX = targetX;
		cameraTargetY = targetY;
		cameraTargetZ = targetZ;
		internalRepaint();
	}

	public void clearCameraLookAt() {
		if (!useCameraLookAt) {
			return;
		}
		useCameraLookAt = false;
		internalRepaint();
	}

	public void setFlightTrail(final double[] xs, final double[] ys, final double[] zs) {
		trailX = xs != null ? xs.clone() : new double[0];
		trailY = ys != null ? ys.clone() : new double[0];
		trailZ = zs != null ? zs.clone() : new double[0];
		visibleTrailSamples = Math.min(trailX.length, Math.min(trailY.length, trailZ.length));
		internalRepaint();
	}

	public void setVisibleTrailSamples(final int count) {
		int clamped = Math.max(0, Math.min(count, Math.min(trailX.length, Math.min(trailY.length, trailZ.length))));
		if (visibleTrailSamples == clamped) {
			return;
		}
		visibleTrailSamples = clamped;
		internalRepaint();
	}

	public void setRecoverySystemState(final boolean visible, final double lineLength, final double canopyRadius) {
		if (recoverySystemVisible == visible &&
				MathUtil.equals(recoveryLineLength, lineLength) &&
				MathUtil.equals(recoveryCanopyRadius, canopyRadius)) {
			return;
		}

		recoverySystemVisible = visible;
		recoveryLineLength = lineLength;
		recoveryCanopyRadius = canopyRadius;
		internalRepaint();
	}

	/**
	 * Apply all playback-visible state with a single repaint to avoid stutter from
	 * multiple immediate GL redraws in one timer tick.
	 */
	public void setPlaybackFrame(final double sceneTargetX, final double sceneTargetY, final double sceneTargetZ,
					 final double modelX, final double modelY, final double modelZ,
					 final double yawRadians, final double pitchRadians, final double rollRadians,
					 final int trailSampleCount,
					 final boolean recoveryVisible, final double recoveryLineLength, final double recoveryCanopyRadius,
					 final boolean useLookAt,
					 final double eyeX, final double eyeY, final double eyeZ,
					 final double targetX, final double targetY, final double targetZ) {
		final double normalizedYaw = Double.isNaN(yawRadians) ? 0.0 : MathUtil.reduce2Pi(yawRadians);
		final double normalizedPitch = Double.isNaN(pitchRadians) ? 0.0 : MathUtil.reducePi(pitchRadians);
		final double normalizedRoll = Double.isNaN(rollRadians) ? 0.0 : MathUtil.reduce2Pi(rollRadians);
		final int clampedTrailSamples = Math.max(0,
				Math.min(trailSampleCount, Math.min(trailX.length, Math.min(trailY.length, trailZ.length))));

		boolean changed = false;

		if (!MathUtil.equals(this.sceneTargetX, sceneTargetX) ||
				!MathUtil.equals(this.sceneTargetY, sceneTargetY) ||
				!MathUtil.equals(this.sceneTargetZ, sceneTargetZ)) {
			this.sceneTargetX = sceneTargetX;
			this.sceneTargetY = sceneTargetY;
			this.sceneTargetZ = sceneTargetZ;
			changed = true;
		}

		if (!MathUtil.equals(this.modelOffsetX, modelX) ||
				!MathUtil.equals(this.modelOffsetY, modelY) ||
				!MathUtil.equals(this.modelOffsetZ, modelZ)) {
			this.modelOffsetX = modelX;
			this.modelOffsetY = modelY;
			this.modelOffsetZ = modelZ;
			changed = true;
		}

		if (!MathUtil.equals(this.modelYaw, normalizedYaw) ||
				!MathUtil.equals(this.modelPitch, normalizedPitch) ||
				!MathUtil.equals(this.modelRoll, normalizedRoll)) {
			this.modelYaw = normalizedYaw;
			this.modelPitch = normalizedPitch;
			this.modelRoll = normalizedRoll;
			changed = true;
		}

		if (this.visibleTrailSamples != clampedTrailSamples) {
			this.visibleTrailSamples = clampedTrailSamples;
			changed = true;
		}

		if (this.recoverySystemVisible != recoveryVisible ||
				!MathUtil.equals(this.recoveryLineLength, recoveryLineLength) ||
				!MathUtil.equals(this.recoveryCanopyRadius, recoveryCanopyRadius)) {
			this.recoverySystemVisible = recoveryVisible;
			this.recoveryLineLength = recoveryLineLength;
			this.recoveryCanopyRadius = recoveryCanopyRadius;
			changed = true;
		}

		if (this.useCameraLookAt != useLookAt) {
			this.useCameraLookAt = useLookAt;
			changed = true;
		}

		if (useLookAt) {
			if (!MathUtil.equals(this.cameraEyeX, eyeX) ||
					!MathUtil.equals(this.cameraEyeY, eyeY) ||
					!MathUtil.equals(this.cameraEyeZ, eyeZ) ||
					!MathUtil.equals(this.cameraTargetX, targetX) ||
					!MathUtil.equals(this.cameraTargetY, targetY) ||
					!MathUtil.equals(this.cameraTargetZ, targetZ)) {
				this.cameraEyeX = eyeX;
				this.cameraEyeY = eyeY;
				this.cameraEyeZ = eyeZ;
				this.cameraTargetX = targetX;
				this.cameraTargetY = targetY;
				this.cameraTargetZ = targetZ;
				changed = true;
			}
		}

		if (changed) {
			internalRepaint();
		}
	}
	
	// ///////////// Extra methods
	
	private CoordinateIF project(final CoordinateIF c, final GL2 gl, final GLU glu) {
		final double[] mvmatrix = new double[16];
		final double[] projmatrix = new double[16];
		final int[] viewport = new int[4];
		
		gl.glGetIntegerv(GL.GL_VIEWPORT, viewport, 0);
		gl.glGetDoublev(GLMatrixFunc.GL_MODELVIEW_MATRIX, mvmatrix, 0);
		gl.glGetDoublev(GLMatrixFunc.GL_PROJECTION_MATRIX, projmatrix, 0);
		
		final double out[] = new double[4];
		glu.gluProject(c.getX(), c.getY(), c.getZ(), mvmatrix, 0, projmatrix, 0, viewport, 0,
				out, 0);
		
		return new Coordinate(out[0], out[1], out[2]);
		
	}
	
	private CoordinateIF cp = new Coordinate(0, 0, 0);
	private CoordinateIF cg = new Coordinate(0, 0, 0);
	
	public void setCG(final CoordinateIF cg) {
		this.cg = cg;
		redrawExtras = true;
	}
	
	public void setCP(final CoordinateIF cp) {
		this.cp = cp;
		redrawExtras = true;
	}
	
	public void addRelativeExtra(final FigureElement p) {
		relativeExtra.add(p);
		redrawExtras = true;
	}
	
	public void removeRelativeExtra(final FigureElement p) {
		relativeExtra.remove(p);
		redrawExtras = true;
	}
	
	public void clearRelativeExtra() {
		relativeExtra.clear();
		redrawExtras = true;
	}
	
	public void addAbsoluteExtra(final FigureElement p) {
		absoluteExtra.add(p);
		redrawExtras = true;
	}
	
	public void removeAbsoluteExtra(final FigureElement p) {
		absoluteExtra.remove(p);
		redrawExtras = true;
	}
	
	public void clearAbsoluteExtra() {
		absoluteExtra.clear();
		redrawExtras = true;
	}
	
	private ComponentSelectionListener csl;
	
	public static interface ComponentSelectionListener {
		public void componentClicked(RocketComponent[] components, MouseEvent e);
	}
	
	public void addComponentSelectionListener(
			ComponentSelectionListener newListener) {
		this.csl = newListener;
	}
	
	public void setType(final int t) {
		//There is no canvas if there was an error while creating it.
		if (canvas == null)
			return;
		
		// The first time the user selects any 3d figure types,  the canvas' internal _drawable
		// has not been realized.  Unfortunately, there is a test in canvas.invoke which doesn't
		// execute the runnable if the drawable isn't realized.
		// In order to trump this, we test if the canvas has not been realized and initialize
		// the renderer accordingly.  There is certainly a better way to do this.
		
		
		final RocketRenderer newRR = switch (t) {
			case TYPE_FINISHED -> new RealisticRenderer(document);
			case TYPE_UNFINISHED -> new UnfinishedRenderer(document);
			default -> new FigureRenderer();
		};

		if (canvas instanceof GLCanvas && !((GLCanvas) canvas).isRealized()) {
			rr = newRR;
		} else if (canvas instanceof GLJPanel && !((GLJPanel) canvas).isRealized()) {
			rr = newRR;
		} else {
			((GLAutoDrawable) canvas).invoke(true, new GLRunnable() {
				@Override
				public boolean run(GLAutoDrawable drawable) {
					rr.dispose(drawable);
					rr = newRR;
					newRR.init(drawable);
					if (canvas instanceof GLJPanel)
						internalRepaint();
					return false;
				}
			});
		}
	}

	public boolean isDrawCarets() {
		return drawCarets;
	}

	public void setDrawCarets(boolean drawCarets) {
		this.drawCarets = drawCarets;
	}

	/**
	 * Captures the current 3D view as a BufferedImage.
	 * This method renders the current state of the 3D canvas to an image.
	 *
	 * @return a BufferedImage containing the current 3D view, or null if capture fails
	 */
	public BufferedImage captureImage() {
		if (canvas == null) {
			return null;
		}

		// Use GLAutoDrawable to get the actual surface dimensions (important for HiDPI displays)
		GLAutoDrawable drawable = (GLAutoDrawable) canvas;
		int surfaceWidth = drawable.getSurfaceWidth();
		int surfaceHeight = drawable.getSurfaceHeight();
		if (surfaceWidth <= 0 || surfaceHeight <= 0) {
			return null;
		}

		// Use AWTGLReadBufferUtil to read the framebuffer - works for both GLJPanel and GLCanvas
		final BufferedImage[] result = new BufferedImage[1];
		
		drawable.invoke(true, glDrawable -> {
			GL2 gl = glDrawable.getGL().getGL2();
			AWTGLReadBufferUtil readBufferUtil = new AWTGLReadBufferUtil(glDrawable.getGLProfile(), true);
			result[0] = readBufferUtil.readPixelsToBufferedImage(gl, true);
			return true;
		});
		
		return result[0];
	}
}
