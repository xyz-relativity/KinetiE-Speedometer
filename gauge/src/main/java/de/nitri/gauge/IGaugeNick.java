package de.nitri.gauge;

public interface IGaugeNick {
	/** Whether the thin nick for this scale position is drawn at all. */
	boolean shouldDrawNick(int nick, float value);

	boolean shouldDrawMajorNick(int nick, float value);

	int getMajorNicColor(int nick, float value);

	boolean shouldDrawHalfNick(int nick, float value);

	int getHalfNicColor(int nick, float value);

	int getNicColor(int nick, float value);

	String getNicLabelString(int nick, float value);

	int getNicLabelColor();
}
