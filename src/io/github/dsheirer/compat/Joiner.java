/*
 * TrunkDeck compatibility shim for the SDRTrunk code in io.github.dsheirer (GPL-3.0-or-later).
 * Copyright (C) 2026 Ray Cummings.
 */
package io.github.dsheirer.compat;

import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/** Stands in for Guava's Joiner (only {@code Joiner.on(separator).join(...)} is used). */
public final class Joiner {
    private final String separator;

    private Joiner(String separator) { this.separator = separator; }

    public static Joiner on(String separator) { return new Joiner(separator); }

    public String join(Iterable<?> parts) {
        return StreamSupport.stream(parts.spliterator(), false).map(String::valueOf).collect(Collectors.joining(separator));
    }

    public String join(Object[] parts) { return Arrays.stream(parts).map(String::valueOf).collect(Collectors.joining(separator)); }

    public String join(int[] parts) { return Arrays.stream(parts).mapToObj(String::valueOf).collect(Collectors.joining(separator)); }
}
