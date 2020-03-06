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
import android.util.TypedValue;
import android.view.Window;
import android.view.WindowManager;

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
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import de.nitri.gauge.Gauge;
import de.nitri.gauge.IGaugeNick;

public class MainActivity extends AppCompatActivity implements ILocationListener {
    private LocationManager locationManager;

    private static final float MASS_KG = 1;
    private static final float ONE_HALF_MASS_KG = MASS_KG * 0.5f;
    private static final float G_UNIT_CONVERSION = 0.10197162129779f;
    private static final float GAUGE_MAX_SPEED = 100;
    private static final int GAUGE_NICK_COUNT = 200;


    private static final int MAX_SAMPLES = 500;

    private LineChart chart;
    Timer timer = new Timer();

    float prevSpeed = 0;
    float prevTime = 0;
    float targetSpeed = 0;
    float speedStep = 0;
    float currentSpeed = targetSpeed;
    long startTime = System.currentTimeMillis();
    private Gauge gaugeView;

    enum LineGraphs {
        ACCELERATION("Acceleration (g)", Color.rgb(200, 200, 255), 1f, YAxis.AxisDependency.LEFT),
        SPEED("Speed (km/h)", Color.rgb(0, 255, 0), 4f, YAxis.AxisDependency.RIGHT),
        ENERGY("Kinetic Energy/1kg (joules)", Color.rgb(255, 0, 0), 2f, YAxis.AxisDependency.RIGHT);

        public String label;
        public int color;
        public float lineSize;
        public YAxis.AxisDependency dependency;

        LineGraphs(String label, int color, float lineSize, YAxis.AxisDependency dependency) {
            this.label = label;
            this.color = color;
            this.lineSize = lineSize;
            this.dependency = dependency;
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
        locationManager = new LocationManager(this, 100);
        locationManager.addListener(this);

        initChart();
        initGauge();

        timer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                float time = (System.currentTimeMillis() - startTime);
                if (time < 0) {
                    startTime = System.currentTimeMillis();
                    time = 0;
                    prevTime = 0;
                }

                if (Math.floor(currentSpeed) != Math.floor(targetSpeed)) {
                    currentSpeed = currentSpeed + speedStep;
                } else {
                    speedStep = 0;
                    currentSpeed = targetSpeed;
                }

                float dt = time-prevTime;
                Float acceleration = null;
                if (dt != 0) {
                    acceleration = ((currentSpeed - prevSpeed) / (time - prevTime)) * G_UNIT_CONVERSION;
                }

                updateUi(time, currentSpeed * 3.6f, (ONE_HALF_MASS_KG * currentSpeed * currentSpeed), acceleration);
                prevTime = time;
                prevSpeed = currentSpeed;
            }
        }, 0, 100);
    }

    private void initChart() {
        chart = findViewById(R.id.chart);

        chart.setAutoScaleMinMaxEnabled(true);
        chart.setNoDataText("No chart data available. Use the menu to add entries and data sets!");
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

    private void initGauge() {
        gaugeView = findViewById(R.id.gauge);

        float maxSpeedms = (GAUGE_MAX_SPEED / 3.6f);
        int maxEnergy = Math.round(ONE_HALF_MASS_KG * maxSpeedms * maxSpeedms);

        gaugeView.setMinValue(0);
        gaugeView.setMaxValue(GAUGE_MAX_SPEED);
        gaugeView.setTotalNicks(GAUGE_NICK_COUNT);

        float valuePerNick = (maxEnergy) / (float)GAUGE_NICK_COUNT;

        final Map<Float, Integer> majorNickMap = new HashMap<>();

        for (int i = 0; i <= GAUGE_MAX_SPEED; i += 10) {
                majorNickMap.put(i * valuePerNick, i);
        }

        gaugeView.setNickHandler(new IGaugeNick() {
            @Override
            public boolean shouldDrawMajorNick(int nick, float value) {
                return majorNickMap.containsKey(value);
            }

            @Override
            public boolean shouldDrawHalfNick(int nick, float value) {
                return false;
            }

            @Override
            public String getLabelString(int nick, float value) {
                if (shouldDrawMajorNick(nick, value)) {
                    return String.valueOf(majorNickMap.get(value));
                } else {
                    return null;
                }
            }
        });
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
            speedStep = (targetSpeed - currentSpeed) / 10;
        }
    }

    private void updateUi(final float time, final Float speed, final Float energy, final Float acceleration) {
        final LineData data = chart.getData();
        data.addEntry(new Entry(time, speed), LineGraphs.SPEED.ordinal());
        data.addEntry(new Entry(time, energy), LineGraphs.ENERGY.ordinal());

        if (acceleration != null) {
            data.addEntry(new Entry(time, acceleration), LineGraphs.ACCELERATION.ordinal());
        }

        MainActivity.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                gaugeView.moveToValue(energy);
                gaugeView.setLowerText(String.format(Locale.getDefault(), "%.1f",speed));

                data.notifyDataChanged();
                chart.notifyDataSetChanged();
                chart.invalidate();
            }
        });

        if (data.getDataSetByIndex(0).getEntryCount() > MAX_SAMPLES) {
            data.getDataSetByIndex(0).removeFirst();
            data.getDataSetByIndex(1).removeFirst();
            data.getDataSetByIndex(2).removeFirst();
        }
    }

    private void configureAxis(LineChart chart) {
        if (LineGraphs.ACCELERATION.dependency == YAxis.AxisDependency.RIGHT) {
            chart.getAxisRight().setGridColor(LineGraphs.ACCELERATION.color);
            chart.getAxisRight().setTextColor(LineGraphs.ACCELERATION.color);
            chart.getAxisRight().setAxisLineColor(LineGraphs.ACCELERATION.color);

            chart.getAxisLeft().setGridColor(getThemeColor(MainActivity.this, android.R.attr.textColor));
            chart.getAxisLeft().setTextColor(getThemeColor(MainActivity.this, android.R.attr.textColor));
            chart.getAxisLeft().setAxisLineColor(getThemeColor(MainActivity.this, android.R.attr.textColor));

        } else {
            chart.getAxisLeft().setGridColor(LineGraphs.ACCELERATION.color);
            chart.getAxisLeft().setTextColor(LineGraphs.ACCELERATION.color);
            chart.getAxisLeft().setAxisLineColor(LineGraphs.ACCELERATION.color);

            chart.getAxisRight().setGridColor(getThemeColor(MainActivity.this, android.R.attr.textColor));
            chart.getAxisRight().setTextColor(getThemeColor(MainActivity.this, android.R.attr.textColor));
            chart.getAxisRight().setAxisLineColor(getThemeColor(MainActivity.this, android.R.attr.textColor));
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

    private LineData buildLineData() {
        List<ILineDataSet> dataSets = new ArrayList<>();

        for (LineGraphs graph: LineGraphs.values()) {
            LineDataSet dataSet = new LineDataSet(null, graph.label);
            dataSet.setMode(LineDataSet.Mode.HORIZONTAL_BEZIER);
            dataSet.setLineWidth(graph.lineSize);
            dataSet.setDrawCircles(false);
            dataSet.setColor(graph.color);
            dataSet.setAxisDependency(graph.dependency);
            dataSets.add(dataSet);
        }

        return new LineData(dataSets);
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        locationManager.onResume();
    }

    @Override
    protected void onPause() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        locationManager.onPause();
        super.onPause();
    }

}
