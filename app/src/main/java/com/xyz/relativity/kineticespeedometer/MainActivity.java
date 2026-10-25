package com.xyz.relativity.kineticespeedometer;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Color;
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
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.xyz.relativity.kineticespeedometer.fusion.GpsFixPolicy;
import com.xyz.relativity.kineticespeedometer.fusion.SpeedEstimator;
import com.xyz.relativity.kineticespeedometer.recording.SensorRecorder;
import com.xyz.relativity.kineticespeedometer.sensors.DeviceLocationManager;
import com.xyz.relativity.kineticespeedometer.sensors.ILocationListener;

import java.io.File;
import java.io.IOException;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import de.nitri.gauge.Gauge;
import de.nitri.gauge.IGaugeNick;

public class MainActivity extends AppCompatActivity implements ILocationListener, SensorEventListener {
	private DeviceLocationManager locationManager;

	private static final String SAVED_GRAPH_TIMESTAMPS = "GRAPH_TIMESTAMPS";
	private static final String SAVED_GRAPH_VALUES = "GRAPH_VALUES";
	private static final String SAVED_START_TIME = "START_TIME";
	private static final String SAVED_ODOMETER = "ODOMETER";

	private static final String PREFS_NAME = "configs";
	/** Odometer in metres stored as the raw bits of a double; the float key before it lost metres past 10,000 km. */
	private static final String PREF_ODOMETER_METERS_BITS = "odometer_meters_bits";
	private static final String PREF_ODOMETER_LEGACY_FLOAT = "odometer";

	private static final String ODOMETER_FORMAT = "%011.2f km";
	private static final float MASS_KG = 1;
	private static final float ONE_HALF_MASS_KG = MASS_KG * 0.5f;
	private static final float GAUGE_MAX_SPEED_KH = 200;
	private static final float MAX_SPEED_MS = (GAUGE_MAX_SPEED_KH / 3.6f);
	private static final int GAUGE_MAX_ENERGY = Math.round(ONE_HALF_MASS_KG * MAX_SPEED_MS * MAX_SPEED_MS);
	private static final int GAUGE_NICK_COUNT = (int)GAUGE_MAX_SPEED_KH;
	private static final int MAJOR_NICK_FOR_SPEED = 20;
	private static final int MINOR_NICK_FOR_SPEED = 10;

	// --- GPS handling ---
	/** 0 lets the hardware deliver fixes at its own rate; GPS chips rarely exceed 1 Hz anyway. */
	private static final int GPS_MIN_UPDATE_INTERVAL_MILLISECONDS = 0;
	/** Sub-folder of the app's external files directory that holds debug recordings. */
	private static final String RECORDINGS_DIRECTORY = "recordings";
	/** Low-pass factor for the gravity estimate when only a raw accelerometer is available. */
	private static final float RAW_GRAVITY_SMOOTHING = 0.02f;

	private LineChart chart;
	private TextView odometerView;
	private TextView statusView;

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

	// --- Tuning Constants ---
	private static final LineDataSet.Mode GRAPH_DATA_SET_DISPLAY_MODE = LineDataSet.Mode.LINEAR;
	private static final int GRAPH_DATA_WINDOW_SECONDS = 4 * 60; // 4-minute rolling window
	private static final int GRAPH_MAX_SAMPLES_PER_SECOND = 1000 / UI_UPDATE_INTERVAL_MS; // samples per second (10)
	private static final int GRAPH_MAX_SAMPLES = GRAPH_DATA_WINDOW_SECONDS * GRAPH_MAX_SAMPLES_PER_SECOND;
	/** The acceleration axis never zooms in tighter than this, in g, so noise around zero does not fill the chart. */
	private static final float ACCELERATION_AXIS_MIN_RANGE_G = 0.5f;

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

	@Override
	public void onPointerCaptureChanged(boolean hasCapture) {
		super.onPointerCaptureChanged(hasCapture);
	}

