package p25monitor.lan;

import java.net.*;
import java.util.*;

/**
 * Which computers may open the home-network page. Only private IPv4 (RFC 1918) and this computer
 * (loopback). A public address is refused, so a forwarded port is not an open scanner.
 */
public final class LanNet {
    private LanNet() {}

    public static boolean allowed(InetAddress a) {
        if (a == null) return false;
        if (a.isLoopbackAddress()) return true;
        byte[] b = a.getAddress();
        if (b.length == 16 && mappedV4(b)) b = Arrays.copyOfRange(b, 12, 16);
        if (b.length != 4) return false;
        int a0 = b[0] & 0xff, a1 = b[1] & 0xff;
        if (a0 == 10) return true;
        if (a0 == 172 && a1 >= 16 && a1 <= 31) return true;
        if (a0 == 192 && a1 == 168) return true;
        if (a0 == 127) return true;
        return false;
    }

    /** IPv4-mapped IPv6 (::ffff:x.x.x.x), which is how some stacks report an IPv4 peer. */
    static boolean mappedV4(byte[] b) {
        for (int i = 0; i < 10; i++) if (b[i] != 0) return false;
        return (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff;
    }

    /** Addresses another computer on this home network can type. Loopback is not included. */
    public static List<String> homeIpv4() {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> nics = NetworkInterface.getNetworkInterfaces();
            if (nics == null) return out;
            while (nics.hasMoreElements()) {
                NetworkInterface ni = nics.nextElement();
                try { if (!ni.isUp() || ni.isLoopback()) continue; } catch (SocketException e) { continue; }
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (a instanceof Inet4Address && allowed(a) && !a.isLoopbackAddress()) out.add(a.getHostAddress());
                }
            }
        } catch (SocketException ignored) {}
        return out;
    }
}
