/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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
 * Modified for TrunkDeck (2026): always the scalar detector; the Vector API variants and their calibration were left out.
 */

package io.github.dsheirer.module.decode.dmr.sync;

import io.github.dsheirer.compat.Logger;
import io.github.dsheirer.compat.LoggerFactory;

/**
 * Factory for creating DMR Soft Sync Detectors
 */
public class DMRSoftSyncDetectorFactory
{
    private static final Logger mLog = LoggerFactory.getLogger(DMRSoftSyncDetectorFactory.class);

    /**
     * Creates the implementation using calibration data to select the optimal
     * implementation from scalar and vector options.
     * @return demodulator instance
     */
    public static DMRSoftSyncDetector getDetector()
    {
        return new DMRSoftSyncDetectorScalar();
    }
}
