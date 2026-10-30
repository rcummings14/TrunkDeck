package p25monitor.nativeengine;
public final class P2Main {
    public static void main(String[] a) throws Exception {
        if (System.getProperty("jmbe") != null) EngineLibs.loadJmbe(java.nio.file.Path.of(System.getProperty("jmbe")));
        int[][] rs = {P2Tests.fec(), P2Tests.realCapture(), P2Tests.twoSlots(false, 0, 0), P2Tests.twoSlots(true, 0, 0.05), P2Tests.twoSlots(true, 400, 0.2), P2Tests.twoSlots(true, -700, 0.3), P2Tests.channel(), P2Tests.cut()};
        int p = 0, f = 0; for (int[] r : rs) { p += r[0]; f += r[1]; }
        System.out.println("pass " + p + " fail " + f);
    }
}
