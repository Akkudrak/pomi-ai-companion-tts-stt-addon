package com.maine.pomivoz;

import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.Mixer;
import javax.sound.sampled.TargetDataLine;

/**
 * A microphone giving 16 kHz 16-bit mono frames. Many Linux devices (hw:) only record at 44.1 or
 * 48 kHz: then it records at what the device takes and resamples.
 */
final class Microphone implements AutoCloseable {
	static final int RATE = 16000;
	private static final float[] TRY_RATES = {16000f, 48000f, 44100f};

	private final TargetDataLine line;
	private final float rate;
	private final byte[] raw;

	/**
	 * The names of the devices that can record (not Java's "Port" mixers, which are only volume
	 * controls, nor its "primary capture driver", which is the system default already listed first).
	 */
	static List<String> devices() {
		List<String> names = new ArrayList<>();
		for (Mixer.Info info : AudioSystem.getMixerInfo()) {
			Mixer mixer = AudioSystem.getMixer(info);
			boolean records = false;
			for (float rate : TRY_RATES) {
				records |= mixer.isLineSupported(new DataLine.Info(TargetDataLine.class, format(rate)));
			}
			String name = info.getName();
			if (records && !name.startsWith("Port ") && !name.startsWith("Controlador primario") && !name.startsWith("Primary Sound")) {
				names.add(name);
			}
		}
		return names;
	}

	private static AudioFormat format(float rate) {
		return new AudioFormat(rate, 16, 1, true, false);
	}

	/**
	 * @param name the device; empty, or not found, = the system default
	 * @param frameBytes bytes per 16 kHz frame handed out by {@link #read}
	 */
	Microphone(String name, int frameBytes) throws LineUnavailableException {
		line = open(name);
		rate = line.getFormat().getSampleRate();
		raw = new byte[(int) Math.round(frameBytes / 2.0 * rate / RATE) * 2];
	}

	/** The device's line at the first rate it takes. */
	private static TargetDataLine open(String name) throws LineUnavailableException {
		Mixer mixer = null;
		if (name != null && !name.isEmpty()) {
			for (Mixer.Info info : AudioSystem.getMixerInfo()) {
				if (info.getName().equals(name)) {
					mixer = AudioSystem.getMixer(info);
					break;
				}
			}
		}
		LineUnavailableException last = null;
		for (float candidate : TRY_RATES) {
			AudioFormat format = format(candidate);
			DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
			try {
				TargetDataLine opened = mixer == null ? (TargetDataLine) AudioSystem.getLine(info) : (TargetDataLine) mixer.getLine(info);
				opened.open(format, (int) (candidate / 5) * 2);
				opened.start();
				return opened;
			} catch (LineUnavailableException | IllegalArgumentException e) {
				last = e instanceof LineUnavailableException lue ? lue : new LineUnavailableException(e.getMessage());
			}
		}
		throw last == null ? new LineUnavailableException("sin micrófono") : last;
	}

	/** Fills {@code frame} with the next 16 kHz piece of audio (blocks until there is one). */
	void read(byte[] frame) {
		int read = 0;
		while (read < raw.length) {
			int n = line.read(raw, read, raw.length - read);
			if (n <= 0) {
				break;
			}
			read += n;
		}
		byte[] converted = rate == RATE ? raw : Pcm.resample(raw, rate, RATE);
		System.arraycopy(converted, 0, frame, 0, Math.min(frame.length, converted.length));
	}

	@Override
	public void close() {
		line.stop();
		line.close();
	}
}
