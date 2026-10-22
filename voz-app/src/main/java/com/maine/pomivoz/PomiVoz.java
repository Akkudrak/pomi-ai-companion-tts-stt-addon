package com.maine.pomivoz;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.ByteArrayOutputStream;
import java.net.ConnectException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Pomi Voz in Java, for Linux (runs anywhere with Java 17+): reads out loud what Pomi says on the
 * server (plugin Pomi for Paper) and lets the player talk to her. The player types /p voz in the game
 * and enters the address and code here; the app stays linked. With "Con Pomi" it listens all the
 * time and sends what starts with "Pomi"; with a talk key it records while the key is held.
 * Same behaviour and settings as the Windows app (app/PomiVoz.cs).
 */
public final class PomiVoz {
	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
	/** If more lines than this pile up, the old ones are skipped so she doesn't fall behind. */
	private static final int MAX_QUEUED = 3;

	// Voice activity detection, as in the mod: 20 ms frames at 16 kHz.
	private static final int FRAME_MS = 20;
	private static final int FRAME_BYTES = Microphone.RATE * 2 * FRAME_MS / 1000;
	/** Audio from just before the voice starts, so the first syllable isn't cut. */
	private static final int PRE_ROLL_FRAMES = 15;
	/** Loud frames in a row that start an utterance (filters bumps and clicks). */
	private static final int START_FRAMES = 3;
	/** Silence that ends an utterance (0.7 s). */
	private static final int END_SILENCE_FRAMES = 35;
	private static final int MAX_FRAMES = 10_000 / FRAME_MS;
	private static final int MIN_FRAMES = 400 / FRAME_MS;
	/** Minimum level that counts as voice (besides 3 times the background noise). */
	private static final double MIN_THRESHOLD = 700;
	/** After Pomi talks, the mic is ignored this long (the speakers' echo). */
	private static final long ECHO_MILLIS = 400;
	/** The most recorded with the key: 15 s, what the server accepts. */
	private static final int MAX_KEY_BYTES = 15 * Microphone.RATE * 2;

	private final Settings settings = Settings.load();
	private final ServerLink server = new ServerLink();
	private final Speaker speaker = new Speaker();
	private final TalkKey talkKey = new TalkKey();
	private final LinkedBlockingDeque<long[]> queue = new LinkedBlockingDeque<>();

	private final JFrame frame = new JFrame("Pomi Voz");
	private final JTextField serverField = new JTextField(settings.server);
	private final JTextField codeField = new JTextField();
	private final JButton linkButton = new JButton("Vincular");
	private final JButton unlinkButton = new JButton("Desvincular");
	private final JLabel status = new JLabel(" ");
	private final JSpinner pitchSpinner = new JSpinner(new SpinnerNumberModel(settings.pitch, 0.8, 1.6, 0.05));
	private final JCheckBox muteBox = new JCheckBox("Silenciar", settings.muted);
	private final JSlider volumeSlider = new JSlider(0, 100, settings.volume);
	private final JComboBox<String> micBox = new JComboBox<>();
	private final JCheckBox listenBox = new JCheckBox("Con \"Pomi\"", settings.listening);
	private final JSlider gainSlider = new JSlider(0, 400, settings.gain);
	private final JLabel gainText = new JLabel(settings.gain + " %");
	private final JButton keyButton = new JButton();
	private final VoiceMeter meter = new VoiceMeter();
	private final JLabel hearState = new JLabel(" ");
	private final DefaultListModel<String> log = new DefaultListModel<>();

	/** Changes every time the link changes, so the old connection knows it's over. */
	private final AtomicInteger generation = new AtomicInteger();
	/** Utterances sent to Whisper that haven't come back. */
	private final AtomicInteger understanding = new AtomicInteger();
	private volatile boolean micChanged;
	private volatile boolean closing;
	private String shownHearState = "";

	public static void main(String[] args) {
		try {
			UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
		} catch (Exception e) {
			// The default look is fine.
		}
		SwingUtilities.invokeLater(() -> new PomiVoz().show());
	}

