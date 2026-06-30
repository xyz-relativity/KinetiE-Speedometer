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
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.xyz.relativity.kineticespeedometer.sensors.DeviceLocationManager;
import com.xyz.relativity.kineticespeedometer.sensors.ILocationListener;

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

	private static final String SAVED_GRAPH_DATA = "GRAPH_DATA";
	private static final String SAVED_START_TIME = "START_TIME";
	private static final String SAVED_PREV_SPEED = "PREV_SPEED";
	private static final String SAVED_PREV_TIME = "PREV_TIME";
	private static final String SAVED_TARGET_SPEED = "TARGET_SPEED";
	private static final String SAVED_CURRENT_SPEED = "CURRENT_SPEED";
	private static final String SAVED_SPEED_STEP = "SPEED_STEP";
	private static final String SAVED_DELTA_LEFT = "DELTA_LEFT";

	private static final String ODOMETER_FORMAT = "%011.02f km";
	private static final float MASS_KG = 1;
	private static final float ONE_HALF_MASS_KG = MASS_KG * 0.5f;
	private static final float GAUGE_MAX_SPEED_KH = 200;
	private static final float MAX_SPEED_MS = (GAUGE_MAX_SPEED_KH / 3.6f); // convert to meters per second.
	private static final int GAUGE_MAX_ENERGY = Math.round(ONE_HALF_MASS_KG * MAX_SPEED_MS * MAX_SPEED_MS);
	private static final int GAUGE_NICK_COUNT = (int)GAUGE_MAX_SPEED_KH;
	private static final int MAJOR_NICK_FOR_SPEED = 20;
	private static final int MINOR_NICK_FOR_SPEED = 10;
	private static final int GPS_UPDATE_INTERVAL_MILLISECONDS = 500;

	private static final int GRAPH_MAX_SAMPLES = 10000;

	private LineChart chart;
	private TextView odometerView;

	long startTime = System.currentTimeMillis();
	float prevSpeed = 0;
	float prevTime = 0;
	float targetSpeedMps = 0;
	float currentSpeedMps = targetSpeedMps;
	float speedStep = 0;
	float deltaLeft = 0;

	private Gauge gaugeView;
	private boolean isRunning = false;
	private double odometerMeters;
	private SharedPreferences settings;

	// --- State Variables ---
	private float fusedSpeedMps = 0.0f;
	private long lastSensorTimestampNs = 0;
	private boolean hasFirstGpsFix = false;

	// --- Rotation and Orientation Matrices ---
	private final float[] rotationMatrix = new float[9];
	private final float[] localAcceleration = new float[3];
	private final float[] worldAcceleration = new float[3];

	// --- Tuning Constants ---
	// ALPHA determines the weight of GPS vs IMU.
	// 0.85 means: trust 85% of the existing velocity state + 15% new IMU adjustment.
	private static final float COMPLEMENTARY_FILTER_ALPHA = 0.85f;
	private static final float ACCEL_NOISE_DEADZONE = 0.15f; // m/s^2
	private static final float ACCEL_SMOOTHING_ALPHA = 0.1f;

    // Lower value = smoother but more lag. Higher value (e.g., 0.3) = more responsive but noisier.
	private float smoothedAcceleration = 0.0f; // Track historical state for LPF

	@Override
	public void onPointerCaptureChanged(boolean hasCapture) {
		super.onPointerCaptureChanged(hasCapture);
	}

	enum LineGraphs {
		ACCELERATION(R.string.acceleration_label, R.string.acceleration_unit, Color.parseColor("#ff88ddff"), convertDpToPixel(0.3).floatValue(), YAxis.AxisDependency.LEFT),
		SPEED(R.string.speed_label, R.string.speed_unit, Color.parseColor("#ff22ff22"), convertDpToPixel(1).floatValue(), YAxis.AxisDependency.RIGHT),
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
		savedInstanceState.putFloat(SAVED_PREV_SPEED, prevSpeed);
		savedInstanceState.putFloat(SAVED_PREV_TIME, prevTime);
		savedInstanceState.putFloat(SAVED_TARGET_SPEED, targetSpeedMps);
		savedInstanceState.putFloat(SAVED_CURRENT_SPEED, currentSpeedMps);
		savedInstanceState.putFloat(SAVED_SPEED_STEP, speedStep);
		savedInstanceState.putFloat(SAVED_DELTA_LEFT, deltaLeft);

		for (LineGraphs graph: LineGraphs.values()) {
			ILineDataSet dataSet = chart.getData().getDataSets().get(graph.ordinal());
			savedInstanceState.putString(SAVED_GRAPH_DATA + "_" + graph.name(), dataSet.toString());
		}
	}

	@Override
	public void onRestoreInstanceState(@NonNull Bundle savedInstanceState) {
		super.onRestoreInstanceState(savedInstanceState);

		startTime = savedInstanceState.getLong(SAVED_START_TIME);
		prevSpeed = savedInstanceState.getFloat(SAVED_PREV_SPEED);
		prevTime = savedInstanceState.getFloat(SAVED_PREV_TIME);
		targetSpeedMps = savedInstanceState.getFloat(SAVED_TARGET_SPEED);
		currentSpeedMps = savedInstanceState.getFloat(SAVED_CURRENT_SPEED);
		speedStep = savedInstanceState.getFloat(SAVED_SPEED_STEP);
		deltaLeft = savedInstanceState.getFloat(SAVED_DELTA_LEFT);

		String[] speedSplit = savedInstanceState.getString(SAVED_GRAPH_DATA + "_" + LineGraphs.SPEED.name()).split("Entry,");
		String[] energySplit = savedInstanceState.getString(SAVED_GRAPH_DATA + "_" + LineGraphs.ENERGY.name()).split("Entry,");
		String[] accelerationSplit = savedInstanceState.getString(SAVED_GRAPH_DATA + "_" + LineGraphs.ACCELERATION.name()).split("Entry,");

		for (int i = 1; i < speedSplit.length; ++i) {
			updateUi(Float.parseFloat(speedSplit[i].trim().split(" ")[1]),
					Float.parseFloat(speedSplit[i].trim().split(" ")[3]),
					Float.parseFloat(energySplit[i].trim().split(" ")[3]),
					Float.parseFloat(accelerationSplit[i].trim().split(" ")[3])
			);
		}
	}

	@Override
	protected void onStart() {
		super.onStart();
		odometerMeters = settings.getFloat("odometer", 0);
	}

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		SensorManager sm = (SensorManager) getSystemService(SENSOR_SERVICE);

		Sensor accel = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
		Sensor rotation = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);

