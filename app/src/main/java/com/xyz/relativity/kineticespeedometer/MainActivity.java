package com.xyz.relativity.kineticespeedometer;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;

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
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity implements ILocationListener {
    private LocationManager locationManager;

    enum LineGraphs {
        SPEED("Speed (km/h)", Color.rgb(0, 255, 0), 4f),
        ENERGY("Kinetic Energy per 1kg (joules)", Color.rgb(255, 0, 0), 2f),
        ACCELERATION("Acceleration (m/s/s)", Color.rgb(140, 140, 140), 0.5f, YAxis.AxisDependency.RIGHT);

        public String label;
        public int color;
        public float lineSize;
        public YAxis.AxisDependency dependency;

        LineGraphs(String label, int color, float lineSize) {
            this(label, color, lineSize, YAxis.AxisDependency.LEFT);
        }

        LineGraphs(String label, int color, float lineSize, YAxis.AxisDependency dependency) {
            this.label = label;
            this.color = color;
            this.lineSize = lineSize;
            this.dependency = dependency;
        }
    }
    private static final float MASS_KG = 1;
    private static final float ONE_HALF_MASS_KG = MASS_KG * 0.5f;
    private static final float G_UNIT_CONVERSION = 0.10197162129779f;


    private static final int MAX_SAMPLES = 100;

    private LineChart chart;
    Timer timer = new Timer();

    float prevSpeed = 0;
    float prevTime = 0;
    float targetSpeed = 0;
    float speedStep = 0;
    float currentSpeed = targetSpeed;
    long startTime = System.currentTimeMillis();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        chart.setPinchZoom(false);

        Legend legend = chart.getLegend();
        legend.setVerticalAlignment(Legend.LegendVerticalAlignment.TOP);
        legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.LEFT);
        legend.setOrientation(Legend.LegendOrientation.VERTICAL);
        legend.setDrawInside(true);
        legend.setXOffset(30);


        chart.setData(buildLineData());
        chart.invalidate(); // refresh
    }

    @Override
    public void updatePosition(Location location) {
        if (location.hasSpeed()) {
            targetSpeed = location.getSpeed();
            speedStep = (targetSpeed - currentSpeed) / 10;
        }

        System.out.println(targetSpeed);
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
        chart.getAxisRight().setGridColor(LineGraphs.ACCELERATION.color);
        chart.getAxisRight().setGridColor(LineGraphs.ACCELERATION.color);
        chart.getAxisRight().setTextColor(LineGraphs.ACCELERATION.color);

        chart.getAxisRight().setAxisLineColor(LineGraphs.ACCELERATION.color);

        XAxis xAxis = chart.getXAxis();
        xAxis.setLabelRotationAngle(20f);
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
            LineDataSet speedDataSet = new LineDataSet(null, graph.label);
            speedDataSet.setMode(LineDataSet.Mode.HORIZONTAL_BEZIER);
            speedDataSet.setLineWidth(graph.lineSize);
            speedDataSet.setDrawCircles(false);
            speedDataSet.setColor(graph.color);
            speedDataSet.setAxisDependency(graph.dependency);
            dataSets.add(speedDataSet);
        }

        return new LineData(dataSets);
    }

    @Override
    protected void onResume() {
        super.onResume();
        locationManager.onResume();
    }

    @Override
    protected void onPause() {
        locationManager.onPause();
        super.onPause();
    }

}