	private void show() {
		talkKey.start();
		buildWindow();
		daemon("Pomi Voz voz", this::speak);
		daemon("Pomi Voz oído", this::hear);
		if (!settings.token.isEmpty()) {
			connect();
		} else {
			setStatus("Escribe /p voz en el juego y pon aquí la dirección y el código.");
		}
		updateButtons();
		if (!talkKey.available()) {
			log("(la tecla para hablar no está disponible en esta sesión: " + talkKey.problem()
					+ ". En Wayland usa \"Con Pomi\" o entra a una sesión X11)");
		}
	}

	// ---- Window -------------------------------------------------------------------------------

	private void buildWindow() {
		JPanel grid = new JPanel(new GridBagLayout());
		grid.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
		int row = 0;

		add(grid, new JLabel("Servidor"), 0, row, 1);
		add(grid, serverField, 1, row++, 2);

		add(grid, new JLabel("Código"), 0, row, 1);
		add(grid, codeField, 1, row, 1);
		add(grid, linkButton, 2, row++, 1);
		linkButton.addActionListener(e -> link());
		codeField.addActionListener(e -> link());

		add(grid, status, 0, row, 2);
		add(grid, unlinkButton, 2, row++, 1);
		unlinkButton.addActionListener(e -> unlink("Desvinculada."));

		add(grid, new JLabel("Tono"), 0, row, 1);
		add(grid, pitchSpinner, 1, row, 1);
		add(grid, muteBox, 2, row++, 1);
		pitchSpinner.addChangeListener(e -> {
			settings.pitch = ((Number) pitchSpinner.getValue()).doubleValue();
			settings.save();
		});
		muteBox.addActionListener(e -> {
			settings.muted = muteBox.isSelected();
			settings.save();
		});

		add(grid, new JLabel("Volumen"), 0, row, 1);
		add(grid, volumeSlider, 1, row++, 2);
		volumeSlider.addChangeListener(e -> {
			settings.volume = volumeSlider.getValue();
			settings.save();
		});

		add(grid, new JLabel("Micrófono"), 0, row, 1);
		add(grid, micBox, 1, row, 1);
		add(grid, listenBox, 2, row++, 1);
		fillMicrophones();
		micBox.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
			@Override
			public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
				// Just plugged in (headset, USB) shows up when the list opens.
				fillMicrophones();
			}