	enum LineGraphs {
		ACCELERATION(R.string.acceleration_label, R.string.acceleration_unit, Color.parseColor("#ff88ddff"), convertDpToPixel(0.3).floatValue(), YAxis.AxisDependency.LEFT),
		SPEED(R.string.speed_label, R.string.speed_unit, Color.parseColor("#ff22ff22"), convertDpToPixel(0.7).floatValue(), YAxis.AxisDependency.RIGHT),
		ENERGY(R.string.kinetic_energy_label, R.string.kinetic_energy_unit, Color.parseColor("#ffffff22"), convertDpToPixel(0.5).floatValue(), YAxis.AxisDependency.RIGHT);

		public final int label;
		public final int unit;
		public final int color;
		public final float lineSize;
		public final YAxis.AxisDependency dependency;

		LineGraphs(int label, int unit, int color, float lineSize, YAxis.AxisDependency dependency) {
			this.label = label;
			this.unit = unit;
			this.color = color;
			this.lineSize = lineSize;
			this.dependency = dependency;
		}

		public String getLabelWithUnit(Context c) {
			return c.getString(label, c.getString(unit));
		}
	}

	@Override
	public void onSaveInstanceState(@NonNull Bundle savedInstanceState){
		super.onSaveInstanceState(savedInstanceState);
		savedInstanceState.putLong(SAVED_START_TIME, startTime);
		savedInstanceState.putDouble(SAVED_ODOMETER, odometerMeters);

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

		long[] timestamps = savedInstanceState.getLongArray(SAVED_GRAPH_TIMESTAMPS);
		float[] speedValues = savedInstanceState.getFloatArray(SAVED_GRAPH_VALUES + "_" + LineGraphs.SPEED.name());
		float[] energyValues = savedInstanceState.getFloatArray(SAVED_GRAPH_VALUES + "_" + LineGraphs.ENERGY.name());
		float[] accelerationValues = savedInstanceState.getFloatArray(SAVED_GRAPH_VALUES + "_" + LineGraphs.ACCELERATION.name());
		if (timestamps == null || speedValues == null || energyValues == null || accelerationValues == null) {
			return;
		}

		int entryCount = Math.min(timestamps.length,
				Math.min(speedValues.length, Math.min(energyValues.length, accelerationValues.length)));
		for (int index = 0; index < entryCount; index++) {
			updateUi(timestamps[index], speedValues[index], energyValues[index], accelerationValues[index]);
		}
	}

	@Override
	protected void onStart() {
		super.onStart();
		if (settings.contains(PREF_ODOMETER_METERS_BITS)) {
			odometerMeters = Double.longBitsToDouble(settings.getLong(PREF_ODOMETER_METERS_BITS, 0L));
		} else if (settings.contains(PREF_ODOMETER_LEGACY_FLOAT)) {
			odometerMeters = settings.getFloat(PREF_ODOMETER_LEGACY_FLOAT, 0f);
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

		String version = "";
		try {
			PackageInfo pInfo = this.getPackageManager().getPackageInfo(getPackageName(), 0);
			version = getString(R.string.version, pInfo.versionName);
			((TextView) findViewById(R.id.textVersionString)).setText(version);
		} catch (PackageManager.NameNotFoundException ignore) {}

		statusView = findViewById(R.id.textFusionStatus);

		File recordingsRoot = getExternalFilesDir(null);
		if (recordingsRoot == null) {
			recordingsRoot = getFilesDir();
		}
		recorder = new SensorRecorder(new File(recordingsRoot, RECORDINGS_DIRECTORY));
		findViewById(R.id.textVersionString).setOnLongClickListener(new View.OnLongClickListener() {
			@Override
			public boolean onLongClick(View v) {
				toggleRecording();
				return true;
			}
		});

		if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
			Intent intent = new Intent(this, PermissionsActivity.class);
			startActivity(intent);
		}

		locationManager = new DeviceLocationManager(this, GPS_MIN_UPDATE_INTERVAL_MILLISECONDS, this);

		initChart();
		initGauge();
	}

