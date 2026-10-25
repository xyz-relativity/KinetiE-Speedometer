package com.xyz.relativity.kineticespeedometer;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.DisplayMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.widget.TextViewCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.github.mikephil.charting.listener.ChartTouchListener;
import com.github.mikephil.charting.listener.OnChartGestureListener;
import com.xyz.relativity.kineticespeedometer.fusion.GpsFixPolicy;
import com.xyz.relativity.kineticespeedometer.fusion.SpeedEstimator;
import com.xyz.relativity.kineticespeedometer.recording.SensorRecorder;
import com.xyz.relativity.kineticespeedometer.sensors.DeviceLocationManager;
import com.xyz.relativity.kineticespeedometer.sensors.ILocationListener;

import java.io.File;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import de.nitri.gauge.Gauge;
import de.nitri.gauge.IGaugeNick;

public class MainActivity extends AppCompatActivity implements ILocationListener, SensorEventListener {
	private DeviceLocationManager locationManager;

	private static final String SAVED_GRAPH_TIMESTAMPS = "GRAPH_TIMESTAMPS";
	private static final String SAVED_GRAPH_VALUES = "GRAPH_VALUES";
	private static final String SAVED_START_TIME = "START_TIME";
	private static final String SAVED_ODOMETER = "ODOMETER";
	private static final String SAVED_MAX_SPEED = "MAX_SPEED";
	private static final String SAVED_TRIP_DISTANCE = "TRIP_DISTANCE";
	private static final String SAVED_TRIP_TIME = "TRIP_TIME";
	private static final String SAVED_CHART_WINDOW = "CHART_WINDOW";

	private static final String PREFS_NAME = "configs";
	/** Odometer in metres stored as the raw bits of a double; the float key before it lost metres past 10,000 km. */
	private static final String PREF_ODOMETER_METERS_BITS = "odometer_meters_bits";
	private static final String PREF_ODOMETER_LEGACY_FLOAT = "odometer";
	private static final String PREF_TRIP_DISTANCE_METERS_BITS = "trip_distance_meters_bits";
	private static final String PREF_TRIP_TIME_MS = "trip_time_ms";
	private static final String PREF_TRIP_MAX_SPEED_MPS = "trip_max_speed_mps";

	/** Tick positions along the energy scale; labels land on the nearest one. */
	private static final int GAUGE_NICK_COUNT = 200;
	private static final float JOULES_PER_KILOJOULE = 1000f;
	private static final float GAUGE_START_ANGLE = 20f;
	private static final float GAUGE_END_ANGLE = 340f;
	/** Labels closer than this on the dial are dropped; the energy scale packs the low speeds together. */
	private static final float LABEL_MIN_SEPARATION_DEG = 12f;
	/** Pixels on a 1080 px dial; the gauge scales them with the dial it draws. */
	private static final float GAUGE_SPEED_TEXT_SIZE = 180f;
	private static final float GAUGE_SPEED_UNIT_TEXT_SIZE = 48f;
	private static final float GAUGE_ENERGY_TEXT_SIZE = 110f;
	private static final float GAUGE_ENERGY_UNIT_TEXT_SIZE = 44f;
	/** Energy per kilogram equals g times the height of a fall with the same energy. */
	private static final float FEET_PER_METRE = 3.28084f;
	private static final float FALL_HEIGHT_DECIMAL_BELOW = 10f;
	private static final float READOUT_UNIT_SCALE = 0.55f;
	/** Fixes with a worse reported speed accuracy than this get an amber dot. */
	private static final float GPS_GOOD_SIGMA_MPS = 1.0f;
	/** After this long without a fix the dead-reckoned speed is no longer shown. */
	private static final float STALE_ESTIMATE_SECONDS = 120f;

	// --- GPS handling ---
	/** 0 lets the hardware deliver fixes at its own rate; GPS chips rarely exceed 1 Hz anyway. */
	private static final int GPS_MIN_UPDATE_INTERVAL_MILLISECONDS = 0;
	/** Sub-folder of the app's external files directory that holds debug recordings. */
	private static final String RECORDINGS_DIRECTORY = "recordings";
	/** Low-pass factor for the gravity estimate when only a raw accelerometer is available. */
	private static final float RAW_GRAVITY_SMOOTHING = 0.02f;

	private LineChart chart;
	private TextView odometerView;
	private TextView tripSummaryView;
	private final NumberFormat kilometreFormat = NumberFormat.getNumberInstance(Locale.getDefault());
	private TextView chipGps;
	private TextView chipDeadReckoning;
	private TextView chipCalibrating;
	private TextView chipRecording;
	private int gpsChipTextRes;
	private int gpsDotColor;
	private SpeedDisplay speedDisplay;
	private TextView readoutEnergyView;
	private TextView readoutAccelerationView;
	private TextView readoutMaxSpeedView;
	private AppSettings appSettings;
	private AppSettings.SpeedUnit unit;
	private float gaugeMaxMps;
	private float vehicleMassKg;
	/** Trip max speed, reset with the trip. */
	private float maxSpeedMps = 0f;
	private double tripDistanceMeters = 0.0;
	/** Driving time: ticks while the car is not standing still and the speed is known. */
	private long tripTimeMs = 0L;
	private long lastTickElapsedMs = 0L;
	private int chartWindowIndex = DEFAULT_CHART_WINDOW_INDEX;
	private long lastChartSampleMs = Long.MIN_VALUE;

