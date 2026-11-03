package p25monitor.nativeengine;

import p25monitor.dsp.PolyphaseBank;
import p25monitor.p25.P25Framer;
import p25monitor.sdr.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * End-to-end live path through the real SoapySDR library and a mock driver module (test/mocksoapy): the real SoapySdr
 * class (open, gains, CS16 readStream, reader + decoder threads) -> the wideband filter bank -> ChannelDecoder, as the
 * engine does for a wideband tuner, and (narrow) a full-rate ChannelDecoder. Scores the IMBE voice frames against what
 * was transmitted. Run with -Dtrunkdeck.libsoapysdr=..., MOCK_SOAPY_FILE (MakeCs16) and -Ddriver=hackrf|sdrplay|lime|uhd.
 */
public class MockSoapyLive {
    @SuppressWarnings("unchecked")
    public static void main(String[] a) throws Exception {
        double secs = Double.parseDouble(System.getProperty("secs", "6")), rate = Double.parseDouble(System.getProperty("rate", "2500000"));
        long off = Long.parseLong(System.getProperty("off", "350000"));
        SoapyDriver drv = SoapyDriver.of(System.getProperty("driver", "hackrf"));
        String file = System.getenv("MOCK_SOAPY_FILE");
        Set<String> want = new HashSet<>();
        if (file != null) for (byte[] s : (List<byte[]>) new ObjectInputStream(new FileInputStream(file + ".sent")).readObject()) want.add(Arrays.toString(s));
        String jm = System.getProperty("jmbe"); if (jm != null && !jm.isBlank()) EngineLibs.loadJmbe(Path.of(jm));
        SoapySdr.Info info = null;
        for (SoapySdr.Info i : SoapySdr.list()) if (i.driver == drv) { info = i; break; }
        if (info == null) { System.out.println("no " + drv + " receiver found"); System.exit(2); }
        SoapySdr d = new SoapySdr(info);
        d.open(rate, System.getProperty("gain", "default"), 0, false, "");
        long center = 460_000_000L; d.tune(center);
        long[] wide = new long[2], narrow = new long[2];   // frames, bit-exact
        boolean wb = PolyphaseBank.supports(d.sampleRate());
        PolyphaseBank bank = wb ? new PolyphaseBank(d.sampleRate()) : null;
        long f = center + off;
        ChannelDecoder wideDec = wb ? new ChannelDecoder(bank.outRate, center + bank.bandCentre(off), f, listener(want, wide), 0) : null;
        int band = wb ? bank.band(off) : 0;
        boolean[] need = wb ? new boolean[bank.M] : null; if (wb) need[band] = true;
        boolean doNarrow = Boolean.parseBoolean(System.getProperty("narrow", "true"));
        ChannelDecoder narrowDec = doNarrow ? new ChannelDecoder(d.sampleRate(), center, f, listener(want, narrow), 0) : null;
        long[] gaps = new long[1];
        d.start(new IqSource.Listener() {
            public void samples(float[] i, float[] q, int n) {
                if (wb) { bank.process(i, q, n, need); wideDec.process(bank.outI[band], bank.outQ[band], bank.outN); }
                if (narrowDec != null) narrowDec.process(i, q, n);
            }
            public void gap() { gaps[0]++; if (wideDec != null) wideDec.gap(); if (narrowDec != null) narrowDec.gap(); if (bank != null) bank.reset(); }
        });
        Thread.sleep((long) (secs * 1000));
        String err = d.error();
        d.stop();
        System.out.printf(Locale.ROOT, "%s: %.2f MS/s; wideband %d IMBE frames, %.1f%% bit-exact; full-rate %d frames, %.1f%% bit-exact; blocks %d, dropped %d, overflows %d, read errors %d, gaps %d, queue peak %d, clip %.4f, peak %.1f dBFS; config: %s; error: %s%n",
                drv.key, d.sampleRate() / 1e6, wide[0], 100.0 * wide[1] / Math.max(1, wide[0]), narrow[0], 100.0 * narrow[1] / Math.max(1, narrow[0]),
                d.blocks, d.droppedBlocks, d.overflows, d.readErrors, gaps[0], d.queuePeak, d.clipFraction, d.peakDb, d.configReport, err);
        for (String m; (m = SoapySdr.driverLog.poll()) != null; ) System.out.println("driverlog: " + m);
        System.exit(0);
    }

    static P25Framer.Listener listener(Set<String> want, long[] st) {
        return new P25Framer.Listener() {
            public void ldu(int nac, int duid, byte[][] f, P25Framer.LinkControl lc, P25Framer.EncryptionSync e) {
                for (byte[] x : f) { st[0]++; if (want.contains(Arrays.toString(x))) st[1]++; }
            }
        };
    }
}