	/**
	 * Edge to edge with the status and navigation bars visible: the window draws behind them, the
	 * bars are transparent (see the theme) so the background shows through, and the root view is
	 * padded by the bar insets so nothing sits underneath them.
	 */
	private void setUpSystemBars() {
		Window window = getWindow();
		WindowCompat.setDecorFitsSystemWindows(window, false);
		WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
		// Light icons on the dark background.
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
		chart.setAutoScaleMinMaxEnabled(true);
		chart.setDrawGridBackground(false);
		chart.getDescription().setEnabled(false);
		configureAxis(chart);
		chart.setDrawBorders(true);
		chart.setTouchEnabled(true);
		chart.setDragEnabled(true);
		chart.setScaleEnabled(true);
		chart.setVisibleXRangeMaximum(50);
		chart.setVisibleXRangeMinimum(50);

		Legend legend = chart.getLegend();
		legend.setVerticalAlignment(Legend.LegendVerticalAlignment.BOTTOM);
		legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
		legend.setOrientation(Legend.LegendOrientation.HORIZONTAL);
		legend.setDrawInside(false);
		legend.setTextColor(getThemeColor(MainActivity.this, android.R.attr.textColor));

		chart.setData(buildLineData());
		chart.setMaxVisibleValueCount(GRAPH_MAX_SAMPLES);
		chart.invalidate();
	}

	private void initGauge() {
		odometerView = findViewById(R.id.odometer);
		gaugeView = findViewById(R.id.gauge);
		gaugeView.setMinValue(0);
		gaugeView.setMaxValue(GAUGE_MAX_ENERGY);
		gaugeView.setTotalNicks(GAUGE_NICK_COUNT);

		gaugeView.setUpperTextUnit(getString(LineGraphs.SPEED.unit));
		gaugeView.setUpperTextColor(LineGraphs.SPEED.color);
		gaugeView.setUpperTextSize(140);
		gaugeView.setLowerTextUnit(getString(LineGraphs.ENERGY.unit));
		gaugeView.setLowerTextColor(LineGraphs.ENERGY.color);
		gaugeView.setLowerTextSize(80);

		float valuePerNick = (GAUGE_MAX_ENERGY) / (float) GAUGE_NICK_COUNT;
		final Map<Integer, Integer> majorNickMap = new HashMap<>();
		final Map<Integer, Integer> minorNickMap = new HashMap<>();

		for (int i = 0; i <= GAUGE_NICK_COUNT; i++) {
			int speed = lookAround(i, valuePerNick, MAJOR_NICK_FOR_SPEED);
			if (speed % MAJOR_NICK_FOR_SPEED == 0) majorNickMap.put(i, speed);

			speed = lookAround(i, valuePerNick, MINOR_NICK_FOR_SPEED);
			if (speed % MINOR_NICK_FOR_SPEED == 0) minorNickMap.put(i, speed);
		}

		gaugeView.setNickHandler(new IGaugeNick() {
			@Override public int getNicColor(int nick, float value) { return LineGraphs.ENERGY.color; }
			@Override public boolean shouldDrawMajorNick(int nick, float value) { return majorNickMap.containsKey(nick); }
			@Override public int getMajorNicColor(int nick, float value) { return LineGraphs.SPEED.color; }
			@Override public boolean shouldDrawHalfNick(int nick, float value) { return minorNickMap.containsKey(nick); }
			@Override public int getHalfNicColor(int nick, float value) { return LineGraphs.SPEED.color; }
			@Override public String getNicLabelString(int nick, float value) {
				if (nick != 0 && shouldDrawMajorNick(nick, value)) return String.valueOf(majorNickMap.get(nick));
				else return null;
			}
			@Override public int getNicLabelColor() { return LineGraphs.SPEED.color; }
		});
	}

	private int lookAround(int i, float valuePerNick, int nickInterval) {
		if (i == 0) return 0;
		float speedkmh = (float) Math.sqrt((2 * i * valuePerNick) / MASS_KG) * 3.6f;
		int smallerMultiple = ((int) (speedkmh / nickInterval) * nickInterval);
		int largerMultiple = smallerMultiple + nickInterval;

		int j = i;
		float nextSpeed = (float) Math.sqrt((2 * j * valuePerNick) / MASS_KG) * 3.6f;
		while (nextSpeed < largerMultiple) {
			j++;
			nextSpeed = (float) Math.sqrt((2 * j * valuePerNick) / MASS_KG) * 3.6f;
		}
		j--;

		if (i == j) return largerMultiple;
		else return -1;
	}