	long startTime = System.currentTimeMillis();

	private Gauge gaugeView;
	private boolean isRunning = false;
	private double odometerMeters;
	private SharedPreferences settings;

	// --- Sensor fusion ---
	private final SpeedEstimator estimator = new SpeedEstimator();
	private SensorManager sensorManager;
	private Sensor linearAccelerationSensor;
	private Sensor gravitySensor;
	private Sensor rawAccelerometerSensor;
	private final float[] gravity = new float[3];
	private final float[] linearAcceleration = new float[3];
	private boolean hasGravity = false;
	private SensorRecorder recorder;

	// --- UI Decoupling Handler and Interval ---
	private static final int UI_UPDATE_INTERVAL_MS = 100; // 10Hz rendering rate

	// --- Chart ---
	/** Windows the chart cycles through on tap, in seconds. */
	private static final int[] CHART_WINDOWS_SECONDS = {60, 4 * 60, 15 * 60};
	private static final int DEFAULT_CHART_WINDOW_INDEX = 1;
	/** The chart needs far fewer points than the gauge; 2 Hz keeps the longest window cheap to draw. */
	private static final int CHART_SAMPLE_INTERVAL_MS = 500;
	private static final int GRAPH_MAX_SAMPLES = CHART_WINDOWS_SECONDS[CHART_WINDOWS_SECONDS.length - 1] * 1000 / CHART_SAMPLE_INTERVAL_MS;
	private static final float CHART_LINE_WIDTH_DP = 2f;
	private static final float ENERGY_LINE_WIDTH_DP = 1f;
	private static final int ENERGY_FILL_ALPHA = 46;
	private static final int ENERGY_LINE_ALPHA = 120;
	private static final float CHART_TEXT_SIZE_SP = 11f;
	/** Samples younger than this are labelled "now". */
	private static final float NOW_THRESHOLD_SECONDS = 5f;

	private final Handler uiHandler = new Handler(Looper.getMainLooper());
	private final Runnable uiRefreshRunnable = new Runnable() {
		@Override
		public void run() {
			if (isRunning) {
				processPeriodicUiUpdate();
				uiHandler.postDelayed(this, UI_UPDATE_INTERVAL_MS);
			}
		}
	};

	/** How far the shown speed can be trusted; drives the colour of the big number. */
	private enum SpeedDisplay { LIVE, DEAD_RECKONING, UNKNOWN }

	@Override
	public void onPointerCaptureChanged(boolean hasCapture) {
		super.onPointerCaptureChanged(hasCapture);
	}

	/** Series on the history chart, in drawing order: the energy area first, the speed line on top. */
	enum LineGraphs {
		ENERGY(R.color.accent_energy, YAxis.AxisDependency.RIGHT),
		SPEED(R.color.accent_speed, YAxis.AxisDependency.LEFT);

		public final int colorRes;
		public final YAxis.AxisDependency dependency;

		LineGraphs(int colorRes, YAxis.AxisDependency dependency) {
			this.colorRes = colorRes;
			this.dependency = dependency;
		}

		public int color(Context c) {
			return ContextCompat.getColor(c, colorRes);
		}
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle savedInstanceState){
		super.onSaveInstanceState(savedInstanceState);
		savedInstanceState.putLong(SAVED_START_TIME, startTime);
		savedInstanceState.putDouble(SAVED_ODOMETER, odometerMeters);
		savedInstanceState.putFloat(SAVED_MAX_SPEED, maxSpeedMps);
		savedInstanceState.putDouble(SAVED_TRIP_DISTANCE, tripDistanceMeters);
		savedInstanceState.putLong(SAVED_TRIP_TIME, tripTimeMs);
		savedInstanceState.putInt(SAVED_CHART_WINDOW, chartWindowIndex);

		LineData lineData = chart.getData();
		if (lineData == null || lineData.getDataSetCount() < LineGraphs.values().length) {
			return;
		}

		ILineDataSet speedData = lineData.getDataSetByIndex(LineGraphs.SPEED.ordinal());
		long[] timestamps = new long[speedData.getEntryCount()];
		for (int index = 0; index < timestamps.length; index++) {
			timestamps[index] = Math.round(speedData.getEntryForIndex(index).getX());
		}
		savedInstanceState.putLongArray(SAVED_GRAPH_TIMESTAMPS, timestamps);

		for (LineGraphs graph: LineGraphs.values()) {
			ILineDataSet dataSet = lineData.getDataSetByIndex(graph.ordinal());
			float[] values = new float[Math.min(timestamps.length, dataSet.getEntryCount())];
			for (int index = 0; index < values.length; index++) {
				values[index] = dataSet.getEntryForIndex(index).getY();
			}
			savedInstanceState.putFloatArray(SAVED_GRAPH_VALUES + "_" + graph.name(), values);
		}
	}

