/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 *
 * Modified for TrunkDeck (2026): SLF4J imports changed to io.github.dsheirer.compat. The decoding logic is unchanged.
 */

package io.github.dsheirer.module.decode.nxdn.layer1.sync;


/**
 * Factory for creating NXDN Soft Sync Detectors
 */
public class NXDNSyncDetectorFactory
{
    /**
     * Creates the implementation using calibration data to select the optimal implementation from scalar and vector options.
     * @return demodulator instance
     */
    public static NXDNSoftSyncDetector getStandardDetector()
    {
        return new NXDNSoftSyncDetectorScalar();
    }
}