	private int getThemeColor(Context context, int colorAttr) {
		TypedValue typedValue = new TypedValue();
		TypedArray a = context.obtainStyledAttributes(typedValue.data, new int[]{colorAttr});
		int color = a.getColor(0, 0);
		a.recycle();
		return color;
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
			Toast.makeText(this, getString(R.string.recording_failed, recordingError.getMessage()), Toast.LENGTH_LONG).show();
		}

		odometerMeters += estimator.drainDistanceMeters();
		float speedMps = estimator.getSpeedMps();
		float accelerationInG = estimator.getForwardAccelMps2() / SensorManager.GRAVITY_EARTH;

		long currentSystemTime = System.currentTimeMillis();
		long time = (currentSystemTime - startTime);
		if (time < 0) {
			startTime = currentSystemTime;
			time = 0;
		}

		updateStatusView();
		updateUi(time, speedMps * 3.6f, (ONE_HALF_MASS_KG * speedMps * speedMps), accelerationInG);
	}

	private void updateStatusView() {
		long nowNs = SystemClock.elapsedRealtimeNanos();
		String status;
		switch (estimator.getMode(nowNs)) {
			case GPS:
				status = getString(R.string.status_gps);
				break;
			case DEAD_RECKONING:
				status = getString(R.string.status_dead_reckoning, Math.round(estimator.secondsSinceGps(nowNs)));
				break;
			default:
				status = getString(R.string.status_no_fix);
				break;
		}
		if (!estimator.isForwardAxisKnown()) {
			status = getString(R.string.status_learning_axis, status);
		}
		if (recorder.isRecording()) {
			status = getString(R.string.status_recording, status);
		}
		if (statusView != null) {
			statusView.setText(status);
		}
	}

	/** Long-press on the version label toggles the raw sensor and GPS recording used for filter tuning. */
	private void toggleRecording() {
		if (recorder.isRecording()) {
			File file = recorder.getFile();
			recorder.stop();
			Toast.makeText(this, getString(R.string.recording_stopped, file.getAbsolutePath()), Toast.LENGTH_LONG).show();
		} else {
			File file = recorder.start();
			Toast.makeText(this, getString(R.string.recording_started, file.getAbsolutePath()), Toast.LENGTH_LONG).show();
		}
	}

	private void updateUi(final long time, final Float speedKph, final Float energy, final Float acceleration) {
		final LineData data = chart.getData();
		if (data == null) return;

		data.addEntry(new Entry(time, speedKph), LineGraphs.SPEED.ordinal());
		data.addEntry(new Entry(time, energy), LineGraphs.ENERGY.ordinal());

		if (acceleration != null) {
			data.addEntry(new Entry(time, acceleration), LineGraphs.ACCELERATION.ordinal());
		}

		for (ILineDataSet i : data.getDataSets()) {
			if (i.getEntryCount() >= GRAPH_MAX_SAMPLES) {
				i.removeFirst();
			}
		}

		data.notifyDataChanged();
		clampAccelerationAxis(data);

		if (isRunning) {
			odometerView.setText(String.format(Locale.getDefault(), ODOMETER_FORMAT, odometerMeters / 1000.0));
			chart.notifyDataSetChanged();

			gaugeView.moveToValue(energy);
			gaugeView.setLowerText(String.format(Locale.getDefault(), "%.1f", energy));
			gaugeView.setUpperText(String.format(Locale.getDefault(), "%.1f", speedKph));

			chart.invalidate();
		}
	}

	/**
	 * Keeps the acceleration axis symmetric around zero and at least +/- the minimum range, while
	 * still growing when the data goes beyond it. Auto-scaling alone shrinks the axis to the noise.
	 */
	private void clampAccelerationAxis(LineData data) {
		ILineDataSet accelerationData = data.getDataSetByIndex(LineGraphs.ACCELERATION.ordinal());
		float range = ACCELERATION_AXIS_MIN_RANGE_G;
		if (accelerationData != null && accelerationData.getEntryCount() > 0) {
			range = Math.max(range, Math.max(Math.abs(accelerationData.getYMin()), Math.abs(accelerationData.getYMax())));
		}
		YAxis axis = LineGraphs.ACCELERATION.dependency == YAxis.AxisDependency.RIGHT ? chart.getAxisRight() : chart.getAxisLeft();
		axis.setAxisMinimum(-range);
		axis.setAxisMaximum(range);
	}

	private void configureAxis(LineChart chart) {
		if (LineGraphs.ACCELERATION.dependency == YAxis.AxisDependency.RIGHT) {
			prepareAccelerationAxis(chart.getAxisRight());
			prepareSpeedEnergyAxis(chart.getAxisLeft());
		} else {
			prepareAccelerationAxis(chart.getAxisLeft());
			prepareSpeedEnergyAxis(chart.getAxisRight());
		}

		XAxis xAxis = chart.getXAxis();
		xAxis.setLabelRotationAngle(20f);
		xAxis.setTextColor(getThemeColor(MainActivity.this, android.R.attr.textColor));
		xAxis.setGridColor(getThemeColor(MainActivity.this, android.R.attr.textColor));
		xAxis.setValueFormatter(new ValueFormatter() {
			final SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

			@Override
			public String getFormattedValue(float value) {
				return format.format(new Date(startTime + (long) value));
			}
		});
	}

	private void prepareAccelerationAxis(YAxis axis) {
		axis.setGridColor(LineGraphs.ACCELERATION.color);
		axis.setTextColor(LineGraphs.ACCELERATION.color);
		axis.setAxisLineColor(LineGraphs.ACCELERATION.color);
		axis.setValueFormatter(new ValueFormatter() {
			final NumberFormat formatter = NumberFormat.getInstance(Locale.getDefault());
			{
				formatter.setMaximumFractionDigits(4);
				formatter.setRoundingMode(RoundingMode.HALF_EVEN);
			}

			@Override
			public String getFormattedValue(float value) {
				return formatter.format(value);
			}
		});
	}

	private void prepareSpeedEnergyAxis(YAxis axis) {
		axis.setGridColor(getThemeColor(MainActivity.this, android.R.attr.color));
		axis.setTextColor(getThemeColor(MainActivity.this, android.R.attr.textColor));
		axis.setAxisLineColor(getThemeColor(MainActivity.this, android.R.attr.textColor));
		axis.setAxisMinimum(0);
		axis.setValueFormatter(new ValueFormatter() {
			final NumberFormat formatter = NumberFormat.getInstance(Locale.getDefault());
			{
				formatter.setMaximumFractionDigits(2);
				formatter.setRoundingMode(RoundingMode.HALF_EVEN);
			}
			@Override
			public String getFormattedValue(float value) {
				return formatter.format(value);
			}
		});
	}

	private LineData buildLineData() {
		List<ILineDataSet> dataSets = new CopyOnWriteArrayList<>();

		for (LineGraphs graph : LineGraphs.values()) {
			LineDataSet dataSet = new LineDataSet(new CopyOnWriteArrayList<Entry>(), graph.getLabelWithUnit(this));
			dataSet.setMode(GRAPH_DATA_SET_DISPLAY_MODE);
			dataSet.setLineWidth(graph.lineSize);
			dataSet.setDrawCircles(false);
			dataSet.setDrawValues(false);
			dataSet.setValueFormatter(new ValueFormatter() {
				@Override
				public String getFormattedValue(float value) {
					return String.format(Locale.getDefault(), "%.2f", value);
				}

				@Override
				public String getPointLabel(Entry entry) {
					return super.getPointLabel(entry);
				}
			});
			dataSet.setValueTextColor(graph.color);
			dataSet.setDrawVerticalHighlightIndicator(true);
			dataSet.setColor(graph.color);
			dataSet.setAxisDependency(graph.dependency);
			dataSets.add(dataSet);
		}

		return new LineData(dataSets);
	}

	@Override
	protected void onResume() {
		super.onResume();
		isRunning = true;
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		registerSensors();
		locationManager.onResume();

		uiHandler.post(uiRefreshRunnable);
	}

	@Override
	protected void onPause() {
		odometerMeters += estimator.drainDistanceMeters();
		settings.edit()
				.putLong(PREF_ODOMETER_METERS_BITS, Double.doubleToRawLongBits(odometerMeters))
				.remove(PREF_ODOMETER_LEGACY_FLOAT)
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
