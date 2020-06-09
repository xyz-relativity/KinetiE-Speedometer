package com.xyz.relativity.kineticespeedometer;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.util.TimeUtils;
import android.util.TypedValue;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.ValueFormatter;
import com.github.mikephil.charting.interfaces.datasets.ILineDataSet;
import com.xyz.relativity.kineticespeedometer.sensors.ILocationListener;
import com.xyz.relativity.kineticespeedometer.sensors.LocationManager;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import de.nitri.gauge.Gauge;
import de.nitri.gauge.IGaugeNick;

public class MainActivity extends AppCompatActivity implements ILocationListener {
    private LocationManager locationManager;

    private static final float MASS_KG = 1;
    private static final float ONE_HALF_MASS_KG = MASS_KG * 0.5f;
    private static final float G_UNIT_CONVERSION = 0.10197162129779f;
    private static final float GAUGE_MAX_SPEED_KH = 200;
    private static final float MAX_SPEED_MS = (GAUGE_MAX_SPEED_KH / 3.6f); // convert to meters per second.
    private static final int GAUGE_MAX_ENERGY = Math.round(ONE_HALF_MASS_KG * MAX_SPEED_MS * MAX_SPEED_MS);
    private static final int GAUGE_NICK_COUNT = 200;
    private static final int MAJOR_NICK_FOR_SPEED = 20;
    private static final int MINOR_NICK_FOR_SPEED = 10;
    private static final int GPS_UPDATE_INTERVAL_MILLISECONDS = 250;
    private static final int GRAPH_HISTORY_LENGTH_SECONDS = (int)TimeUnit.MINUTES.toSeconds(5);
    private static final int GRAPH_UPDATE_INTERVAL_MILLISECONDS = 100;

    private static final int MAX_SAMPLES = GRAPH_HISTORY_LENGTH_SECONDS * ((int)TimeUnit.SECONDS.toMillis(1)/GRAPH_UPDATE_INTERVAL_MILLISECONDS);

    private LineChart chart;
    Timer timer = new Timer();

    float prevSpeed = 0;
    float prevTime = 0;
    float targetSpeed = 0;
    float speedStep = 0;
    float deltaLeft = 0;
    float currentSpeed = targetSpeed;

    long startTime = System.currentTimeMillis();
    private Gauge gaugeView;
    TextView energyTextView;
    private boolean isRunning = false;

    enum LineGraphs {
        ACCELERATION(R.string.acceleration_label, R.string.acceleration_unit, Color.rgb(200, 200, 255), 0.5f, YAxis.AxisDependency.LEFT),
        SPEED(R.string.speed_label, R.string.speed_unit, Color.rgb(0, 255, 0), 2f, YAxis.AxisDependency.RIGHT),
        ENERGY(R.string.kinetic_energy_label, R.string.kinetic_energy_unit, Color.rgb(255, 255, 0), 1f, YAxis.AxisDependency.RIGHT);

        public int label;
        public int unit;
        public int color;
        public float lineSize;
        public YAxis.AxisDependency dependency;
        public List<Entry>container = new CopyOnWriteArrayList<>();

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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Intent intent = new Intent(this, PermissionsActivity.class);
            startActivity(intent);
        }

        //location
        locationManager = new LocationManager(this, GPS_UPDATE_INTERVAL_MILLISECONDS);
        locationManager.addListener(this);

        energyTextView = findViewById(R.id.energyTextView);
        initChart();
        initGauge();

        timer.cancel();
        timer = new Timer();
        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                float time = (System.currentTimeMillis() - startTime);
                if (time < 0) {
                    startTime = System.currentTimeMillis();
                    time = 0;
                    prevTime = 0;
                }

                if (deltaLeft <= Math.abs(speedStep)) {
                    speedStep = 0;
                    currentSpeed = targetSpeed;
                    deltaLeft = 0;
                } else {
                    currentSpeed = currentSpeed + speedStep;
                    deltaLeft = deltaLeft - Math.abs(speedStep);
                }

                float dt = time - prevTime;
                Float acceleration = null;
                if (dt != 0) {
                    acceleration = ((currentSpeed - prevSpeed) / (time - prevTime)) * G_UNIT_CONVERSION;
                }

