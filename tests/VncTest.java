import app.desktop.VncClient;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs against a live x11vnc. Args: host port password expectedWidth expectedHeight.
 * Checks the handshake and VNC auth, the first full frame, a background pixel, and that input is accepted.
 */
public class VncTest {
    public static void main(String[] args) throws Exception {
        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String password = args[2];
        int expW = Integer.parseInt(args[3]);
        int expH = Integer.parseInt(args[4]);
        int expColour = (args.length > 5 && !args[5].equals("-")) ? Integer.parseInt(args[5], 16) : -1;

        CountDownLatch resized = new CountDownLatch(1);
        CountDownLatch frame = new CountDownLatch(1);
        final int[] size = new int[2];
        final String[] closed = new String[1];
        final boolean[] closedFlag = new boolean[1];

        VncClient client = new VncClient(host, port, password, new VncClient.Listener() {
            public void onResize(int w, int h) {
                size[0] = w;
                size[1] = h;
                resized.countDown();
            }
            public void onRect(int x, int y, int w, int h) {
                frame.countDown();
            }
            public void onClosed(String reason) {
                closed[0] = reason;
                closedFlag[0] = true;
            }
        });

        client.connect(5000);
        check(resized.await(10, TimeUnit.SECONDS), "got framebuffer size");
        check(size[0] == expW && size[1] == expH,
                "size " + size[0] + "x" + size[1] + " expected " + expW + "x" + expH);
        check(frame.await(15, TimeUnit.SECONDS), "got a rectangle update");
        check(client.isConnected(), "still connected after first frame");

        // Let the server finish sending the first full frame, then sample a pixel.
        Thread.sleep(1000);
        int sample;
        synchronized (client) {
            sample = client.pixels()[(client.height() - 1) * client.width() + (client.width() - 1)];
        }
        System.out.println("corner pixel = " + Integer.toHexString(sample));
        if (expColour != -1) {
            check((sample & 0xFFFFFF) == (expColour & 0xFFFFFF),
                    "corner colour " + Integer.toHexString(sample) + " expected " + Integer.toHexString(expColour));
        }

        // Input must be accepted and must not kill the connection.
        client.pointer(1, 100, 100);
        client.pointer(0, 100, 100);
        client.key(true, 'a');
        client.key(false, 'a');
        Thread.sleep(500);
        check(client.isConnected(), "connection survives input events");

        // A wrong password must be rejected cleanly.
        if (args.length > 6 && args[6].equals("check-bad-password")) {
            VncClient bad = new VncClient(host, port, "wrong-password", new VncClient.Listener() {
                public void onResize(int w, int h) { }
                public void onRect(int x, int y, int w, int h) { }
                public void onClosed(String reason) { }
            });
            boolean rejected = false;
            try {
                bad.connect(5000);
            } catch (java.io.IOException e) {
                rejected = true;
                System.out.println("bad password rejected: " + e.getMessage());
            }
            check(rejected, "wrong password is rejected");
        }

        client.close();
        System.out.println("ALL PASSED");
    }

    static void check(boolean ok, String what) {
        if (!ok) {
            System.out.println("FAIL: " + what);
            System.exit(1);
        }
        System.out.println("ok: " + what);
    }
}
