/*
 * TrunkDeck compatibility shim for the SDRTrunk code in io.github.dsheirer (GPL-3.0-or-later).
 * Copyright (C) 2026 Ray Cummings.
 *
 * SDRTrunk logs through SLF4J. TrunkDeck does not put SLF4J on its own classpath, so the copied SDRTrunk files import
 * this small logger instead. Debug and info messages are dropped; warnings and errors go to {@link #sink}, which
 * TrunkDeck points at its diagnostic log (System.err until then).
 */
package io.github.dsheirer.compat;

import java.util.function.BiConsumer;

public final class Logger {
    /** Receives (logger name, message) for warnings and errors. */
    public static volatile BiConsumer<String, String> sink = (name, text) -> System.err.println(name + ": " + text);

    private final String name;

    Logger(String name) { this.name = name; }

    public boolean isDebugEnabled() { return false; }
    public void trace(String text, Object... args) {}
    public void debug(String text, Object... args) {}
    public void info(String text, Object... args) {}
    public void warn(String text, Object... args) { emit(text, args); }
    public void error(String text, Object... args) { emit(text, args); }

    private void emit(String text, Object[] args) {
        BiConsumer<String, String> s = sink;
        if (s != null) s.accept(name, format(text, args));
    }

    /** SLF4J style: each {} takes the next argument; a trailing Throwable is appended. */
    static String format(String text, Object[] args) {
        if (text == null) text = "null";
        if (args == null || args.length == 0) return text;
        StringBuilder sb = new StringBuilder();
        int a = 0, from = 0, at;
        while ((at = text.indexOf("{}", from)) >= 0 && a < args.length) {
            sb.append(text, from, at).append(args[a++]);
            from = at + 2;
        }
        sb.append(text.substring(from));
        if (a < args.length && args[args.length - 1] instanceof Throwable t) sb.append(" - ").append(t);
        return sb.toString();
    }
}
