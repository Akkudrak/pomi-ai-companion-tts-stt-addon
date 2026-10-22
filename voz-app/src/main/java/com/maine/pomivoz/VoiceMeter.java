package com.maine.pomivoz;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

import javax.swing.JComponent;
import javax.swing.Timer;

/**
 * The microphone viewer: the last 3 seconds of level as bars (grey = noise, green = voice that will
 * be sent, blue = Pomi talking so the mic doesn't count) and the voice threshold as a dashed line.
 */
final class VoiceMeter extends JComponent {
	static final byte NOISE = 0, VOICE = 1, POMI_TALKING = 2;
	private static final int HISTORY = 150;
	/** Louder than this is clipping (will sound bad). */
	private static final double CLIPPING = 28000;

	private final float[] levels = new float[HISTORY];
	private final byte[] kinds = new byte[HISTORY];
	private int newest;
	private double threshold = 700;
	private volatile boolean dirty;

	VoiceMeter() {
		setPreferredSize(new Dimension(420, 80));
		setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
		new Timer(40, e -> {
			if (dirty) {
				dirty = false;
				repaint();
			}
		}).start();
	}

	/** One 20 ms frame; called from the microphone thread. */
	void push(double level, double threshold, byte kind) {
		synchronized (levels) {
			newest = (newest + 1) % HISTORY;
			levels[newest] = (float) level;
			kinds[newest] = kind;
			this.threshold = threshold;
		}
		dirty = true;
	}

	void clear() {
		synchronized (levels) {
			java.util.Arrays.fill(levels, 0);
		}
		dirty = true;
	}

	/** Log scale, like hearing: 0 = silence, 1 = as loud as it gets. */
	private static float scale(double level) {
		return (float) Math.max(0, Math.min(1, Math.log10(Math.max(level, 1)) / Math.log10(32768)));
	}

	@Override
	protected void paintComponent(Graphics graphics) {
		Graphics2D g = (Graphics2D) graphics;
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		int width = getWidth();
		int height = getHeight();
		g.setColor(new Color(28, 30, 34));
		g.fillRect(0, 0, width, height);
		float barWidth = (float) width / HISTORY;
		float limit;
		boolean clipping;
		synchronized (levels) {
			for (int i = 0; i < HISTORY; i++) {
				int index = (newest + 1 + i) % HISTORY;
				int bar = Math.round(scale(levels[index]) * (height - 2));
				if (bar < 1) {
					continue;
				}
				g.setColor(kinds[index] == VOICE ? new Color(80, 200, 110)
						: kinds[index] == POMI_TALKING ? new Color(80, 140, 220) : new Color(110, 115, 125));
				g.fillRect(Math.round(i * barWidth), height - bar, Math.max(1, Math.round(barWidth) - 1), bar);
			}
			clipping = levels[newest] > CLIPPING;
			limit = height - scale(threshold) * (height - 2);
		}
		Color amber = new Color(230, 190, 70);
		g.setColor(amber);
		g.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[] {5, 4}, 0));
		g.drawLine(0, Math.round(limit), width, Math.round(limit));
		g.drawString("umbral de voz", 4, Math.max(g.getFontMetrics().getAscent(), Math.round(limit) - 3));
		if (clipping) {
			g.setColor(new Color(230, 80, 70));
			String warning = "¡muy fuerte! baja el volumen";
			g.drawString(warning, width - g.getFontMetrics().stringWidth(warning) - 4, g.getFontMetrics().getAscent() + 2);
		}
	}
}
