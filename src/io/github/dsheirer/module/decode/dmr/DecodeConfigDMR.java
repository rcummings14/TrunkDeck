/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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
 * Modified for TrunkDeck (2026): reduced to the settings the DMR message processor reads. The playlist (XML)
 * annotations, the JMBE / decoder-type base class, the traffic channel pool and the channel grant event, which belong
 * to SDRTrunk's channel framework, were removed.
 */
package io.github.dsheirer.module.decode.dmr;

import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import java.util.ArrayList;
import java.util.List;

/**
 * DMR decoder configuration
 */
public class DecodeConfigDMR
{
    private boolean mIgnoreDataCalls = true;
    private boolean mIgnoreCRCChecksums = false;
    private boolean mUseCompressedTalkgroups = false;
    private List<TimeslotFrequency> mTimeslotMap = new ArrayList<>();

    public DecodeConfigDMR()
    {
    }

    public boolean getIgnoreDataCalls()
    {
        return mIgnoreDataCalls;
    }

    public void setIgnoreDataCalls(boolean ignore)
    {
        mIgnoreDataCalls = ignore;
    }

    /**
     * Ignore CRC checksums. Some DMR systems use a non-standard CRC mask and setting this lets those messages through.
     */
    public boolean getIgnoreCRCChecksums()
    {
        return mIgnoreCRCChecksums;
    }

    public void setIgnoreCRCChecksums(boolean ignore)
    {
        mIgnoreCRCChecksums = ignore;
    }

    /**
     * Use compressed talkgroups (Hytera Tier III systems that compress radio and talkgroup values).
     */
    public boolean isUseCompressedTalkgroups()
    {
        return mUseCompressedTalkgroups;
    }

    public void setUseCompressedTalkgroups(boolean useCompressedTalkgroups)
    {
        mUseCompressedTalkgroups = useCompressedTalkgroups;
    }

    /**
     * Logical channel number to frequency map for Capacity Plus / Connect Plus / Tier III systems.
     */
    public List<TimeslotFrequency> getTimeslotMap()
    {
        return mTimeslotMap;
    }

    public void setTimeslotMap(List<TimeslotFrequency> timeslots)
    {
        mTimeslotMap = timeslots == null ? new ArrayList<>() : timeslots;
    }

    public void addTimeslotFrequency(TimeslotFrequency timeslotFrequency)
    {
        mTimeslotMap.add(timeslotFrequency);
    }
}
