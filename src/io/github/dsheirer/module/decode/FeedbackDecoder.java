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
 * Modified for TrunkDeck (2026): no longer extends SDRTrunk's PrimaryDecoder / source event framework. The symbol
 * processor's frequency error goes to a plain listener (TrunkDeck's channel AFC) instead of a SourceEvent.
 */
package io.github.dsheirer.module.decode;

import java.util.function.DoubleConsumer;

/**
 * Decoder that sends demodulated symbols and frequency (PLL) error feedback to optional listeners.
 */
public class FeedbackDecoder
{
    private static final double TWO_PI = 2 * Math.PI;
    private DoubleConsumer mFrequencyCorrectionListener;
    private DoubleConsumer mSymbolListener;
    private double mSampleRate;

    /**
     * Sample rate of the demodulated stream, used to turn the PLL error (radians per sample) into Hertz.
     */
    protected void setDecimatedSampleRate(double rate)
    {
        mSampleRate = rate;
    }

    /**
     * Receives the carrier offset (Hz) that the symbol processor measures from the balance of the sync patterns.
     * Positive: the signal is above the channel centre.
     */
    public void setFrequencyCorrectionListener(DoubleConsumer listener)
    {
        mFrequencyCorrectionListener = listener;
    }

    /**
     * Processes a PLL error measurement (radians per sample) into a frequency correction request in Hertz.
     */
    public void processPLLError(float pllError)
    {
        DoubleConsumer l = mFrequencyCorrectionListener;
        if(l != null && mSampleRate > 0)
        {
            l.accept(mSampleRate / TWO_PI * pllError);
        }
    }

    public void setSymbolListener(DoubleConsumer listener)
    {
        mSymbolListener = listener;
    }

    /**
     * Sends a demodulated soft symbol to the optional symbol listener.
     */
    public void broadcast(float symbol)
    {
        DoubleConsumer l = mSymbolListener;
        if(l != null)
        {
            l.accept(symbol);
        }
    }
}
