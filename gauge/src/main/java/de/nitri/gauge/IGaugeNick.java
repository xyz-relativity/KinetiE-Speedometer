package de.nitri.gauge;

import android.graphics.Color;

public interface IGaugeNick {
    int getNicColor();
    boolean shouldDrawMajorNick(int nick, float value);
    int getMajorNicColor();
    boolean shouldDrawHalfNick(int nick, float value);
    int getMinorNicColor();
    String getNicLabelString(int nick, float value);
    int getNicLabelColor();
}
