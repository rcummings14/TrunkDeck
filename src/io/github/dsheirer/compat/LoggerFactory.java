/*
 * TrunkDeck compatibility shim for the SDRTrunk code in io.github.dsheirer (GPL-3.0-or-later).
 * Copyright (C) 2026 Ray Cummings.
 */
package io.github.dsheirer.compat;

/** Stands in for org.slf4j.LoggerFactory in the copied SDRTrunk files. */
public final class LoggerFactory {
    private LoggerFactory() {}

    public static Logger getLogger(Class<?> c) { return new Logger("SDRTrunk " + c.getSimpleName()); }
}
