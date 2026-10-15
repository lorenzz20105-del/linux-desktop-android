import app.desktop.VncClient;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Connects to a VNC server, waits for a settled frame, and writes it to a PNG. Args: host port password out.png */
public class VncShot {
    public static void main(String[] args) throws Exception {
        CountDownLatch resized = new CountDownLatch(1);
        final int[] size = new int[2];
        VncClient c = new VncClient(args[0], Integer.parseInt(args[1]), args[2], new VncClient.Listener() {
            public void onResize(int w, int h) { size[0] = w; size[1] = h; resized.countDown(); }
            public void onRect(int x, int y, int w, int h) { }
            public void onClosed(String r) { }
        });
        c.connect(5000);
        if (!resized.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("no size");
        Thread.sleep(Long.parseLong(args.length > 4 ? args[4] : "4000"));
        BufferedImage img = new BufferedImage(size[0], size[1], BufferedImage.TYPE_INT_RGB);
        synchronized (c) {
            img.setRGB(0, 0, size[0], size[1], c.pixels(), 0, size[0]);
        }
        ImageIO.write(img, "png", new File(args[3]));
        System.out.println("wrote " + args[3] + " " + size[0] + "x" + size[1]);
        c.close();
    }
}
