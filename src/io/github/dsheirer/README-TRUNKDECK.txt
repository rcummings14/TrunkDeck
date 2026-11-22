SDRTrunk source code included in TrunkDeck
==========================================

The Java files under src/io/github/dsheirer are taken from SDRTrunk by Dennis Sheirer:

  https://github.com/DSheirer/sdrtrunk
  copied from the master branch at commit b177fd77ae15e8bc7dc7352458b231a0b319ae2b (November 2026)
  Copyright (C) 2014-2026 Dennis Sheirer
  Licence: GNU General Public License, version 3 or (at your option) any later version - the same licence as
  TrunkDeck. The full text is licenses/GPL-3.0.txt. Each file keeps its original copyright and licence header.

What it is used for
-------------------
TrunkDeck's DMR and NXDN decoders. src/p25monitor/dmr/DmrDecoder.java and src/p25monitor/nxdn/NxdnDecoder.java feed
them the channel's 48 kHz samples and turn their messages into TrunkDeck's call, grant and audio events.

What was copied
---------------
Only the DMR and NXDN decoding chains and what they need, with SDRTrunk's package names kept:
  module/decode/dmr          symbol processor, burst framer, message processor, CRC mask manager, sync detection,
                             BPTC, channels, identifiers and all DMR message classes (CSBK, link control, headers,
                             voice, data blocks, packet framing)
  module/decode/nxdn         symbol processor, frame framer, message processor, layer 1 sync detection, layer 2
                             (LICH, SACCH assembly, scrambler, interleaver), layer 3 messages and their coding (CAC,
                             FACCH1, SACCH, convolutional code, CRC), channels and identifiers
  dsp                        FM demodulator, FIR / half-band / Remez filter design, 4FSK (C4FM) symbol processor and
                             equalizer, window functions
  edac, bits, identifier, message, protocol, sample, map  error correction, bit fields and the shared message types
Not copied: SDRTrunk's user interface, tuners, audio, playlists, event logging, channel / traffic channel managers,
the NXDN message filters,
the IP / LRRP / ARS / Hytera packet payload parsers, and the Vector API (SIMD) variants of the DSP classes.

Changes made for TrunkDeck
--------------------------
Every changed file says so in its header ("Modified for TrunkDeck (2026): ..."). In short:
  * Imports of SLF4J, Apache Commons Lang / Math and Guava now point to io.github.dsheirer.compat, small stand-ins
    written for TrunkDeck (logger, Joiner, Validate, Mean / Variance / StandardDeviation, natural cubic spline), so
    TrunkDeck still needs no extra libraries. FastMath calls use java.lang.Math.
  * The factories that chose a Vector API (SIMD) implementation return the scalar implementation; the vector classes
    were not copied (the Vector API is an incubator module TrunkDeck does not enable).
  * FilterFactory: the FFT-designed filters (JTransforms) and the polyphase channelizer filter were removed.
  * FeedbackDecoder, DecodeConfigDMR and DecodeConfigNXDN were reduced to what the decoders use (no SDRTrunk
    channel framework).
  * NXDNSymbolProcessor: sync thresholds lowered (37 / 40 to 33 / 34) so transmitters at 80-90% of nominal deviation
    still sync.
  * NXDNMessageProcessor: preload() (traffic channel framework) removed. ChannelFrequency: Jackson annotations
    removed. Convolution: the table generator (Commons Lang) removed. jspecify @NonNull annotations removed.
  * TimeslotFrequency: JavaFX properties and Jackson annotations replaced with plain fields.
  * C4FMSymbolProcessor: the JavaFX sync debug viewer replaced with a no-op.
  * VoiceMessage, Audio: getCorrectedAMBEFrames() / getCorrectedAudioFrames() (JMBE API) removed; TrunkDeck
    corrects the AMBE frames itself.
  * PacketSequenceMessageFactory: packet payloads are kept undecoded.
  * Benchmark main() methods that used the vector classes were removed.
  * These small interfaces / classes were copied unchanged so the DMR classes compile: channel/IChannelDescriptor,
    channel/IBandChannelDescriptor, module/decode/p25/phase1/message/IFrequencyBand,
    gui/viewer/sync/ISyncResultsListener, module/decode/ip/{IPacket, IHeader, UnknownPacket, DefinedShortDataPacket},
    module/decode/ip/mototrbo/xcmp/XCMPMessageType.
The files in io/github/dsheirer/compat are TrunkDeck's own (Copyright (C) 2026 Ray Cummings, GPL-3.0-or-later).
