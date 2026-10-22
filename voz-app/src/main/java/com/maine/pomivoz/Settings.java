package com.maine.pomivoz;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * What the app remembers between sessions, in the same format as the Windows app: config.txt in
 * ~/.config/PomiVoz (Linux), %APPDATA%\PomiVoz (Windows) or ~/Library/Application Support/PomiVoz.
 */
final class Settings {
	String server = "";
	String token = "";
	String player = "";
	double pitch = 1.25;
	int volume = 80;
	boolean muted;
	boolean listening = true;
	/** Microphone name; empty = the system default. */
	String microphone = "";
	/** Microphone volume in %, applied to the samples (100 = as is). */
	int gain = 100;
	/** Talk key: "" = none, "k:<code>" = keyboard, "m:<button>" = mouse button (JNativeHook codes). */
	String key = "";

	static Path folder() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String home = System.getProperty("user.home");
		if (os.contains("win") && System.getenv("APPDATA") != null) {
			return Path.of(System.getenv("APPDATA"), "PomiVoz");
		}
		if (os.contains("mac")) {
			return Path.of(home, "Library", "Application Support", "PomiVoz");
		}
		String xdg = System.getenv("XDG_CONFIG_HOME");
		return (xdg == null || xdg.isBlank() ? Path.of(home, ".config") : Path.of(xdg)).resolve("PomiVoz");
	}

	private static Path file() {
		return folder().resolve("config.txt");
	}

	static Settings load() {
		Settings s = new Settings();
		List<String> lines;
		try {
			lines = Files.readAllLines(file(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			return s;
		}
		for (String line : lines) {
			int eq = line.indexOf('=');
			if (eq <= 0) {
				continue;
			}
			String key = line.substring(0, eq).strip().replace("﻿", "");
			String value = line.substring(eq + 1).strip();
			try {
				switch (key) {
					case "servidor" -> s.server = value;
					case "token" -> s.token = value;
					case "jugador" -> s.player = value;
					case "tono" -> s.pitch = Math.max(0.8, Math.min(1.6, Double.parseDouble(value)));
					case "volumen" -> s.volume = Math.max(0, Math.min(100, Integer.parseInt(value)));
					case "silencio" -> s.muted = value.equals("true");
					case "escuchar" -> s.listening = !value.equals("false");
					case "microfono" -> s.microphone = value;
					case "ganancia" -> s.gain = Math.max(0, Math.min(400, Integer.parseInt(value)));
					case "tecla" -> s.key = value.startsWith("k:") || value.startsWith("m:") ? value : "";
					default -> {
					}
				}
			} catch (NumberFormatException e) {
				// Hand-edited value: keep the default.
			}
		}
		return s;
	}

	synchronized void save() {
		try {
			Files.createDirectories(folder());
			Files.write(file(), List.of(
					"servidor=" + server,
					"token=" + token,
					"jugador=" + player,
					"tono=" + String.format(Locale.ROOT, "%.2f", pitch),
					"volumen=" + volume,
					"silencio=" + muted,
					"escuchar=" + listening,
					"microfono=" + microphone,
					"ganancia=" + gain,
					"tecla=" + key), StandardCharsets.UTF_8);
		} catch (IOException e) {
			System.err.println("No se pudo guardar la configuración: " + e);
		}
	}
}
