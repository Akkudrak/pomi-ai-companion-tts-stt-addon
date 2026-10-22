package com.maine.pomivoz;

/** 16-bit little-endian mono PCM helpers. */
final class Pcm {
	private Pcm() {
	}

	static int sample(byte[] pcm, int index) {
		return (short) ((pcm[index * 2] & 0xFF) | (pcm[index * 2 + 1] << 8));
	}

	static void put(byte[] pcm, int index, int sample) {
		int clipped = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample));
		pcm[index * 2] = (byte) clipped;
		pcm[index * 2 + 1] = (byte) (clipped >> 8);
	}

	/** Multiplies the samples in place, clipping at the maximum. */
	static void amplify(byte[] pcm, int length, double gain) {
		if (Math.abs(gain - 1.0) < 0.01) {
			return;
		}
		for (int i = 0; i < length / 2; i++) {
			put(pcm, i, (int) (sample(pcm, i) * gain));
		}
	}

	static double rms(byte[] pcm) {
		long sum = 0;
		int samples = pcm.length / 2;
		for (int i = 0; i < samples; i++) {
			long s = sample(pcm, i);
			sum += s * s;
		}
		return Math.sqrt(sum / Math.max(1.0, samples));
	}

	/**
	 * Linear resampling from {@code fromRate} to {@code toRate}. Playing 24 kHz audio "as if" it were
	 * 30 kHz and resampling to the device rate is how Pomi's voice gets faster and higher (the mod's pitch).
	 */
	static byte[] resample(byte[] pcm, double fromRate, double toRate) {
		int in = pcm.length / 2;
		if (in == 0 || Math.abs(fromRate - toRate) < 1) {
			return pcm;
		}
		int out = (int) (in * toRate / fromRate);
		byte[] result = new byte[out * 2];
		double step = fromRate / toRate;
		for (int i = 0; i < out; i++) {
			double position = i * step;
			int a = Math.min((int) position, in - 1);
			int b = Math.min(a + 1, in - 1);
			double t = position - a;
			put(result, i, (int) Math.round(sample(pcm, a) * (1 - t) + sample(pcm, b) * t));
		}
		return result;
	}
}
