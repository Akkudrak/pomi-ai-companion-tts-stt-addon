package com.maine.pomivoz;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.kwhat.jnativehook.GlobalScreen;
import com.github.kwhat.jnativehook.NativeHookException;
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent;
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener;
import com.github.kwhat.jnativehook.mouse.NativeMouseEvent;
import com.github.kwhat.jnativehook.mouse.NativeMouseListener;

/**
 * The talk key, read globally (it works while Minecraft has the focus) with JNativeHook: X11 on
 * Linux, plus Windows and macOS. Wayland doesn't let apps read other windows' keys, so there it may
 * not be available; the "Pomi" wake word still works.
 */
final class TalkKey {
	/** Keys and mouse buttons held right now: "k:<code>" / "m:<button>". */
	private final Set<String> held = ConcurrentHashMap.newKeySet();
	private volatile Consumer<String> capture;
	private boolean available;
	private String problem = "";

	void start() {
		Logger hookLog = Logger.getLogger(GlobalScreen.class.getPackage().getName());
		hookLog.setLevel(Level.OFF);
		hookLog.setUseParentHandlers(false);
		try {
			GlobalScreen.registerNativeHook();
		} catch (NativeHookException | UnsatisfiedLinkError e) {
			problem = e.getMessage() == null ? e.toString() : e.getMessage();
			return;
		}
		available = true;
		GlobalScreen.addNativeKeyListener(new NativeKeyListener() {
			@Override
			public void nativeKeyPressed(NativeKeyEvent e) {
				pressed("k:" + e.getKeyCode(), e.getKeyCode() == NativeKeyEvent.VC_ESCAPE);
			}

			@Override
			public void nativeKeyReleased(NativeKeyEvent e) {
				held.remove("k:" + e.getKeyCode());
			}
		});
		GlobalScreen.addNativeMouseListener(new NativeMouseListener() {
			@Override
			public void nativeMousePressed(NativeMouseEvent e) {
				// The left and right buttons are used for everything: never a talk key.
				if (e.getButton() >= 3) {
					pressed("m:" + e.getButton(), false);
				}
			}

			@Override
			public void nativeMouseReleased(NativeMouseEvent e) {
				held.remove("m:" + e.getButton());
			}
		});
	}

	private void pressed(String id, boolean escape) {
		Consumer<String> waiting = capture;
		if (waiting != null) {
			capture = null;
			waiting.accept(escape ? "" : id);
			return;
		}
		held.add(id);
	}

	boolean available() {
		return available;
	}

	String problem() {
		return problem;
	}

	/** The next key or side button pressed anywhere goes to {@code then} ("" for Esc). */
	void captureNext(Consumer<String> then) {
		capture = then;
	}

	void cancelCapture() {
		capture = null;
	}

	boolean isDown(String key) {
		return !key.isEmpty() && held.contains(key);
	}

	static String name(String key) {
		if (key.startsWith("m:")) {
			return switch (key) {
				case "m:3" -> "el botón de en medio del mouse";
				case "m:4" -> "el botón lateral 1 del mouse";
				case "m:5" -> "el botón lateral 2 del mouse";
				default -> "el botón " + key.substring(2) + " del mouse";
			};
		}
		try {
			return NativeKeyEvent.getKeyText(Integer.parseInt(key.substring(2)));
		} catch (RuntimeException e) {
			return key;
		}
	}

	void stop() {
		if (available) {
			try {
				GlobalScreen.unregisterNativeHook();
			} catch (NativeHookException e) {
				// Closing anyway.
			}
		}
	}
}
