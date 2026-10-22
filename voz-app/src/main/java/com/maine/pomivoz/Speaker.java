package com.maine.pomivoz;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Plays Pomi's lines. Everything is resampled to 48 kHz first, so it works on any sound card (ALSA,
 * PulseAudio, PipeWire, Windows) without asking it for odd rates.
 */
final class Speaker {
	static final int KOKORO_RATE = 24000;
	private static final float DEVICE_RATE = 48000f;

	/** While Pomi talks (and a moment after) the microphone is ignored, so she doesn't hear herself. */
	private volatile boolean speaking;
	private volatile long quietSince;

	boolean busy(long echoMillis) {
		return speaking || System.currentTimeMillis() - quietSince < echoMillis;
	}

	/** Kokoro's PCM with Pomi's pitch (1.25 = faster and higher) at {@code volume} percent. Blocks while playing. */
	void playVoice(byte[] pcm, double pitch, int volume) throws LineUnavailableException {
		play(Pcm.resample(pcm, KOKORO_RATE * pitch, DEVICE_RATE), volume, true);
	}

	/** Two little notes: "I'm listening". */
	void chime(int volume) {
		int rate = (int) DEVICE_RATE;
		int samples = rate * 24 / 100;
		byte[] pcm = new byte[samples * 2];
		for (int i = 0; i < samples; i++) {
			double t = (double) i / rate;
			double frequency = t < 0.12 ? 880 : 1320;
			double fade = Math.min(1.0, Math.min(t, 0.24 - t) * 40);
			Pcm.put(pcm, i, (int) (Math.sin(2 * Math.PI * frequency * t) * 6000 * fade));
		}
		try {
			play(pcm, volume, false);
		} catch (LineUnavailableException e) {
			// No sound card: the log line is enough.
		}
	}

	private void play(byte[] pcm, int volume, boolean isVoice) throws LineUnavailableException {
		byte[] data = pcm.clone();
		Pcm.amplify(data, data.length, volume / 100.0);
		AudioFormat format = new AudioFormat(DEVICE_RATE, 16, 1, true, false);
		if (isVoice) {
			speaking = true;
		}
		try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
			line.open(format, (int) DEVICE_RATE / 5 * 2);
			line.start();
			line.write(data, 0, data.length);
			line.drain();
		} finally {
			if (isVoice) {
				speaking = false;
				quietSince = System.currentTimeMillis();
			}
		}
	}
}