                updateUi(time, currentSpeed * 3.6f, (ONE_HALF_MASS_KG * currentSpeed * currentSpeed), acceleration);
                prevTime = time;
                prevSpeed = currentSpeed;
            }
        }, 0, GRAPH_UPDATE_INTERVAL_MILLISECONDS);
    }

    private void initChart() {
        chart = findViewById(R.id.chart);

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
        legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.LEFT);
        legend.setOrientation(Legend.LegendOrientation.HORIZONTAL);
        legend.setDrawInside(false);
        legend.setTextColor(getThemeColor(MainActivity.this, android.R.attr.textColor));

        chart.setData(buildLineData());
        chart.invalidate(); // refresh
    }

    private void initGauge(){
        gaugeView = findViewById(R.id.gauge);
        gaugeView.setMinValue(0);
        gaugeView.setMaxValue(GAUGE_MAX_ENERGY);
        gaugeView.setTotalNicks(GAUGE_NICK_COUNT);

        gaugeView.setUpperTextUnit(getString(LineGraphs.SPEED.unit));
        gaugeView.setUpperTextColor(LineGraphs.SPEED.color);
        gaugeView.setLowerTextUnit(getString(LineGraphs.ENERGY.unit));
        gaugeView.setLowerTextColor(LineGraphs.ENERGY.color);

        float valuePerNick = (GAUGE_MAX_ENERGY) / (float)GAUGE_NICK_COUNT;

        final Map<Integer, Integer> majorNickMap = new HashMap<>();
        final Map<Integer, Integer> minorNickMap = new HashMap<>();

        for (int i = 0; i <= GAUGE_NICK_COUNT; i ++) {
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
            public int getNicColor() {
                return LineGraphs.ENERGY.color;
            }

            @Override
            public boolean shouldDrawMajorNick(int nick, float value) {
                return majorNickMap.containsKey(nick);
            }

            @Override
            public int getMajorNicColor() {
                return LineGraphs.SPEED.color;
            }

            @Override
            public boolean shouldDrawHalfNick(int nick, float value) {
                return minorNickMap.containsKey(nick);
            }

            @Override
            public int getMinorNicColor() {
                return LineGraphs.ENERGY.color;
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
        int smallerMultiple = ((int)(speedkmh / nickInterval) * nickInterval);
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

        TypedArray a = context.obtainStyledAttributes(typedValue.data, new int[] { colorAttr });
        int color = a.getColor(0, 0);

        a.recycle();

        return color;
    }

    @Override
    public void updatePosition(Location location) {
        if (location.hasSpeed()) {
            targetSpeed = location.getSpeed();
            deltaLeft = Math.abs(targetSpeed - currentSpeed);
            speedStep = (targetSpeed - currentSpeed) / 10f;
        }
    }

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

                for (ILineDataSet i: data.getDataSets()) {
                    if (i.getEntryCount() > MAX_SAMPLES) {
                        i.removeFirst();
                    }
                }

                data.notifyDataChanged();

                if (isRunning) {
                    chart.notifyDataSetChanged();

                    //energyTextView.setText(String.format(Locale.getDefault(), "%.1f (J)", energy));
                    
                    gaugeView.moveToValue(energy);
                    gaugeView.setUpperText(String.format(Locale.getDefault(), "%.1f", energy));
                    gaugeView.setLowerText(String.format(Locale.getDefault(), "%.1f", speed));

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
            SimpleDateFormat format = new SimpleDateFormat("HH:mm:ss");
            @Override
            public String getFormattedValue(float value) {
                return format.format( new Date(startTime + (long)value));
            }
        });
    }

    private void prepareAccelerationAxis(YAxis axis) {
        axis.setGridColor(LineGraphs.ACCELERATION.color);
        axis.setTextColor(LineGraphs.ACCELERATION.color);
        axis.setAxisLineColor(LineGraphs.ACCELERATION.color);
    }

    private void prepareSpeedEnergyAxis(YAxis axis) {
        axis.setGridColor(getThemeColor(MainActivity.this, android.R.attr.textColor));
        axis.setTextColor(getThemeColor(MainActivity.this, android.R.attr.textColor));
        axis.setAxisLineColor(getThemeColor(MainActivity.this, android.R.attr.textColor));
        axis.setAxisMinimum(0);
    }

    private LineData buildLineData() {
        List<ILineDataSet> dataSets = new CopyOnWriteArrayList<>();

        for (LineGraphs graph: LineGraphs.values()) {
            LineDataSet dataSet = new LineDataSet(new CopyOnWriteArrayList<Entry>(), graph.getLabelWithUnit(this));
            dataSet.setMode(LineDataSet.Mode.HORIZONTAL_BEZIER);
            dataSet.setLineWidth(graph.lineSize);
            dataSet.setDrawCircles(false);
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
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        locationManager.onPause();
        isRunning = false;
        super.onPause();
    }

}
