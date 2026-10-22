package com.maine.pomivoz;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The day's conversation: "conversacion-2026-10-19.txt" next to PomiVoz.jar (or, if that folder
 * isn't writable, in the settings folder). Lines are always appended, never overwritten.
 */
final class Transcript {
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

	private Transcript() {
	}

	static synchronized void append(String text) {
		LocalDateTime now = LocalDateTime.now();
		String name = "conversacion-" + now.format(DAY) + ".txt";
		byte[] line = (now.format(TIME) + "  " + text + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
		for (Path folder : new Path[] {jarFolder(), Settings.folder()}) {
			if (folder == null) {
				continue;
			}
			try {
				Files.createDirectories(folder);
				Files.write(folder.resolve(name), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				return;
			} catch (IOException e) {
				// Try the next folder.
			}
		}
	}

	private static Path jarFolder() {
		try {
			Path jar = Path.of(Transcript.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			return Files.isDirectory(jar) ? jar : jar.getParent();
		} catch (URISyntaxException | RuntimeException e) {
			return null;
		}
	}
}
