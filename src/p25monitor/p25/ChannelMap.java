package p25monitor.p25;

import java.util.concurrent.ConcurrentHashMap;

/** Band plan learned from identifier-update TSBKs: channel number -> downlink frequency, FDMA vs TDMA. */
public final class ChannelMap {
    public static final class Band {
        public int id, slots = 1, type = -1;
        public long baseHz, spacingHz, txOffsetHz, bandwidthHz;

        public static Band from(Tsbk t) {
            Band b = new Band();
            b.id = (int) t.bits(16, 4);
            b.spacingHz = t.bits(38, 10) * 125;
            b.baseHz = t.bits(48, 32) * 5;
            if (t.opcode == Tsbk.IDEN_UP) {
                b.bandwidthHz = t.bits(20, 9) * 125;
                long off = t.bits(30, 8) * 250000;
                b.txOffsetHz = t.bits(29, 1) == 1 ? off : -off;
            } else {
                long off = t.bits(25, 13) * b.spacingHz;
                b.txOffsetHz = t.bits(24, 1) == 1 ? off : -off;
                if (t.opcode == Tsbk.IDEN_UP_TDMA) {
                    b.type = (int) t.bits(20, 4);
                    b.slots = switch (b.type) { case 3, 5 -> 2; case 4 -> 4; default -> 1; };
                    b.bandwidthHz = b.type == 4 ? 25000 : b.type == 2 ? 6250 : 12500;
                } else {
                    int bw = (int) t.bits(20, 4);
                    b.bandwidthHz = bw == 4 ? 6250 : bw == 5 ? 12500 : 0;
                }
            }
            return b;
        }
    }

    final ConcurrentHashMap<Integer, Band> bands = new ConcurrentHashMap<>();

    public void update(Band b) { bands.put(b.id, b); }
    public Band band(int channel) { return bands.get(channel >> 12); }
    public java.util.Collection<Band> bands() { return bands.values(); }

    /** Downlink frequency in Hz, or 0 if the band has not been announced yet. */
    public long frequency(int channel) {
        Band b = band(channel);
        if (b == null) return 0;
        int num = channel & 0xfff;
        return b.baseHz + b.spacingHz * (num / Math.max(1, b.slots));
    }

    public boolean isTdma(int channel) { Band b = band(channel); return b != null && b.slots > 1; }
    public int timeslot(int channel) { Band b = band(channel); return b == null || b.slots <= 1 ? 0 : (channel & 0xfff) % b.slots; }
}