			@Override
			public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
			}

			@Override
			public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
			}
		});
		micBox.addActionListener(e -> {
			if (!filling && micBox.getSelectedIndex() >= 0) {
				String chosen = micBox.getSelectedIndex() == 0 ? "" : (String) micBox.getSelectedItem();
				if (!chosen.equals(settings.microphone)) {
					settings.microphone = chosen;
					settings.save();
					micChanged = true;
				}
			}
		});
		listenBox.addActionListener(e -> {
			settings.listening = listenBox.isSelected();
			settings.save();
		});

		add(grid, new JLabel("Vol. mic"), 0, row, 1);
		add(grid, gainSlider, 1, row, 1);
		add(grid, gainText, 2, row++, 1);
		gainSlider.addChangeListener(e -> {
			settings.gain = gainSlider.getValue();
			gainText.setText(settings.gain + " %");
			settings.save();
		});

		add(grid, new JLabel("Tecla"), 0, row, 1);
		add(grid, keyButton, 1, row++, 2);
		keyButton.setText(keyText());
		keyButton.setEnabled(talkKey.available());
		keyButton.addActionListener(e -> {
			keyButton.setText("Presiona una tecla o botón lateral del mouse (Esc = ninguna)");
			talkKey.captureNext(key -> SwingUtilities.invokeLater(() -> {
				settings.key = key;
				settings.save();
				keyButton.setText(keyText());
			}));
		});

		GridBagConstraints wide = constraints(0, row++, 3);
		wide.fill = GridBagConstraints.BOTH;
		grid.add(meter, wide);

		hearState.setFont(hearState.getFont().deriveFont(Font.BOLD));
		add(grid, hearState, 0, row++, 3);

		JList<String> list = new JList<>(log);
		JScrollPane scroll = new JScrollPane(list);
		scroll.setPreferredSize(new Dimension(420, 200));
		GridBagConstraints rest = constraints(0, row, 3);
		rest.fill = GridBagConstraints.BOTH;
		rest.weighty = 1;
		grid.add(scroll, rest);

		frame.getContentPane().add(grid, BorderLayout.CENTER);
		frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowClosed(WindowEvent e) {
				closing = true;
				talkKey.stop();
				System.exit(0);
			}
		});
		frame.setMinimumSize(new Dimension(480, 640));
		frame.pack();
		frame.setLocationRelativeTo(null);
		frame.setVisible(true);
	}

	private static GridBagConstraints constraints(int x, int y, int span) {
		GridBagConstraints c = new GridBagConstraints();
		c.gridx = x;
		c.gridy = y;
		c.gridwidth = span;
		c.insets = new Insets(4, 4, 4, 4);
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.weightx = x == 1 ? 1 : 0;
		return c;
	}

	private static void add(JPanel grid, JComponent component, int x, int y, int span) {
		grid.add(component, constraints(x, y, span));
	}

	private boolean filling;

	private void fillMicrophones() {
		filling = true;
		try {
			micBox.removeAllItems();
			micBox.addItem("Predeterminado del sistema");
			List<String> devices = Microphone.devices();
			devices.forEach(micBox::addItem);
			if (settings.microphone.isEmpty()) {
				micBox.setSelectedIndex(0);
			} else {
				if (!devices.contains(settings.microphone)) {
					// Unplugged: stays in the list so it's clear which one it was; the default is used.
					micBox.addItem(settings.microphone);
				}
				micBox.setSelectedItem(settings.microphone);
			}
		} finally {
			filling = false;
		}
	}

	private String keyText() {
		if (!talkKey.available()) {
			return "Tecla no disponible en esta sesión";
		}
		return settings.key.isEmpty() ? "Sin tecla (clic para elegir una)"
				: "Mantén " + TalkKey.name(settings.key) + " para hablar (clic para cambiarla)";
	}

	private void setStatus(String text) {
		SwingUtilities.invokeLater(() -> status.setText("<html>" + text.replace("<", "&lt;") + "</html>"));
	}

	private void setHearState(String text, Color color) {
		if (text.equals(shownHearState)) {
			return;
		}
		shownHearState = text;
		SwingUtilities.invokeLater(() -> {
			hearState.setText(text);
			hearState.setForeground(color);
		});
	}

	/** A line for the window's list and the day's conversation file. */
	private void log(String text) {
		Transcript.append(text);
		String line = LocalTime.now().format(CLOCK) + "  " + text;
		SwingUtilities.invokeLater(() -> {
			log.add(0, line);
			while (log.size() > 100) {
				log.remove(log.size() - 1);
			}
		});
	}

	private static void daemon(String name, Runnable task) {
		Thread thread = new Thread(task, name);
		thread.setDaemon(true);
		thread.start();
	}

	private static String errorText(Exception e) {
		if (e instanceof ConnectException) {
			return "no se pudo conectar con el servidor";
		}
		return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
	}

	// ---- Link ---------------------------------------------------------------------------------

	private void link() {
		String address = serverField.getText().strip();
		String code = codeField.getText().strip().toUpperCase();
		if (address.isEmpty() || code.isEmpty()) {
			setStatus("Falta la dirección o el código. Los da /p voz en el juego.");
			return;
		}
		settings.server = address;
		linkButton.setEnabled(false);
		setStatus("Vinculando...");
		daemon("Pomi Voz vínculo", () -> {
			try {
				String[] answer = server.link(address, code);
				SwingUtilities.invokeLater(() -> {
					settings.token = answer[0];
					settings.player = answer[1];
					settings.save();
					codeField.setText("");
					connect();
					updateButtons();
				});
			} catch (Exception e) {
				setStatus("No se pudo vincular: " + errorText(e));
				SwingUtilities.invokeLater(this::updateButtons);
			}
		});
	}

	private void unlink(String why) {
		settings.token = "";
		settings.player = "";
		settings.save();
		generation.incrementAndGet();
		setStatus(why + " Escribe /p voz en el juego para vincular de nuevo.");
		updateButtons();
	}

	private void updateButtons() {
		linkButton.setEnabled(true);
		unlinkButton.setEnabled(!settings.token.isEmpty());
	}

	// ---- Pomi's lines -------------------------------------------------------------------------

	private void connect() {
		int mine = generation.incrementAndGet();
		String address = settings.server;
		String token = settings.token;
		daemon("Pomi Voz frases", () -> listen(mine, address, token));
	}

	private void listen(int mine, String address, String token) {
		while (!closing && mine == generation.get()) {
			setStatus("Conectando con " + address + "...");
			try {
				server.listen(address, token, (id, text) -> {
					if (mine == generation.get()) {
						setStatus("Conectada" + (settings.player.isEmpty() ? "" : " como " + settings.player) + ". Pomi habla aquí.");
						heard(id, text);
					}
				}, () -> !closing && mine == generation.get());
			} catch (ServerLink.ServerError e) {
				if (e.status == 401) {
					if (mine == generation.get()) {
						SwingUtilities.invokeLater(() -> unlink("El servidor ya no reconoce esta app."));
					}
					return;
				}
				setStatus("Sin conexión (" + errorText(e) + "). Reintentando...");
			} catch (Exception e) {
				setStatus("Sin conexión (" + errorText(e) + "). Reintentando...");
			}
			try {
				Thread.sleep(5000);
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	private void heard(long id, String text) {
		log("Pomi: " + text);
		while (queue.size() >= MAX_QUEUED) {
			queue.pollFirst();
		}
		queue.addLast(new long[] {id});
	}

	private void speak() {
		while (!closing) {
			long id;
			try {
				id = queue.takeFirst()[0];
			} catch (InterruptedException e) {
				return;
			}
			if (settings.muted || settings.token.isEmpty()) {
				continue;
			}
			try {
				byte[] pcm = server.audio(settings.server, settings.token, id);
				if (pcm.length >= 2) {
					speaker.playVoice(pcm, settings.pitch, settings.volume);
				}
			} catch (Exception e) {
				log("(sin voz: " + errorText(e) + ")");
			}
		}
	}

	// ---- Hearing (mic → /escuchar) ------------------------------------------------------------

	private void hear() {
		Microphone mic = null;
		byte[] frame = new byte[FRAME_BYTES];
		ArrayDeque<byte[]> preRoll = new ArrayDeque<>();
		ByteArrayOutputStream utterance = new ByteArrayOutputStream();
		ByteArrayOutputStream keyRecording = new ByteArrayOutputStream();
		boolean inSpeech = false;
		boolean recordingKey = false;
		int loud = 0;
		int quiet = 0;
		int frames = 0;
		double noise = 0;
		String micError = null;
		while (!closing) {
			// The mic is on for the name, the key, or both.
			boolean wanted = !settings.token.isEmpty() && (settings.listening || !settings.key.isEmpty());
			if (mic != null && (micChanged || !wanted)) {
				mic.close();
				mic = null;
				inSpeech = false;
				recordingKey = false;
				preRoll.clear();
				noise = 0;
				meter.clear();
			}
			if (micChanged) {
				micError = null;
			}
			micChanged = false;
			if (!wanted) {
				setHearState(settings.token.isEmpty() ? "Micrófono apagado: primero vincula la app."
						: "Micrófono apagado: activa \"Con Pomi\" o elige una tecla.", Color.GRAY);
				sleep(200);
				continue;
			}
			if (mic == null) {
				try {
					mic = new Microphone(settings.microphone, FRAME_BYTES);
					micError = null;
				} catch (Exception e) {
					if (!errorText(e).equals(micError)) {
						micError = errorText(e);
						log("(no se pudo abrir el micrófono: " + micError + ")");
					}
					setHearState("No se pudo abrir el micrófono.", new Color(178, 34, 34));
					sleep(3000);
					continue;
				}
			}
			mic.read(frame);
			Pcm.amplify(frame, frame.length, settings.gain / 100.0);
			double level = Pcm.rms(frame);
			double threshold = Math.max(MIN_THRESHOLD, noise * 3.0);

			// Talk key: record while held (even over Pomi's voice), send on release, no name needed.
			if (talkKey.isDown(settings.key)) {
				if (!recordingKey) {
					recordingKey = true;
					keyRecording.reset();
					inSpeech = false;
					preRoll.clear();
				}
				if (keyRecording.size() < MAX_KEY_BYTES) {
					keyRecording.write(frame, 0, frame.length);
				}
				meter.push(level, threshold, VoiceMeter.VOICE);
				setHearState("● Grabando... suelta la tecla para mandarlo.", new Color(34, 139, 34));
				continue;
			}
			if (recordingKey) {
				recordingKey = false;
				if (keyRecording.size() >= MIN_FRAMES * FRAME_BYTES) {
					sendInBackground(keyRecording.toByteArray(), true);
				}
				continue;
			}
			if (!settings.listening) {
				meter.push(level, threshold, VoiceMeter.NOISE);
				setHearState(understanding.get() > 0 ? "Entendiendo lo que dijiste..."
						: "Mantén " + TalkKey.name(settings.key) + " para hablarle a Pomi.", Color.DARK_GRAY);
				continue;
			}
			if (speaker.busy(ECHO_MILLIS)) {
				meter.push(level, threshold, VoiceMeter.POMI_TALKING);
				setHearState("Pomi está hablando (no te oigo mientras).", new Color(70, 130, 180));
				inSpeech = false;
				preRoll.clear();
				loud = 0;
				continue;
			}
			meter.push(level, threshold, inSpeech || level > threshold ? VoiceMeter.VOICE : VoiceMeter.NOISE);
			if (inSpeech) {
				setHearState("● Te oigo...", new Color(34, 139, 34));
			} else if (understanding.get() > 0) {
				setHearState("Entendiendo lo que dijiste...", new Color(255, 140, 0));
			} else {
				setHearState("Escuchando. Di \"Pomi, baila\" o \"Pomi\" y luego la orden.", Color.DARK_GRAY);
			}
			if (!inSpeech) {
				noise = noise == 0 ? level : noise * 0.98 + level * 0.02;
				preRoll.addLast(frame.clone());
				if (preRoll.size() > PRE_ROLL_FRAMES) {
					preRoll.removeFirst();
				}
				loud = level > threshold ? loud + 1 : 0;
				if (loud >= START_FRAMES) {
					inSpeech = true;
					utterance.reset();
					frames = preRoll.size();
					for (byte[] earlier : preRoll) {
						utterance.write(earlier, 0, earlier.length);
					}
					preRoll.clear();
					quiet = 0;
				}
			} else {
				utterance.write(frame, 0, frame.length);
				frames++;
				quiet = level < threshold * 0.6 ? quiet + 1 : 0;
				if (quiet >= END_SILENCE_FRAMES || frames >= MAX_FRAMES) {
					inSpeech = false;
					loud = 0;
					if (frames - quiet >= MIN_FRAMES) {
						sendInBackground(utterance.toByteArray(), false);
					}
				}
			}
		}
		if (mic != null) {
			mic.close();
		}
	}

	private void sendInBackground(byte[] said, boolean fromKey) {
		understanding.incrementAndGet();
		daemon("Pomi Voz entendiendo", () -> {
			try {
				send(said, fromKey);
			} finally {
				understanding.decrementAndGet();
			}
		});
	}

	/** Sends one utterance and shows what the server understood. With the key no name is needed. */
	private void send(byte[] pcm, boolean fromKey) {
		try {
			String[] answer = server.hear(settings.server, settings.token, pcm, fromKey);
			String state = answer[0];
			String text = answer[1];
			if (state.equals("escuchando")) {
				log("● Pomi te escucha... (" + text + ")");
				speaker.chime(settings.volume);
			} else if (state.equals("enviado")) {
				log("Tú: " + answer[2]);
			} else if (fromKey) {
				log(text.isEmpty() ? "(no te entendí: no se oyó nada claro, revisa el visor y el volumen del micrófono)"
						: "(oí \"" + text + "\", pero el servidor no lo tomó como orden: ¿tiene el plugin actualizado?)");
			} else if (!text.isEmpty()) {
				log("(oí \"" + text + "\", pero no empezó con Pomi)");
			}
		} catch (ServerLink.ServerError e) {
			log(e.status == 404 ? "(el plugin Pomi del servidor es una versión sin oído: actualízalo para usar órdenes de voz)"
					: "(no te entendí: " + errorText(e) + ")");
		} catch (Exception e) {
			log("(no te entendí: " + errorText(e) + ")");
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
