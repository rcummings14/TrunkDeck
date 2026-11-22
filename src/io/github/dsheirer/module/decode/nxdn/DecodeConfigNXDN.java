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
 * Modified for TrunkDeck (2026): reduced to the settings the decoder reads (transmission mode, channel map, talker
 * alias encoding and the baseband filter band edges); no SDRTrunk configuration framework, Jackson or JMBE settings.
 */
package io.github.dsheirer.module.decode.nxdn;

import io.github.dsheirer.module.decode.nxdn.channel.ChannelFrequency;
import io.github.dsheirer.module.decode.nxdn.layer3.proprietary.Encoding;
import io.github.dsheirer.module.decode.nxdn.layer3.type.TransmissionMode;
import java.util.ArrayList;
import java.util.List;

/**
 * NXDN decoder configuration
 */
public class DecodeConfigNXDN
{
    private TransmissionMode mTransmissionMode = TransmissionMode.M4800;
    private List<ChannelFrequency> mChannelMap = new ArrayList<>();
    private Encoding mEncoding = Encoding.UTF8;

    public DecodeConfigNXDN()
    {
    }

    public DecodeConfigNXDN(TransmissionMode transmissionMode)
    {
        mTransmissionMode = transmissionMode;
    }

    public List<ChannelFrequency> getChannelMap()
    {
        return mChannelMap;
    }

    public void add(ChannelFrequency channelFrequency)
    {
        mChannelMap.add(channelFrequency);
    }

    public TransmissionMode getTransmissionMode()
    {
        return mTransmissionMode;
    }

    public Encoding getEncoding()
    {
        return mEncoding;
    }

    public void setEncoding(Encoding encoding)
    {
        mEncoding = encoding;
    }

    /** Baseband filter pass band edge in Hz (SDRTrunk's ChannelSpecification for this mode). */
    public double getPassFrequency()
    {
        return mTransmissionMode == TransmissionMode.M9600 ? 5200.0 : 3000.0;
    }

    /** Baseband filter stop band edge in Hz. */
    public double getStopFrequency()
    {
        return mTransmissionMode == TransmissionMode.M9600 ? 6250.0 : 3125.0;
    }
}