	@Override
	public void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
		super.onRestoreInstanceState(savedInstanceState);
		startTime = savedInstanceState.getLong(SAVED_START_TIME);
		odometerMeters = savedInstanceState.getDouble(SAVED_ODOMETER);
		maxSpeedMps = savedInstanceState.getFloat(SAVED_MAX_SPEED);
		tripDistanceMeters = savedInstanceState.getDouble(SAVED_TRIP_DISTANCE);
		tripTimeMs = savedInstanceState.getLong(SAVED_TRIP_TIME);

		chartWindowIndex = savedInstanceState.getInt(SAVED_CHART_WINDOW, DEFAULT_CHART_WINDOW_INDEX);
		long[] timestamps = savedInstanceState.getLongArray(SAVED_GRAPH_TIMESTAMPS);
		float[] speedValues = savedInstanceState.getFloatArray(SAVED_GRAPH_VALUES + "_" + LineGraphs.SPEED.name());
		float[] energyValues = savedInstanceState.getFloatArray(SAVED_GRAPH_VALUES + "_" + LineGraphs.ENERGY.name());
		if (timestamps == null || speedValues == null || energyValues == null) {
			return;
		}

		int entryCount = Math.min(timestamps.length, Math.min(speedValues.length, energyValues.length));
		for (int index = 0; index < entryCount; index++) {
			appendChartSample(timestamps[index], speedValues[index], energyValues[index]);
		}
		refreshChart();
	}

	@Override
	protected void onStart() {
		super.onStart();
		if (settings.contains(PREF_ODOMETER_METERS_BITS)) {
			odometerMeters = Double.longBitsToDouble(settings.getLong(PREF_ODOMETER_METERS_BITS, 0L));
		} else if (settings.contains(PREF_ODOMETER_LEGACY_FLOAT)) {
			odometerMeters = settings.getFloat(PREF_ODOMETER_LEGACY_FLOAT, 0f);
		}
		if (settings.contains(PREF_TRIP_DISTANCE_METERS_BITS)) {
			tripDistanceMeters = Double.longBitsToDouble(settings.getLong(PREF_TRIP_DISTANCE_METERS_BITS, 0L));
			tripTimeMs = settings.getLong(PREF_TRIP_TIME_MS, 0L);
			maxSpeedMps = settings.getFloat(PREF_TRIP_MAX_SPEED_MPS, 0f);
		}
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
		linearAccelerationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
		gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
		if (linearAccelerationSensor == null || gravitySensor == null) {
			rawAccelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
		}

		settings = getSharedPreferences(PREFS_NAME, 0);

		supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
		setContentView(R.layout.activity_main);
		setUpSystemBars();

		appSettings = new AppSettings(this);
		unit = appSettings.speedUnit();
		gaugeMaxMps = appSettings.gaugeMaxMps();
		vehicleMassKg = appSettings.vehicleMassKg();

		findViewById(R.id.buttonSettings).setOnClickListener(new View.OnClickListener() {
			@Override
			public void onClick(View v) {
				startActivity(new Intent(MainActivity.this, SettingsActivity.class));
			}
		});

		chipGps = findViewById(R.id.chipGps);
		chipDeadReckoning = findViewById(R.id.chipDeadReckoning);
		chipCalibrating = findViewById(R.id.chipCalibrating);
		chipRecording = findViewById(R.id.chipRecording);

		File recordingsRoot = getExternalFilesDir(null);
		if (recordingsRoot == null) {
			recordingsRoot = getFilesDir();
		}
		recorder = new SensorRecorder(new File(recordingsRoot, RECORDINGS_DIRECTORY));
		findViewById(R.id.tripRow).setOnLongClickListener(new View.OnLongClickListener() {
			@Override
			public boolean onLongClick(View v) {
				resetTrip();
				return true;
			}
		});
		kilometreFormat.setMinimumFractionDigits(1);
		kilometreFormat.setMaximumFractionDigits(1);

		if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
			Intent intent = new Intent(this, PermissionsActivity.class);
			startActivity(intent);
		}

		locationManager = new DeviceLocationManager(this, GPS_MIN_UPDATE_INTERVAL_MILLISECONDS, this);

		initChart();
		initGauge();
		configureGauge();
	}

	/**
	 * Full-screen dashboard: draws edge-to-edge on every supported Android version (API 35+ enforces
	 * it anyway), hides the status and navigation bars (a swipe from the edge shows them briefly) and
	 * pads the root so its children stay clear of the display cutout, or of the bars if they are ever
	 * shown. The root's background still fills the whole screen.
	 */
	private void setUpSystemBars() {
		Window window = getWindow();
		WindowCompat.setDecorFitsSystemWindows(window, false);
		WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
		controller.hide(WindowInsetsCompat.Type.systemBars());
		controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
		// Light icons and a dark 3-button scrim for the moments the bars are swiped in.
		controller.setAppearanceLightStatusBars(false);
		controller.setAppearanceLightNavigationBars(false);

		ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), new OnApplyWindowInsetsListener() {
			@Override
			public WindowInsetsCompat onApplyWindowInsets(@NonNull View v, @NonNull WindowInsetsCompat insets) {
				Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
				v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
				return insets;
			}
		});
	}

	private void initChart() {
		chart = findViewById(R.id.historyChart);
		chart.setData(buildLineData());
		chart.setAutoScaleMinMaxEnabled(true);
		chart.setDrawGridBackground(false);
		chart.setDrawBorders(false);
		chart.getDescription().setEnabled(false);
		chart.getLegend().setEnabled(false);
		chart.setMinOffset(0f);
		chart.setExtraOffsets(8f, 8f, 12f, 4f);
		configureAxis(chart);

		// Touch only cycles the window: no dragging, zooming or value highlighting while driving.
		chart.setTouchEnabled(true);
		chart.setDragEnabled(false);
		chart.setScaleEnabled(false);
		chart.setPinchZoom(false);
		chart.setDoubleTapToZoomEnabled(false);
		chart.setHighlightPerTapEnabled(false);
		chart.setHighlightPerDragEnabled(false);
		chart.setOnChartGestureListener(new OnChartGestureListener() {
			@Override public void onChartGestureStart(MotionEvent me, ChartTouchListener.ChartGesture lastPerformedGesture) {}
			@Override public void onChartGestureEnd(MotionEvent me, ChartTouchListener.ChartGesture lastPerformedGesture) {}
			@Override public void onChartLongPressed(MotionEvent me) {}
			@Override public void onChartDoubleTapped(MotionEvent me) {}
			@Override public void onChartSingleTapped(MotionEvent me) { cycleChartWindow(); }
			@Override public void onChartFling(MotionEvent me1, MotionEvent me2, float velocityX, float velocityY) {}
			@Override public void onChartScale(MotionEvent me, float scaleX, float scaleY) {}
			@Override public void onChartTranslate(MotionEvent me, float dX, float dY) {}
		});
		refreshChart();
	}

	private void initGauge() {
		odometerView = findViewById(R.id.odometer);
		tripSummaryView = findViewById(R.id.tripSummary);
		readoutEnergyView = findViewById(R.id.readoutEnergy);
		readoutAccelerationView = findViewById(R.id.readoutAcceleration);
		readoutMaxSpeedView = findViewById(R.id.readoutMaxSpeed);

		gaugeView = findViewById(R.id.gauge);
		gaugeView.setMinValue(0);
		gaugeView.setTotalNicks(GAUGE_NICK_COUNT);
		gaugeView.setStartAngle(GAUGE_START_ANGLE);
		gaugeView.setEndAngle(GAUGE_END_ANGLE);

		// Speed above the hub, energy below it: reading the two together is the point of the app.
		gaugeView.setUpperTextColor(ContextCompat.getColor(this, R.color.text_primary));
		gaugeView.setUpperTextSize(GAUGE_SPEED_TEXT_SIZE);
		gaugeView.setUpperTextUnitSize(GAUGE_SPEED_UNIT_TEXT_SIZE);
		gaugeView.setLowerTextColor(ContextCompat.getColor(this, R.color.accent_energy));
		gaugeView.setLowerTextSize(GAUGE_ENERGY_TEXT_SIZE);
		gaugeView.setLowerTextUnitSize(GAUGE_ENERGY_UNIT_TEXT_SIZE);
	}

	/** Unit-dependent dial setup: the energy scale ends at the dial maximum, labels every major step. */
	private void configureGauge() {
		float maxEnergy = 0.5f * gaugeMaxMps * gaugeMaxMps;
		gaugeView.setMaxValue(maxEnergy);
		gaugeView.setUpperTextUnit(getString(unit.speedLabel));

		float valuePerNick = maxEnergy / GAUGE_NICK_COUNT;
		final Map<Integer, Integer> majorNickMap = new TreeMap<>();
		final Map<Integer, Integer> minorNickMap = new HashMap<>();

		for (int i = 0; i <= GAUGE_NICK_COUNT; i++) {
			int speed = lookAround(i, valuePerNick, unit.majorLabelStep);
			if (speed % unit.majorLabelStep == 0) majorNickMap.put(i, speed);

			speed = lookAround(i, valuePerNick, unit.minorTickStep);
			if (speed % unit.minorTickStep == 0) minorNickMap.put(i, speed);
		}

		// The energy scale squeezes the low speeds into a few degrees, so only label ticks that are far enough apart.
		float degreesPerNick = (GAUGE_END_ANGLE - GAUGE_START_ANGLE) / GAUGE_NICK_COUNT;
		final Set<Integer> labelledNicks = new HashSet<>();
		float lastLabelDegrees = 0f;
		for (int nick : majorNickMap.keySet()) {
			float degrees = nick * degreesPerNick;
			if (nick != 0 && degrees - lastLabelDegrees >= LABEL_MIN_SEPARATION_DEG) {
				labelledNicks.add(nick);
				lastLabelDegrees = degrees;
			}
		}

		final int tickColor = ContextCompat.getColor(this, R.color.gauge_tick);
		final int labelColor = ContextCompat.getColor(this, R.color.text_secondary);
		gaugeView.setNickHandler(new IGaugeNick() {
			@Override public boolean shouldDrawNick(int nick, float value) { return false; }
			@Override public int getNicColor(int nick, float value) { return tickColor; }
			@Override public boolean shouldDrawMajorNick(int nick, float value) { return majorNickMap.containsKey(nick); }
			@Override public int getMajorNicColor(int nick, float value) { return tickColor; }
			@Override public boolean shouldDrawHalfNick(int nick, float value) { return minorNickMap.containsKey(nick); }
			@Override public int getHalfNicColor(int nick, float value) { return tickColor; }
			@Override public String getNicLabelString(int nick, float value) {
				return labelledNicks.contains(nick) ? String.valueOf(majorNickMap.get(nick)) : null;
			}
			@Override public int getNicLabelColor() { return labelColor; }
		});
		gaugeView.invalidate();
	}

	/** Re-reads the settings after the settings screen; rebuilds the dial only when its scale changed. */
	private void applySettings() {
		AppSettings.SpeedUnit newUnit = appSettings.speedUnit();
		float newGaugeMaxMps = appSettings.gaugeMaxMps();
		vehicleMassKg = appSettings.vehicleMassKg();
		if (newUnit != unit || newGaugeMaxMps != gaugeMaxMps) {
			unit = newUnit;
			gaugeMaxMps = newGaugeMaxMps;
			configureGauge();
			refreshChart();
		}
		syncRecordingWithSettings();
	}

	/** Recording is switched on the settings screen; the recorder itself lives here. */
	private void syncRecordingWithSettings() {
		boolean wanted = appSettings.recordSensors();
		if (wanted && !recorder.isRecording()) {
			File file = recorder.start();
			Toast.makeText(this, getString(R.string.recording_started, file.getAbsolutePath()), Toast.LENGTH_LONG).show();
		} else if (!wanted && recorder.isRecording()) {
			File file = recorder.getFile();
			recorder.stop();
			Toast.makeText(this, getString(R.string.recording_stopped, file.getAbsolutePath()), Toast.LENGTH_LONG).show();
		}
	}

	private String formatDistance(double meters) {
		return kilometreFormat.format(meters / unit.metresPerDistanceUnit);
	}

	private String formatDuration(long millis) {
		long minutes = millis / 60_000L;
		if (minutes < 60L) {
			return getString(R.string.duration_minutes, minutes);
		}
		return getString(R.string.duration_hours_minutes, minutes / 60L, minutes % 60L);
	}

	/** "12.4 km trip, 42 min, avg 31 km/h" with the distance in the primary colour. */
	private CharSequence tripSummary() {
		String distance = getString(R.string.trip_distance, formatDistance(tripDistanceMeters), getString(unit.distanceLabel));
		String average;
		if (tripTimeMs > 0L && tripDistanceMeters > 0.0) {
			float averageMps = (float) (tripDistanceMeters / (tripTimeMs / 1000.0));
			average = getString(R.string.average_speed, Math.round(unit.fromMps(averageMps)), getString(unit.speedLabel));
		} else {
			average = getString(R.string.speed_unknown);
		}
		String line = getString(R.string.trip_line, distance, formatDuration(tripTimeMs), average);
		SpannableString text = new SpannableString(line);
		int start = line.indexOf(distance);
		if (start >= 0) {
			text.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, R.color.text_primary)),
					start, start + distance.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		}
		return text;
	}

	/** Long-press on the trip row: distance, driving time and max speed start over. */
	private void resetTrip() {
		tripDistanceMeters = 0.0;
		tripTimeMs = 0L;
		maxSpeedMps = 0f;
		tripSummaryView.setText(tripSummary());
		readoutMaxSpeedView.setText(readout("0", getString(unit.speedLabel)));
		Toast.makeText(this, R.string.trip_reset, Toast.LENGTH_SHORT).show();
	}

	/** Value with a smaller, muted unit after it, for the readout tiles. */
	private CharSequence readout(String value, String unit) {
		SpannableString text = new SpannableString(value + " " + unit);
		int unitStart = value.length() + 1;
		text.setSpan(new RelativeSizeSpan(READOUT_UNIT_SCALE), unitStart, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		text.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, R.color.text_secondary)), unitStart, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
		return text;
	}

	/** Speed label that belongs on nick i, or -1 when a neighbouring nick is closer to the next multiple. */
	private int lookAround(int i, float valuePerNick, int nickInterval) {
		if (i == 0) return 0;
		float speed = unit.fromMps((float) Math.sqrt(2 * i * valuePerNick));
		int smallerMultiple = ((int) (speed / nickInterval) * nickInterval);
		int largerMultiple = smallerMultiple + nickInterval;

		int j = i;
		float nextSpeed = unit.fromMps((float) Math.sqrt(2 * j * valuePerNick));
		while (nextSpeed < largerMultiple) {
			j++;
			nextSpeed = unit.fromMps((float) Math.sqrt(2 * j * valuePerNick));
		}
		j--;

		if (i == j) return largerMultiple;
		else return -1;
	}

	@Override
	public void updatePosition(Location location) {
		long timestampNs = location.getElapsedRealtimeNanos();
		float speed = location.hasSpeed() ? location.getSpeed() : Float.NaN;
		float speedAccuracy = Float.NaN;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasSpeedAccuracy()) {
			speedAccuracy = location.getSpeedAccuracyMetersPerSecond();
		}
		float positionAccuracy = location.hasAccuracy() ? location.getAccuracy() : Float.NaN;
		recorder.recordGps(timestampNs, speed, speedAccuracy, positionAccuracy);

		if (!GpsFixPolicy.isUsable(speed, positionAccuracy)) {
			return;
		}
		estimator.onGpsSpeed(timestampNs, speed, GpsFixPolicy.speedSigma(speedAccuracy, positionAccuracy));
	}

	@Override
	public void onSensorChanged(SensorEvent event) {
		switch (event.sensor.getType()) {
			case Sensor.TYPE_GRAVITY:
				System.arraycopy(event.values, 0, gravity, 0, 3);
				hasGravity = true;
				recorder.recordGravity(event.timestamp, gravity);
				break;
			case Sensor.TYPE_LINEAR_ACCELERATION:
				recorder.recordLinearAcceleration(event.timestamp, event.values);
				if (hasGravity) {
					estimator.onSensorSample(event.timestamp, event.values, gravity);
				}
				break;
			case Sensor.TYPE_ACCELEROMETER:
				// Fallback for devices without the virtual gravity / linear acceleration sensors.
				if (!hasGravity) {
					System.arraycopy(event.values, 0, gravity, 0, 3);
					hasGravity = true;
					break;
				}
				for (int i = 0; i < 3; i++) {
					gravity[i] += RAW_GRAVITY_SMOOTHING * (event.values[i] - gravity[i]);
					linearAcceleration[i] = event.values[i] - gravity[i];
				}
				recorder.recordGravity(event.timestamp, gravity);
				recorder.recordLinearAcceleration(event.timestamp, linearAcceleration);
				estimator.onSensorSample(event.timestamp, linearAcceleration, gravity);
				break;
			default:
				break;
		}
	}

	@Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

	private void registerSensors() {
		hasGravity = false;
		if (rawAccelerometerSensor != null) {
			sensorManager.registerListener(this, rawAccelerometerSensor, SensorManager.SENSOR_DELAY_GAME);
			return;
		}
		if (gravitySensor != null && linearAccelerationSensor != null) {
			sensorManager.registerListener(this, gravitySensor, SensorManager.SENSOR_DELAY_GAME);
			sensorManager.registerListener(this, linearAccelerationSensor, SensorManager.SENSOR_DELAY_GAME);
		}
	}

	private void processPeriodicUiUpdate() {
		IOException recordingError = recorder.getLastError();
		if (recordingError != null && recorder.isRecording()) {
			recorder.stop();
			appSettings.setRecordSensors(false);
			Toast.makeText(this, getString(R.string.recording_failed, recordingError.getMessage()), Toast.LENGTH_LONG).show();
		}

		long nowNs = SystemClock.elapsedRealtimeNanos();
		SpeedEstimator.Mode mode = estimator.getMode(nowNs);
		applySpeedDisplay(speedDisplayFor(mode, nowNs));

		double travelled = estimator.drainDistanceMeters();
		odometerMeters += travelled;
		tripDistanceMeters += travelled;

		long nowMs = SystemClock.elapsedRealtime();
		boolean speedKnown = speedDisplay != SpeedDisplay.UNKNOWN;
		if (lastTickElapsedMs != 0L && speedKnown && !estimator.isStationary()) {
			tripTimeMs += nowMs - lastTickElapsedMs;
		}
		lastTickElapsedMs = nowMs;

		float speedMps = estimator.getSpeedMps();
		if (speedKnown) {
			maxSpeedMps = Math.max(maxSpeedMps, speedMps);
		}
		float accelerationInG = estimator.getForwardAccelMps2() / SensorManager.GRAVITY_EARTH;

		long currentSystemTime = System.currentTimeMillis();
		long time = (currentSystemTime - startTime);
		if (time < 0) {
			startTime = currentSystemTime;
			time = 0;
		}

		updateStatusRow(mode, nowNs);
		updateUi(time, speedMps, 0.5f * speedMps * speedMps, accelerationInG);
	}

	private SpeedDisplay speedDisplayFor(SpeedEstimator.Mode mode, long nowNs) {
		switch (mode) {
			case GPS:
				return SpeedDisplay.LIVE;
			case DEAD_RECKONING:
				return estimator.secondsSinceGps(nowNs) > STALE_ESTIMATE_SECONDS
						? SpeedDisplay.UNKNOWN : SpeedDisplay.DEAD_RECKONING;
			default:
				return SpeedDisplay.UNKNOWN;
		}
	}

	private void applySpeedDisplay(SpeedDisplay display) {
		if (display == speedDisplay) {
			return;
		}
		speedDisplay = display;
		int colorRes;
		switch (display) {
			case LIVE:
				colorRes = R.color.text_primary;
				break;
			case DEAD_RECKONING:
				colorRes = R.color.text_secondary;
				break;
			default:
				colorRes = R.color.text_muted;
				break;
		}
		gaugeView.setUpperTextColor(ContextCompat.getColor(this, colorRes));
	}

	private void updateStatusRow(SpeedEstimator.Mode mode, long nowNs) {
		int textRes;
		int dotColorRes;
		switch (mode) {
			case GPS:
				textRes = R.string.chip_gps;
				dotColorRes = estimator.getLastGpsSigmaMps() <= GPS_GOOD_SIGMA_MPS ? R.color.status_good : R.color.status_fair;
				break;
			case DEAD_RECKONING:
				textRes = R.string.chip_gps;
				dotColorRes = R.color.status_bad;
				break;
			default:
				textRes = R.string.chip_no_fix;
				dotColorRes = R.color.status_bad;
				break;
		}
		if (textRes != gpsChipTextRes) {
			gpsChipTextRes = textRes;
			chipGps.setText(textRes);
		}
		int dotColor = ContextCompat.getColor(this, dotColorRes);
		if (dotColor != gpsDotColor) {
			gpsDotColor = dotColor;
			TextViewCompat.setCompoundDrawableTintList(chipGps, ColorStateList.valueOf(dotColor));
		}

		boolean deadReckoning = mode == SpeedEstimator.Mode.DEAD_RECKONING;
		if (deadReckoning) {
			chipDeadReckoning.setText(getString(R.string.chip_dead_reckoning, Math.round(estimator.secondsSinceGps(nowNs))));
		}
		chipDeadReckoning.setVisibility(deadReckoning ? View.VISIBLE : View.GONE);
		boolean calibrating = mode == SpeedEstimator.Mode.GPS && !estimator.isForwardAxisKnown();
		chipCalibrating.setVisibility(calibrating ? View.VISIBLE : View.GONE);
		chipRecording.setVisibility(recorder.isRecording() ? View.VISIBLE : View.GONE);
	}

	/**
	 * @param speedMps       estimated speed, converted to the display unit here
	 * @param specificEnergy kinetic energy per kilogram (J/kg), which is also the dial's scale
	 */
	private void updateUi(long time, float speedMps, float specificEnergy, float accelerationInG) {
		boolean sampled = lastChartSampleMs == Long.MIN_VALUE || time - lastChartSampleMs >= CHART_SAMPLE_INTERVAL_MS;
		if (sampled) {
			appendChartSample(time, speedMps, specificEnergy);
		}
		if (!isRunning) {
			return;
		}

		odometerView.setText(getString(R.string.odometer_total, formatDistance(odometerMeters), getString(unit.distanceLabel)));
		tripSummaryView.setText(tripSummary());

		boolean unknown = speedDisplay == SpeedDisplay.UNKNOWN;
		String unknownText = getString(R.string.speed_unknown);
		gaugeView.moveToValue(unknown ? 0f : specificEnergy);
		gaugeView.setUpperText(unknown ? unknownText : String.valueOf(Math.round(unit.fromMps(speedMps))));

		boolean perKilogram = vehicleMassKg <= 0f;
		float energyShown = perKilogram ? specificEnergy : specificEnergy * vehicleMassKg / JOULES_PER_KILOJOULE;
		gaugeView.setLowerText(unknown ? unknownText : String.valueOf(Math.round(energyShown)));
		gaugeView.setLowerTextUnit(getString(perKilogram ? R.string.kinetic_energy_unit : R.string.unit_kj));

		// The tile translates the same energy into something physical: the drop that would produce it.
		boolean imperial = unit == AppSettings.SpeedUnit.MPH;
		float fallHeight = specificEnergy / SensorManager.GRAVITY_EARTH * (imperial ? FEET_PER_METRE : 1f);
		String fallText = fallHeight < FALL_HEIGHT_DECIMAL_BELOW
				? String.format(Locale.getDefault(), "%.1f", fallHeight)
				: String.valueOf(Math.round(fallHeight));
		readoutEnergyView.setText(readout(unknown ? unknownText : fallText, getString(imperial ? R.string.unit_ft : R.string.unit_m)));
		readoutAccelerationView.setText(readout(String.format(Locale.getDefault(), "%.2f", accelerationInG), getString(R.string.unit_g)));
		readoutMaxSpeedView.setText(readout(String.valueOf(Math.round(unit.fromMps(maxSpeedMps))), getString(unit.speedLabel)));

		if (sampled) {
			refreshChart();
		}
	}

	/** Adds one point per series (speed in m/s, energy in J/kg) and trims to the longest window. */
	private void appendChartSample(long time, float speedMps, float energy) {
		LineData data = chart.getData();
		if (data == null) {
			return;
		}
		data.addEntry(new Entry(time, speedMps), LineGraphs.SPEED.ordinal());
		data.addEntry(new Entry(time, energy), LineGraphs.ENERGY.ordinal());
		for (ILineDataSet dataSet : data.getDataSets()) {
			while (dataSet.getEntryCount() > GRAPH_MAX_SAMPLES) {
				dataSet.removeFirst();
			}
		}
		lastChartSampleMs = time;
	}

	/**
	 * Pins the x axis to the selected window ending at the newest sample, so the chart grows in from
	 * the right until the window is full and then scrolls. Auto-scaling keeps the y axes fitted to
	 * what is visible.
	 */
	private void refreshChart() {
		LineData data = chart.getData();
		if (data == null || data.getEntryCount() == 0) {
			chart.invalidate();
			return;
		}
		float windowMs = CHART_WINDOWS_SECONDS[chartWindowIndex] * 1000f;
		float xMax = data.getXMax();
		XAxis xAxis = chart.getXAxis();
		xAxis.setAxisMinimum(xMax - windowMs);
		xAxis.setAxisMaximum(xMax);
		data.notifyDataChanged();
		chart.notifyDataSetChanged();
		chart.invalidate();
	}

	private void cycleChartWindow() {
		chartWindowIndex = (chartWindowIndex + 1) % CHART_WINDOWS_SECONDS.length;
		refreshChart();
	}

	private void configureAxis(final LineChart chart) {
		int muted = ContextCompat.getColor(this, R.color.text_muted);
		int hairline = ContextCompat.getColor(this, R.color.gauge_rim);

		XAxis xAxis = chart.getXAxis();
		xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
		xAxis.setDrawGridLines(false);
		xAxis.setDrawAxisLine(true);
		xAxis.setAxisLineColor(hairline);
		xAxis.setTextColor(muted);
		xAxis.setTextSize(CHART_TEXT_SIZE_SP);
		xAxis.setLabelRotationAngle(0f);
		xAxis.setLabelCount(3, true);
		xAxis.setAvoidFirstLastClipping(true);
		xAxis.setValueFormatter(new ValueFormatter() {
			@Override
			public String getFormattedValue(float value) {
				LineData data = chart.getData();
				float ageSeconds = data == null ? 0f : (data.getXMax() - value) / 1000f;
				if (ageSeconds < NOW_THRESHOLD_SECONDS) {
					return getString(R.string.chart_now);
				}
				if (ageSeconds < 60f) {
					return getString(R.string.chart_seconds_ago, Math.round(ageSeconds));
				}
				return getString(R.string.chart_minutes_ago, Math.round(ageSeconds / 60f));
			}
		});

		YAxis speedAxis = chart.getAxisLeft();
		speedAxis.setDrawGridLines(false);
		speedAxis.setDrawAxisLine(false);
		speedAxis.setDrawZeroLine(false);
		speedAxis.setTextColor(muted);
		speedAxis.setTextSize(CHART_TEXT_SIZE_SP);
		speedAxis.setAxisMinimum(0f);
		speedAxis.setLabelCount(3, true);
		speedAxis.setValueFormatter(new ValueFormatter() {
			@Override
			public String getFormattedValue(float value) {
				return String.valueOf(Math.round(unit.fromMps(value)));
			}
		});

		// The energy area scales on its own axis, which stays enabled so auto-scaling computes it, but draws nothing.
		YAxis energyAxis = chart.getAxisRight();
		energyAxis.setDrawLabels(false);
		energyAxis.setDrawAxisLine(false);
		energyAxis.setDrawGridLines(false);
		energyAxis.setDrawZeroLine(false);
		energyAxis.setAxisMinimum(0f);
	}

	private LineData buildLineData() {
		List<ILineDataSet> dataSets = new ArrayList<>();

		for (LineGraphs graph : LineGraphs.values()) {
			LineDataSet dataSet = new LineDataSet(new ArrayList<Entry>(), graph.name());
			int color = graph.color(this);
			dataSet.setMode(LineDataSet.Mode.LINEAR);
			dataSet.setDrawCircles(false);
			dataSet.setDrawValues(false);
			dataSet.setHighlightEnabled(false);
			dataSet.setAxisDependency(graph.dependency);
			if (graph == LineGraphs.ENERGY) {
				dataSet.setDrawFilled(true);
				dataSet.setFillColor(color);
				dataSet.setFillAlpha(ENERGY_FILL_ALPHA);
				dataSet.setColor(ColorUtils.setAlphaComponent(color, ENERGY_LINE_ALPHA));
				dataSet.setLineWidth(ENERGY_LINE_WIDTH_DP);
			} else {
				dataSet.setColor(color);
				dataSet.setLineWidth(CHART_LINE_WIDTH_DP);
			}
			dataSets.add(dataSet);
		}

		return new LineData(dataSets);
	}

	@Override
	protected void onResume() {
		super.onResume();
		isRunning = true;
		applySettings();
		lastTickElapsedMs = SystemClock.elapsedRealtime();
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		registerSensors();
		locationManager.onResume();

		uiHandler.post(uiRefreshRunnable);
	}

	@Override
	protected void onPause() {
		double travelled = estimator.drainDistanceMeters();
		odometerMeters += travelled;
		tripDistanceMeters += travelled;
		lastTickElapsedMs = 0L;
		settings.edit()
				.putLong(PREF_ODOMETER_METERS_BITS, Double.doubleToRawLongBits(odometerMeters))
				.remove(PREF_ODOMETER_LEGACY_FLOAT)
				.putLong(PREF_TRIP_DISTANCE_METERS_BITS, Double.doubleToRawLongBits(tripDistanceMeters))
				.putLong(PREF_TRIP_TIME_MS, tripTimeMs)
				.putFloat(PREF_TRIP_MAX_SPEED_MPS, maxSpeedMps)
				.apply();

		getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		locationManager.onPause();
		sensorManager.unregisterListener(this);
		isRunning = false;
		recorder.flush();

		uiHandler.removeCallbacks(uiRefreshRunnable);
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		recorder.shutdown();
		super.onDestroy();
	}

	public static Double convertDpToPixel(double dp) {
		return dp * ((double) Resources.getSystem().getDisplayMetrics().densityDpi / DisplayMetrics.DENSITY_DEFAULT);
	}
}
