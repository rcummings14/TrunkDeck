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
 * Modified for TrunkDeck (2026): packet payloads (IP, LRRP, ARS, XCMP, Hytera SMS / RRS) are kept as undecoded packets; their parsers were not copied.
 */

package io.github.dsheirer.module.decode.dmr.message.data.packet;

import io.github.dsheirer.bits.BitSetFullException;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.dmr.message.data.block.DataBlock;
import io.github.dsheirer.module.decode.dmr.message.data.header.PacketSequenceHeader;
import io.github.dsheirer.module.decode.dmr.message.data.header.UDTHeader;
import io.github.dsheirer.module.decode.ip.DefinedShortDataPacket;
import io.github.dsheirer.module.decode.ip.UnknownPacket;
import java.util.ArrayList;
import java.util.List;
import io.github.dsheirer.compat.Logger;
import io.github.dsheirer.compat.LoggerFactory;

/**
 * Converts a DMR packet sequence into a message
 */
public class PacketSequenceMessageFactory
{
    private final static Logger mLog = LoggerFactory.getLogger(PacketSequenceMessageFactory.class);

    /**
     * Creates a message from a packet sequence
      * @param packetSequence with message parts
     * @return message
     */
    public static IMessage create(PacketSequence packetSequence)
    {
        if(packetSequence != null)
        {
            if(packetSequence.hasPacketSequenceHeader())
            {
                PacketSequenceHeader primaryHeader = packetSequence.getPacketSequenceHeader();
                CorrectedBinaryMessage packet = getPacket(packetSequence, primaryHeader.isConfirmedData());

                if(packet != null)
                {
                    switch(primaryHeader.getServiceAccessPoint())
                    {
                        case IP_PACKET_DATA:
                            return createIPPacketData(packetSequence, packet);
                        case PROPRIETARY_DATA:
                            return createProprietary(packetSequence, packet);
                        case SHORT_DATA:
                            return createDefinedShortData(packetSequence, packet);
                        default:
//                            mLog.info("Unknown Packet SAP: " + primaryHeader.getServiceAccessPoint() + " - returning unknown packet");
                            return new DMRPacketMessage(packetSequence, new UnknownPacket(packet, 0), packet,
                                    packetSequence.getTimeslot(), packetSequence.getPacketSequenceHeader().getTimestamp());
                    }
                }
            }
            else if(packetSequence.hasUDTHeader() && packetSequence.hasDataBlocks())
            {
                UDTHeader header = packetSequence.getUDTHeader();
                if(header.isShortData())
                {
                    CorrectedBinaryMessage payload = getUnconfirmedPayload(packetSequence.getDataBlocks());
                    return new UDTShortMessageService(header, payload);
                }
            }
        }

        return null;
    }

    /**
     * Assembles the unconfirmed payload bytes from each of the data blocks into a contiguous binary message.
     * @param dataBlocks to assemble
     * @return assembled unconfirmed payload.
     */
    public static CorrectedBinaryMessage getUnconfirmedPayload(List<DataBlock> dataBlocks)
    {
        if(!dataBlocks.isEmpty())
        {
            int length = 0;

            List<CorrectedBinaryMessage> fragments = new ArrayList<>();
            for(DataBlock dataBlock: dataBlocks)
            {
                CorrectedBinaryMessage fragment = dataBlock.getUnConfirmedPayload();
                length += fragment.size();
                fragments.add(fragment);
            }

            CorrectedBinaryMessage combined = new CorrectedBinaryMessage(length);
            int pointer = 0;
            for(CorrectedBinaryMessage fragment: fragments)
            {
                combined.load(pointer, fragment);
                pointer += fragment.size();
            }

            return combined;
        }

        return null;
    }

    /**
     * Creates a proprietary data message from the packet sequence
     * @param packetSequence containing proprietary message fragments
     * @param packet reassembled from the fragments
     * @return message
     */
    public static IMessage createProprietary(PacketSequence packetSequence, CorrectedBinaryMessage packet)
    {
        return new DMRPacketMessage(packetSequence, new UnknownPacket(packet, 0), packet,
            packetSequence.getTimeslot(), packetSequence.getPacketSequenceHeader().getTimestamp());
    }

    /**
     * Creates an IP packet from the packet sequence
     * @param packetSequence containing IP packet fragments
     * @param packet that is reassembled from the fragments
     * @return message
     */
    public static IMessage createIPPacketData(PacketSequence packetSequence, CorrectedBinaryMessage packet)
    {
        return new DMRPacketMessage(packetSequence, new UnknownPacket(packet, 0), packet,
            packetSequence.getTimeslot(), packetSequence.getPacketSequenceHeader().getTimestamp());
    }

    /**
     * Creates a Short Data packet from the packet sequence
     * @param packetSequence containin the short data sequence
     * @param packet with the reassembled contents
     * @return constructed DMR packet sequence message
     */
    public static IMessage createDefinedShortData(PacketSequence packetSequence, CorrectedBinaryMessage packet)
    {
        return new DMRPacketMessage(packetSequence, new DefinedShortDataPacket(packet, 0), packet,
            packetSequence.getTimeslot(), packetSequence.getPacketSequenceHeader().getTimestamp());
    }

    /**
     * Extracts and reassembles the packet contents from a Confirmed or Unconfirmed packet sequence.
     * @param sequence that contains a Header and Data Blocks
     * @param confirmed is true or unconfirmed is false
     * @return the extracted packet or null.
     */
    public static CorrectedBinaryMessage getPacket(PacketSequence sequence, boolean confirmed)
    {
        List<CorrectedBinaryMessage> fragments = new ArrayList<>();
        int length = 0;

        if(sequence.hasProprietaryDataHeader())
        {
            CorrectedBinaryMessage prefix = sequence.getProprietaryDataHeader().getPacketPrefix();

            if(prefix != null)
            {
                fragments.add(prefix);
                length += prefix.size();
            }
        }

        for(DataBlock dataBlock: sequence.getDataBlocks())
        {
            //TODO: check data block sequence numbers for confirmed payloads
            CorrectedBinaryMessage fragment = (confirmed ? dataBlock.getConfirmedPayload() : dataBlock.getUnConfirmedPayload());
            fragments.add(fragment);
            length += fragment.size();
        }

        if(length > 0)
        {
            CorrectedBinaryMessage packet = new CorrectedBinaryMessage(length);

            for(CorrectedBinaryMessage fragment: fragments)
            {
                for(int x = 0; x < fragment.size(); x++)
                {
                    try
                    {
                        packet.add(fragment.get(x));
                    }
                    catch(BitSetFullException bsfe)
                    {
                        //We should never get here
                        mLog.error("BitSet full while assembling packet fragments");
                        return packet;
                    }
                }
            }

//            mLog.info("Packet Bytes:" + packet.size() + " MSG:" + packet.toHexString());

            return packet;
        }

        return null;
    }

    /**
     * Inspects the first 7 bits of each data block to see if a contiguous data block sequence number pattern is
     * present.  This might indicate that the payload is a confirmed payload versus an unconfirmed payload when
     * the packet sequence is not yet identified.
     *
     * @param dataBlocks to inspect.
     * @return true if each of the data blocks have a sequential data block sequencing number.
     */
    public static boolean isConfirmedDataBlocks(List<DataBlock> dataBlocks)
    {
        int sequenceNumber = 0;

        for(DataBlock dataBlock: dataBlocks)
        {
            if(dataBlock.getDataBlockSerialNumber() == sequenceNumber)
            {
                sequenceNumber++;
            }
            else
            {
                return false;
            }
        }

        return true;
    }
}
