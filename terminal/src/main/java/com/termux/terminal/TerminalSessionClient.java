package com.termux.terminal;

/**
 * NOT UPSTREAM. Written for AIDE-OS to replace Termux's interface of the same
 * name; see {@code terminal/vendor/PROVENANCE.md}.
 *
 * <p>Upstream's version declares callbacks that each take a Termux
 * {@code TerminalSession}, which would drag in Termux's own process handling —
 * its {@code TerminalSession}, {@code JNI}, {@code ByteQueue} and a second
 * {@code termux.c}. This project already has that layer in
 * {@code com.osamu.aide.terminal}, tested on a device by spike R7, and running
 * two of them would be worse than writing this file.
 *
 * <p>It declares only what the vendored code actually calls, which is very
 * little: {@code TerminalEmulator} touches a client exactly twice, and
 * {@code Logger} forwards five log levels to it. Everything else the emulator
 * needs to say goes through {@link TerminalOutput}, which is vendored
 * unmodified and which this project implements.
 *
 * <p><b>Because this file exists, every vendored file is byte-identical to
 * upstream.</b> That is the point: it keeps the substitution in a file we own,
 * so a future update is a straight copy rather than a merge.
 */
public interface TerminalSessionClient {

    /**
     * The cursor style to use, or null for the emulator's default.
     *
     * <p>Called by {@code TerminalEmulator} when it resets.
     */
    Integer getTerminalCursorStyle();

    /** The shell asked for the cursor to be shown or hidden. */
    void onTerminalCursorStateChange(boolean state);

    void logError(String tag, String message);

    void logWarn(String tag, String message);

    void logInfo(String tag, String message);

    void logDebug(String tag, String message);

    void logVerbose(String tag, String message);
}
