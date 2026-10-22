package com.maine.pomivoz;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The plugin's Pomi Voz endpoints (see PomiVoiceLink in the plugin): link with a code, the stream of
 * Pomi's lines (Server-Sent Events), each line's audio, and what the player said.
 */
final class ServerLink {
	/** An HTTP error with the server's message (expired code, unknown token...). */
	static final class ServerError extends IOException {
		final int status;

		ServerError(int status, String message) {
			super(message.isBlank() ? "el servidor respondió " + status : message);
			this.status = status;
		}
	}

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

	static String baseUrl(String server) {
		String s = server.strip();
		while (s.endsWith("/")) {
			s = s.substring(0, s.length() - 1);
		}
		return s.startsWith("http://") || s.startsWith("https://") ? s : "http://" + s;
	}

	/** POST /vincular: returns {token, player}. */
	String[] link(String server, String code) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl(server) + "/vincular"))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "text/plain; charset=utf-8")
				.POST(HttpRequest.BodyPublishers.ofString(code, StandardCharsets.UTF_8))
				.build();
		String answer = text(http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
		return new String[] {field(answer, "token"), field(answer, "jugador")};
	}

	/**
	 * GET /eventos until the stream ends or {@code stillWanted} says stop. Each event goes to
	 * {@code onLine} as (id, text). The server pings every 20 s; a minute of silence means it's gone.
	 */
	void listen(String server, String token, BiConsumer<Long, String> onLine, java.util.function.BooleanSupplier stillWanted)
			throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl(server) + "/eventos"))
				.header("Authorization", "Bearer " + token)
				.GET()
				.build();
		HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
		if (response.statusCode() != 200) {
			throw new ServerError(response.statusCode(), new String(response.body().readAllBytes(), StandardCharsets.UTF_8).strip());
		}
		try (InputStream body = response.body();
				BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
			long[] lastRead = {System.currentTimeMillis()};
			Thread watchdog = new Thread(() -> {
				try {
					while (stillWanted.getAsBoolean() && System.currentTimeMillis() - lastRead[0] < 60_000) {
						Thread.sleep(500);
					}
					body.close();
				} catch (IOException | InterruptedException e) {
					// The stream is closing anyway.
				}
			}, "Pomi Voz vigía");
			watchdog.setDaemon(true);
			watchdog.start();
			try {
				long id = 0;
				StringBuilder data = new StringBuilder();
				String line;
				while (stillWanted.getAsBoolean() && (line = reader.readLine()) != null) {
					lastRead[0] = System.currentTimeMillis();
					if (line.isEmpty()) {
						if (id > 0 && data.length() > 0) {
							onLine.accept(id, data.toString());
						}
						id = 0;
						data.setLength(0);
					} else if (line.startsWith("id:")) {
						try {
							id = Long.parseLong(line.substring(3).strip());
						} catch (NumberFormatException e) {
							id = 0;
						}
					} else if (line.startsWith("data:")) {
						data.append(line.substring(5).stripLeading());
					}
				}
			} finally {
				watchdog.interrupt();
			}
		}
	}

	/** GET /audio/<id>: the line spoken by Kokoro, 24 kHz 16-bit mono PCM. */
	byte[] audio(String server, String token, long id) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl(server) + "/audio/" + id))
				.timeout(Duration.ofSeconds(30))
				.header("Authorization", "Bearer " + token)
				.GET()
				.build();
		HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
		if (response.statusCode() != 200) {
			throw new ServerError(response.statusCode(), new String(response.body(), StandardCharsets.UTF_8).strip());
		}
		return response.body();
	}

	/** POST /escuchar: 16 kHz 16-bit mono PCM of what the player said. Returns {estado, texto, orden}. */
	String[] hear(String server, String token, byte[] pcm, boolean fromKey) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl(server) + "/escuchar" + (fromKey ? "?tecla=1" : "")))
				.timeout(Duration.ofSeconds(30))
				.header("Authorization", "Bearer " + token)
				.header("Content-Type", "application/octet-stream")
				.POST(HttpRequest.BodyPublishers.ofByteArray(pcm))
				.build();
		String answer = text(http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
		return new String[] {field(answer, "estado"), field(answer, "texto"), field(answer, "orden")};
	}

	private static String text(HttpResponse<String> response) throws ServerError {
		if (response.statusCode() != 200) {
			throw new ServerError(response.statusCode(), response.body().strip());
		}
		return response.body();
	}

	/** A text field of a flat JSON object. */
	static String field(String json, String name) {
		Matcher match = Pattern.compile("\"" + Pattern.quote(name) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
		if (!match.find()) {
			return "";
		}
		String raw = match.group(1);
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c != '\\' || i + 1 >= raw.length()) {
				out.append(c);
				continue;
			}
			char next = raw.charAt(++i);
			switch (next) {
				case 'n' -> out.append('\n');
				case 't' -> out.append('\t');
				case 'u' -> {
					if (i + 4 < raw.length()) {
						out.append((char) Integer.parseInt(raw.substring(i + 1, i + 5), 16));
						i += 4;
					}
				}
				default -> out.append(next);
			}
		}
		return out.toString();
	}
}
