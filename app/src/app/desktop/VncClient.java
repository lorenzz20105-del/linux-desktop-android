package app.desktop;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal RFB 3.8 client: VNC authentication (type 2) or None (type 1),
 * 32-bit true-colour pixels, Raw and DesktopSize encodings, incremental updates.
 * No Android dependencies, so it can be tested on the host with plain Java.
 */
public class VncClient {

    public interface Listener {
        /** Framebuffer size is known or changed. Called on the reader thread. */
        void onResize(int width, int height);
        /** A rectangle of the framebuffer was updated. Called on the reader thread. */
        void onRect(int x, int y, int w, int h);
        /** Connection ended. reason is null on a clean close. */
        void onClosed(String reason);
    }

    private static final int ENC_RAW = 0;
    private static final int ENC_DESKTOP_SIZE = -223;

    private final String host;
    private final int port;
    private final String password;
    private final Listener listener;

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private volatile boolean running;

    private int width;
    private int height;
    private int[] pixels = new int[0];
    private String name = "";

    public VncClient(String host, int port, String password, Listener listener) {
        this.host = host;
        this.port = port;
        this.password = password == null ? "" : password;
        this.listener = listener;
    }

    /** Connects, handshakes, and starts the reader thread. Throws on handshake failure. */
    public void connect(int timeoutMs) throws IOException {
        socket = new Socket();
        socket.setTcpNoDelay(true);
        socket.connect(new InetSocketAddress(host, port), timeoutMs);
        socket.setSoTimeout(0);
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1 << 16));
        out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 1 << 16));
        handshake();
        running = true;
        Thread reader = new Thread(this::readLoop, "vnc-reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void handshake() throws IOException {
        byte[] ver = new byte[12];
        in.readFully(ver);
        String serverVer = new String(ver, StandardCharsets.US_ASCII);
        if (!serverVer.startsWith("RFB ")) {
            throw new IOException("Not an RFB server: " + serverVer.trim());
        }
        out.write("RFB 003.008\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();

        int nTypes = in.readUnsignedByte();
        if (nTypes == 0) {
            throw new IOException("Server refused: " + readReason());
        }
        byte[] types = new byte[nTypes];
        in.readFully(types);
        int chosen = -1;
        for (byte t : types) {
            if (t == 2) { chosen = 2; break; }
        }
        if (chosen < 0) {
            for (byte t : types) {
                if (t == 1) { chosen = 1; break; }
            }
        }
        if (chosen < 0) {
            throw new IOException("No supported security type");
        }
        out.writeByte(chosen);
        out.flush();

        if (chosen == 2) {
            byte[] challenge = new byte[16];
            in.readFully(challenge);
            out.write(desEncryptChallenge(password, challenge));
            out.flush();
        }
        int result = in.readInt();
        if (result != 0) {
            throw new IOException(chosen == 2 ? "Wrong VNC password" : "Security handshake failed: " + readReason());
        }

        out.writeByte(1); // ClientInit: shared
        out.flush();

        width = in.readUnsignedShort();
        height = in.readUnsignedShort();
        skipFully(16); // server pixel format, replaced below
        int nameLen = in.readInt();
        byte[] nb = new byte[nameLen];
        in.readFully(nb);
        name = new String(nb, StandardCharsets.UTF_8);

        // SetPixelFormat: 32 bpp, depth 24, little-endian, true colour, R=16 G=8 B=0.
        out.writeByte(0);
        out.write(new byte[3]);
        out.writeByte(32);
        out.writeByte(24);
        out.writeByte(0);
        out.writeByte(1);
        out.writeShort(255);
        out.writeShort(255);
        out.writeShort(255);
        out.writeByte(16);
        out.writeByte(8);
        out.writeByte(0);
        out.write(new byte[3]);

        // SetEncodings: Raw, DesktopSize.
        out.writeByte(2);
        out.writeByte(0);
        out.writeShort(2);
        out.writeInt(ENC_RAW);
        out.writeInt(ENC_DESKTOP_SIZE);
        out.flush();

        allocate(width, height);
        listener.onResize(width, height);
        requestUpdate(false);
    }

    private String readReason() throws IOException {
        int len = in.readInt();
        byte[] b = new byte[Math.min(len, 4096)];
        in.readFully(b);
        return new String(b, StandardCharsets.UTF_8);
    }

    /** Skips exactly n bytes. DataInputStream.skipBytes may skip fewer, so loop. */
    private void skipFully(int n) throws IOException {
        int left = n;
        while (left > 0) {
            int got = in.skipBytes(left);
            if (got <= 0) {
                in.readUnsignedByte(); // blocks, or throws on EOF
                got = 1;
            }
            left -= got;
        }
    }

    /** VNC DES: key is the password padded to 8 bytes, each byte bit-reversed. */
    static byte[] desEncryptChallenge(String password, byte[] challenge) throws IOException {
        byte[] key = new byte[8];
        byte[] pw = password.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i < 8 && i < pw.length; i++) {
            key[i] = (byte) reverseBits(pw[i] & 0xff);
        }
        try {
            Cipher c = Cipher.getInstance("DES/ECB/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "DES"));
            return c.doFinal(challenge);
        } catch (Exception e) {
            throw new IOException("DES failed: " + e, e);
        }
    }

    static int reverseBits(int b) {
        int r = 0;
        for (int i = 0; i < 8; i++) {
            r = (r << 1) | (b & 1);
            b >>= 1;
        }
        return r;
    }

    private synchronized void allocate(int w, int h) {
        width = w;
        height = h;
        pixels = new int[w * h];
    }

    /** Returns the framebuffer. Hold the lock on this client while reading it. */
    public synchronized int[] pixels() { return pixels; }
    public synchronized int width() { return width; }
    public synchronized int height() { return height; }
    public String serverName() { return name; }

    private void readLoop() {
        String reason = null;
        try {
            while (running) {
                int type = in.readUnsignedByte();
                switch (type) {
                    case 0: // FramebufferUpdate
                        in.readUnsignedByte();
                        int nRects = in.readUnsignedShort();
                        for (int r = 0; r < nRects; r++) {
                            int x = in.readUnsignedShort();
                            int y = in.readUnsignedShort();
                            int w = in.readUnsignedShort();
                            int h = in.readUnsignedShort();
                            int enc = in.readInt();
                            if (enc == ENC_RAW) {
                                readRaw(x, y, w, h);
                            } else if (enc == ENC_DESKTOP_SIZE) {
                                allocate(w, h);
                                listener.onResize(w, h);
                            } else {
                                throw new IOException("Unsupported encoding " + enc);
                            }
                        }
                        requestUpdate(true);
                        break;
                    case 1: // SetColourMap: not expected with true colour
                        skipFully(1);
                        int n = in.readUnsignedShort();
                        skipFully(n * 6);
                        break;
                    case 2: // Bell
                        break;
                    case 3: // ServerCutText
                        skipFully(3);
                        int len = in.readInt();
                        skipFully(len);
                        break;
                    default:
                        throw new IOException("Unknown server message " + type);
                }
            }
        } catch (IOException e) {
            if (running) {
                reason = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        } finally {
            running = false;
            closeQuietly();
            listener.onClosed(reason);
        }
    }

    private void readRaw(int x, int y, int w, int h) throws IOException {
        byte[] row = new byte[w * 4];
        for (int dy = 0; dy < h; dy++) {
            in.readFully(row);
            synchronized (this) {
                if (y + dy < height && x + w <= width) {
                    int base = (y + dy) * width + x;
                    for (int dx = 0; dx < w; dx++) {
                        int o = dx * 4;
                        // Pixel bytes are B, G, R, pad (little-endian, as set in SetPixelFormat).
                        pixels[base + dx] = 0xFF000000
                                | ((row[o + 2] & 0xff) << 16)
                                | ((row[o + 1] & 0xff) << 8)
                                | (row[o] & 0xff);
                    }
                }
            }
        }
        listener.onRect(x, y, w, h);
    }

    private void requestUpdate(boolean incremental) throws IOException {
        synchronized (out) {
            out.writeByte(3);
            out.writeByte(incremental ? 1 : 0);
            out.writeShort(0);
            out.writeShort(0);
            out.writeShort(width);
            out.writeShort(height);
            out.flush();
        }
    }

    /** Pointer event. x and y are framebuffer coordinates. buttons: bit0 left, bit1 middle, bit2 right, bit3 wheel up, bit4 wheel down. */
    public void pointer(int buttons, int x, int y) {
        send(() -> {
            out.writeByte(5);
            out.writeByte(buttons);
            out.writeShort(clamp(x, 0, Math.max(0, width() - 1)));
            out.writeShort(clamp(y, 0, Math.max(0, height() - 1)));
        });
    }

    /** Key press or release. keysym is an X11 keysym (Latin-1 keysyms equal their code point). */
    public void key(boolean down, int keysym) {
        send(() -> {
            out.writeByte(4);
            out.writeByte(down ? 1 : 0);
            out.writeShort(0);
            out.writeInt(keysym);
        });
    }

    private interface Writer { void write() throws IOException; }

    private void send(Writer w) {
        if (!running) {
            return;
        }
        try {
            synchronized (out) {
                w.write();
                out.flush();
            }
        } catch (IOException e) {
            running = false;
            closeQuietly();
        }
    }

    public boolean isConnected() { return running; }

    public void close() {
        running = false;
        closeQuietly();
    }

    private void closeQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
