package app.desktop;

import android.content.Context;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;

/**
 * Installs the desktop from .tar.gz archives: the base system, and the optional Windows add-on
 * that is unpacked over it. Archives can come from a URL or from a file the user picked. They are
 * unpacked while they are read, so nothing is stored twice.
 */
public final class Installer {

    /** Progress callbacks. fraction() is 0..1 when the total size is known. */
    public interface Progress {
        void update(String message);

        default void fraction(double value) { }
    }

    /** An opened archive: its stream and its size in bytes (or -1 if unknown). */
    public static final class Source {
        public final InputStream stream;
        public final long length;

        public Source(InputStream stream, long length) {
            this.stream = stream;
            this.length = length;
        }
    }

    private Installer() { }

    /** Opens an https URL for reading. */
    public static Source open(String url) throws IOException {
        if (url == null || !url.startsWith("https://")) {
            throw new IOException("The archive URL must start with https://");
        }
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new IOException("Download failed with HTTP " + code);
        }
        return new Source(conn.getInputStream(), conn.getContentLengthLong());
    }

    /**
     * Opens a local archive chosen with the file picker (a content:// URI). The SHA-256 check is
     * skipped for these, because a local archive is not one of the published release files.
     */
    public static Source openUri(Context c, android.net.Uri uri) throws IOException {
        InputStream in = c.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("Cannot open the chosen file");
        }
        long length = -1;
        try (android.content.res.AssetFileDescriptor afd =
                     c.getContentResolver().openAssetFileDescriptor(uri, "r")) {
            if (afd != null) {
                length = afd.getLength();
            }
        } catch (IOException | RuntimeException e) {
            // Size is optional; progress is then shown as a spinner.
        }
        return new Source(in, length);
    }

    /**
     * Replaces the installed desktop with the base archive. The Windows add-on, if installed, is
     * removed too, because it was unpacked over the old base.
     */
    public static void installBase(Context c, Source src, String expectedSha256, Progress p)
            throws IOException {
        p.update("Preparing the system files");
        Session.installMarker(c).delete();
        File root = Session.rootfsDir(c);
        deleteRecursive(root);
        if (!root.mkdirs()) {
            throw new IOException("Cannot create " + root);
        }
        extract(src, root, expectedSha256, "Extracting the Linux system", p);
        // The guest has no resolv.conf of its own, so provide public resolvers.
        File resolv = new File(root, "etc/resolv.conf");
        try (OutputStream os = new FileOutputStream(resolv)) {
            os.write("nameserver 1.1.1.1\nnameserver 8.8.8.8\n".getBytes("US-ASCII"));
        }
        if (!new File(root, "bin/sh").exists()) {
            throw new IOException("The archive does not contain /bin/sh");
        }
        Session.installMarker(c).createNewFile();
    }

    /** Adds Windows support by unpacking the add-on over the installed base system. */
    public static void installAddon(Context c, Source src, String expectedSha256, Progress p)
            throws IOException {
        installOverlay(c, src, expectedSha256, p, "usr/local/bin/wine-run", "Adding Windows support");
    }

    /**
     * Unpacks an overlay archive (base + Windows add-on already installed) and checks that the
     * file it must provide exists.
     */
    public static void installOverlay(Context c, Source src, String expectedSha256, Progress p,
                                      String requiredFile, String title) throws IOException {
        if (!Session.isInstalled(c)) {
            throw new IOException("Install the Linux system first");
        }
        extract(src, Session.rootfsDir(c), expectedSha256, title, p);
        if (!new File(Session.rootfsDir(c), requiredFile).exists()) {
            throw new IOException("The archive does not contain " + requiredFile);
        }
    }

    private static void extract(Source src, File root, String expectedSha256, String title, Progress p)
            throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is not available");
        }
        InputStream raw = new DigestInputStream(src.stream, md);
        try {
            p.update(title);
            TarExtractor.extract(raw, src.length, root, (read, total, path) -> {
                if (total > 0) {
                    p.fraction(Math.min(1.0, read / (double) total));
                }
                p.update(title + ": " + shorten(path));
            });
            // Read to the end so the digest covers the whole archive.
            byte[] buf = new byte[1 << 16];
            while (raw.read(buf) >= 0) {
                // Discard.
            }
        } finally {
            raw.close();
        }
        if (expectedSha256 != null && !expectedSha256.trim().isEmpty()) {
            String actual = hex(md.digest());
            if (!actual.equalsIgnoreCase(expectedSha256.trim())) {
                throw new IOException("SHA-256 mismatch: got " + actual);
            }
        }
        p.fraction(1.0);
    }

    private static String shorten(String path) {
        if (path == null) return "";
        return path.length() > 48 ? "..." + path.substring(path.length() - 45) : path;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format("%02x", x & 0xff));
        }
        return sb.toString();
    }

    private static void deleteRecursive(File f) {
        // Do not follow symlinks: a link to a directory must be removed, not emptied.
        if (java.nio.file.Files.isDirectory(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) {
                    deleteRecursive(k);
                }
            }
        }
        f.delete();
    }
}