// Use SENSOR_DELAY_GAME or SENSOR_DELAY_FASTEST for low-latency gaps
		sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI);
		sm.registerListener(this, rotation, SensorManager.SENSOR_DELAY_UI);

		settings = getSharedPreferences("configs", 0);

		supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
				WindowManager.LayoutParams.FLAG_FULLSCREEN);
		setContentView(R.layout.activity_main);

		String version = "";
		try {
			PackageInfo pInfo = this.getPackageManager().getPackageInfo(getPackageName(), 0);
			version = getString(R.string.version, pInfo.versionName);
			((TextView) findViewById(R.id.textVersionString)).setText(version);
		} catch (PackageManager.NameNotFoundException ignore) {
		}

		if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
				!= PackageManager.PERMISSION_GRANTED) {
			Intent intent = new Intent(this, PermissionsActivity.class);
			startActivity(intent);
		}

		//location
		locationManager = new DeviceLocationManager(this, GPS_UPDATE_INTERVAL_MILLISECONDS, this);

		initChart();
		initGauge();
	}

	private void initChart() {
		chart = findViewById(R.id.historyChart);

		chart.setAutoScaleMinMaxEnabled(true);
		chart.setDrawGridBackground(false);
		chart.getDescription().setEnabled(false);

		configureAxis(chart);

		chart.setDrawBorders(true);

		// enable touch gestures
		chart.setTouchEnabled(true);

		// enable scaling and dragging
		chart.setDragEnabled(true);
		chart.setScaleEnabled(true);
		chart.setVisibleXRangeMaximum(50);
		chart.setVisibleXRangeMinimum(50);

		// if disabled, scaling can be done on x- and y-axis separately
//        chart.setPinchZoom(false);

		Legend legend = chart.getLegend();
		legend.setVerticalAlignment(Legend.LegendVerticalAlignment.BOTTOM);
		legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.CENTER);
		legend.setOrientation(Legend.LegendOrientation.HORIZONTAL);
		legend.setDrawInside(false);
		legend.setTextColor(getThemeColor(MainActivity.this, android.R.attr.textColor));

		chart.setData(buildLineData());
		chart.setMaxVisibleValueCount(GRAPH_MAX_SAMPLES);
		chart.invalidate(); // refresh
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
			if (speed % MAJOR_NICK_FOR_SPEED == 0) {
				majorNickMap.put(i, speed);
			}

			speed = lookAround(i, valuePerNick, MINOR_NICK_FOR_SPEED);
			if (speed % MINOR_NICK_FOR_SPEED == 0) {
				minorNickMap.put(i, speed);
			}
		}

		gaugeView.setNickHandler(new IGaugeNick() {
			@Override
			public int getNicColor(int nick, float value) {
				return LineGraphs.ENERGY.color;
			}

			@Override
			public boolean shouldDrawMajorNick(int nick, float value) {
				return majorNickMap.containsKey(nick);
			}

			@Override
			public int getMajorNicColor(int nick, float value) {
				return LineGraphs.SPEED.color;
			}

			@Override
			public boolean shouldDrawHalfNick(int nick, float value) {
				return minorNickMap.containsKey(nick);
			}

			@Override
			public int getHalfNicColor(int nick, float value) {
				return LineGraphs.SPEED.color;
			}

			@Override
			public String getNicLabelString(int nick, float value) {
				if (nick != 0 && shouldDrawMajorNick(nick, value)) {
					return String.valueOf(majorNickMap.get(nick));
				} else {
					return null;
				}
			}

			@Override
			public int getNicLabelColor() {
				return LineGraphs.SPEED.color;
			}
		});
	}

	private int lookAround(int i, float valuePerNick, int nickInterval) {
		if (i == 0) return 0;

		float speedkmh = (float) Math.sqrt((2 * i * valuePerNick) / MASS_KG) * 3.6f;

		// Smaller multiple
		int smallerMultiple = ((int) (speedkmh / nickInterval) * nickInterval);
		// Larger multiple
		int largerMultiple = smallerMultiple + nickInterval;

		int j = i;
		float nextSpeed = (float) Math.sqrt((2 * j * valuePerNick) / MASS_KG) * 3.6f;

		while (nextSpeed < largerMultiple) {
			j++;
			nextSpeed = (float) Math.sqrt((2 * j * valuePerNick) / MASS_KG) * 3.6f;
		}
		j--;

		if (i == j) {
			return largerMultiple;
		} else {
			return -1;
		}
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
		if (location.hasSpeed() && location.getAccuracy() < 12.0f ) {
			targetSpeedMps = location.getSpeed();

			if (!hasFirstGpsFix) {
				fusedSpeedMps = targetSpeedMps;
				hasFirstGpsFix = true;
				return;
			}

			// Complementary filter fusion on GPS tick
			// Snap the fused speed closer to the absolute truth of the GPS
			fusedSpeedMps = (COMPLEMENTARY_FILTER_ALPHA * fusedSpeedMps) +
					((1.0f - COMPLEMENTARY_FILTER_ALPHA) * targetSpeedMps);
		}
	}

	// --- 2. WORLD TRANSLATION & INTEGRATION (IMU Input) ---
	@Override
	public void onSensorChanged(SensorEvent event) {
		// Track orientation to build the translation matrix
		if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
			SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
			return;
		}

		if (event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION && hasFirstGpsFix) {
			long currentTimestampNs = event.timestamp;

			if (lastSensorTimestampNs == 0) {
				lastSensorTimestampNs = currentTimestampNs;
				return;
			}

			float dt = (currentTimestampNs - lastSensorTimestampNs) / 1000000000.0f;
			lastSensorTimestampNs = currentTimestampNs;

			// Extract raw, phone-relative acceleration forces
			localAcceleration[0] = event.values[0]; // X: Left/Right
			localAcceleration[1] = event.values[1]; // Y: Up/Down
			localAcceleration[2] = event.values[2]; // Z: Forward/Backward

			// Matrix Multiplication: Project phone coordinates into World Coordinates
			// worldAcceleration[0] = East, worldAcceleration[1] = North, worldAcceleration[2] = Sky
			multiplyMatrixVector(rotationMatrix, localAcceleration, worldAcceleration);

			// In a moving vehicle, horizontal acceleration represents forward driving or braking force.
			// We calculate horizontal net acceleration vector magnitude (East/North plane)
			float horizontalAccelMag = (float) Math.sqrt(
					(worldAcceleration[0] * worldAcceleration[0]) +
							(worldAcceleration[1] * worldAcceleration[1])
			);

			// Filter micro-vibrations from engine idle or road bumps
			if (horizontalAccelMag < ACCEL_NOISE_DEADZONE) {
				horizontalAccelMag = 0.0f;
			}

			// Determine sign (Acceleration vs Braking) using vector dot product.
			// If the force vector aligns with the direction of gravity-adjusted forward travel, it is positive.
			// For dashboard phone mounts, Y or Z axis directional shifts dictate sign.
			boolean isDeaccelerating = worldAcceleration[1] < 0; // Negative North/Forward world vector component
            float stepAcceleration = isDeaccelerating ? -horizontalAccelMag : horizontalAccelMag;

			// --- APPLY LOW-PASS FILTER HERE ---
			// Formula: Smoothed = (Alpha * NewValue) + ((1 - Alpha) * OldSmoothedValue)
			smoothedAcceleration = (ACCEL_SMOOTHING_ALPHA * stepAcceleration) +
					((1.0f - ACCEL_SMOOTHING_ALPHA) * smoothedAcceleration);

			// Integrate acceleration step into the fused speed
			fusedSpeedMps += smoothedAcceleration * dt;

			// Prevent negative speeds when coming to a complete stop
			if (fusedSpeedMps < 0.1f) {
				fusedSpeedMps = 0.0f;
			}

			// Send to UI thread
			triggerUiUpdate(fusedSpeedMps, smoothedAcceleration);
		}
	}

	/**
	 * Helper method to multiply a 3x3 rotation matrix by a 3x1 vector.
	 */
	private void multiplyMatrixVector(float[] matrix, float[] vector, float[] result) {
		result[0] = matrix[0] * vector[0] + matrix[1] * vector[1] + matrix[2] * vector[2];
		result[1] = matrix[3] * vector[0] + matrix[4] * vector[1] + matrix[5] * vector[2];
		result[2] = matrix[6] * vector[0] + matrix[7] * vector[1] + matrix[8] * vector[2];
	}

	private void triggerUiUpdate(float speedMps, float acceleration) {
		float speedKmh = speedMps * 3.6f;
		float stepAccelerationInG = acceleration / SensorManager.GRAVITY_EARTH;

		float time = (System.currentTimeMillis() - startTime);
		if (time < 0) {
			startTime = System.currentTimeMillis();
			time = 0;
			prevTime = 0;
		}

		updateUi(time, speedKmh, (ONE_HALF_MASS_KG * speedMps * speedMps), stepAccelerationInG);
		// Broadcast speed values to your UI components here...
	}

	@Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

	private void updateUi(final float time, final Float speed, final Float energy, final Float acceleration) {
		this.runOnUiThread(new Runnable() {
			@Override
			public void run() {
				final LineData data = chart.getData();
				data.addEntry(new Entry(time, speed), LineGraphs.SPEED.ordinal());
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

				if (isRunning) {
					odometerView.setText(String.format(Locale.getDefault(), ODOMETER_FORMAT, odometerMeters/1000));

					chart.notifyDataSetChanged();

					gaugeView.moveToValue(energy);
					gaugeView.setLowerText(String.format(Locale.getDefault(), "%.1f", energy));
					gaugeView.setUpperText(String.format(Locale.getDefault(), "%.1f", speed));

					chart.invalidate();
				}
			}
		});
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
				formatter.setRoundingMode(RoundingMode.HALF_UP);
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
			dataSet.setMode(LineDataSet.Mode.LINEAR);
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
		locationManager.onResume();
	}

	@Override
	protected void onPause() {
		SharedPreferences.Editor editor = settings.edit();
		editor.putFloat("odometer", (float) odometerMeters);
		editor.apply();

		getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
//		locationManager.onPause();
		isRunning = false;
		super.onPause();
	}

	/**
	 * This method converts dp unit to equivalent pixels, depending on device density.
	 *
	 * @param dp A value in dp (density independent pixels) unit. Which we need to convert into pixels
	 * @return A float value to represent px equivalent to dp depending on device density
	 */
	public static Double convertDpToPixel(double dp) {
		return dp * ((double) Resources.getSystem().getDisplayMetrics().densityDpi / DisplayMetrics.DENSITY_DEFAULT);
	}

}
