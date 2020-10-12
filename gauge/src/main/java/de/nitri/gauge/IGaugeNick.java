package de.nitri.gauge;

public interface IGaugeNick {
	boolean shouldDrawMajorNick(int nick, float value);

	int getMajorNicColor(int nick, float value);

	boolean shouldDrawHalfNick(int nick, float value);

	int getHalfNicColor(int nick, float value);

	int getNicColor(int nick, float value);

	String getNicLabelString(int nick, float value);

	int getNicLabelColor();
}
