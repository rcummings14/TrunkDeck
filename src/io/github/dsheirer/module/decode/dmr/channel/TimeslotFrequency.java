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
 * Modified for TrunkDeck (2026): the JavaFX properties, the JavaFX extractor and the Jackson annotations (used only by
 * SDRTrunk's playlist editor and XML files) were replaced with plain fields. The values and their meaning are unchanged.
 */
package io.github.dsheirer.module.decode.dmr.channel;

/**
 * Logical channel number (LCN / LSN) to downlink and uplink frequency.
 */
public class TimeslotFrequency
{
    private int mNumber;
    private long mDownlinkFrequency;
    private long mUplinkFrequency;
    private String mDescription;

    public TimeslotFrequency()
    {
    }

    public TimeslotFrequency copy()
    {
        TimeslotFrequency copy = new TimeslotFrequency();
        copy.setNumber(getNumber());
        copy.setDownlinkFrequency(getDownlinkFrequency());
        copy.setUplinkFrequency(getUplinkFrequency());
        return copy;
    }

    public int getNumber()
    {
        return mNumber;
    }

    public int getChannelNumber()
    {
        return getNumber();
    }

    public void updateDescription()
    {
        StringBuilder sb = new StringBuilder();
        int channel = getChannelNumber();

        if(channel > 0)
        {
            if(channel < 32) //Connect Plus uses 5-bits for LSN for a maximum value of 31
            {
                //Cap+ & Con+ Logical Slot Numbers
                int lsn1 = (channel - 1) * 2 + 1;
                int lsn2 = (channel - 1) * 2 + 2;
                sb.append("LSN:").append(lsn1).append("/").append(lsn2).append(" or ");
            }

            //Tier III channel IDs
            int chanId1 = channel * 2 + 1;
            int chanId2 = channel * 2 + 2;
            sb.append("Tier3 Chan:").append(chanId1).append("/").append(chanId2);
        }
        else
        {
            sb.append("(empty)");
        }

        setDescription(sb.toString());
    }

    public String getDescription()
    {
        return mDescription;
    }

    public void setDescription(String description)
    {
        mDescription = description;
    }

    public void setNumber(int lcn)
    {
        mNumber = lcn;
        updateDescription();
    }

    public long getDownlinkFrequency()
    {
        return mDownlinkFrequency;
    }

    public void setDownlinkFrequency(long downlinkFrequency)
    {
        mDownlinkFrequency = downlinkFrequency;
    }

    public long getUplinkFrequency()
    {
        return mUplinkFrequency;
    }

    public void setUplinkFrequency(long uplinkFrequency)
    {
        mUplinkFrequency = uplinkFrequency;
    }

    @Override
    public String toString()
    {
        return "LCN:" + getNumber() + " DOWNLINK:" + getDownlinkFrequency();
    }
}
