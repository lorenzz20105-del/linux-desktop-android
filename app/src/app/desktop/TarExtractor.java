package app.desktop;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;

/**
 * Unpacks a gzip-compressed tar (ustar, GNU long names and pax headers) without an external tool,
 * so the app can report real progress. Device nodes are skipped, because an app cannot create
 * them. Paths that try to leave the destination directory are skipped too.
 */
public final class TarExtractor {

    /** Receives progress: bytes read from the compressed stream, and the total if it is known (or -1). */
    public interface Listener {
        void onProgress(long compressedRead, long compressedTotal, String currentPath);
    }

    private static final int BLOCK = 512;

    private TarExtractor() { }

    /** Counts the bytes that pass through, for progress reporting. */
    private static final class CountingStream extends java.io.FilterInputStream {
        long count;
        final Listener listener;
        final long total;

        CountingStream(InputStream in, long total, Listener listener) {
            super(in);
            this.total = total;
            this.listener = listener;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long s = super.skip(n);
            if (s > 0) count += s;
            return s;
        }
    }

    /**
     * Extracts the archive read from raw into dest. compressedTotal is the size of the compressed
     * archive, or -1 if unknown. Returns the number of entries written.
     */
    public static int extract(InputStream raw, long compressedTotal, File dest, Listener listener)
            throws IOException {
        CountingStream counting = new CountingStream(raw, compressedTotal, listener);
        InputStream in = new BufferedInputStream(new GZIPInputStream(new BufferedInputStream(counting, 1 << 16), 1 << 16), 1 << 16);
        Path root = dest.toPath().toAbsolutePath().normalize();
        Files.createDirectories(root);

        byte[] header = new byte[BLOCK];
        String longName = null;
        String longLink = null;
        String paxPath = null;
        String paxLink = null;
        int entries = 0;
        while (true) {
            readFully(in, header);
            if (isZero(header)) {
                break; // End of archive.
            }
            long size = parseOctal(header, 124, 12);
            char type = (char) header[156];
            if (type == 0) type = '0';
            String name = cString(header, 0, 100);
            String magic = cString(header, 257, 6);
            if (magic.startsWith("ustar")) {
                String prefix = cString(header, 345, 155);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
            }
            String linkName = cString(header, 157, 100);

            if (type == 'L' || type == 'K') {
                String value = readData(in, size);
                if (type == 'L') longName = value;
                else longLink = value;
                skipPadding(in, size);
                continue;
            }
            if (type == 'x') {
                String pax = readData(in, size);
                skipPadding(in, size);
                paxPath = paxValue(pax, "path");
                paxLink = paxValue(pax, "linkpath");
                continue;
            }
            if (type == 'g') {
                readData(in, size);
                skipPadding(in, size);
                continue;
            }
            if (longName != null) {
                name = longName;
                longName = null;
            }
            if (paxPath != null) {
                name = paxPath;
                paxPath = null;
            }
            if (longLink != null) {
                linkName = longLink;
                longLink = null;
            }
            if (paxLink != null) {
                linkName = paxLink;
                paxLink = null;
            }

            int mode = (int) parseOctal(header, 100, 8);
            Path target = safeTarget(root, name);
            if (target == null) {
                skipData(in, size);
                skipPadding(in, size);
                continue;
            }
            if (hasSymlinkParent(root, target)) {
                throw new IOException("Archive path traverses a symlink: " + name);
            }
            if (listener != null) {
                listener.onProgress(counting.count, compressedTotal, name);
            }

            switch (type) {
                case '5': // Directory
                    Files.createDirectories(target);
                    break;
                case '0': // Regular file
                case '7':
                    writeFile(in, size, target, mode);
                    skipPadding(in, size);
                    break;
                case '2': // Symbolic link
                    deleteIfExists(target);
                    Files.createDirectories(target.getParent());
                    Files.createSymbolicLink(target, Paths.get(linkName));
                    skipData(in, size);
                    skipPadding(in, size);
                    break;
                case '1': { // Hard link: copy the file it points at
                    Path source = safeTarget(root, linkName);
                    deleteIfExists(target);
                    Files.createDirectories(target.getParent());
                    if (source != null && !hasSymlinkParent(root, source)
                            && Files.isRegularFile(source, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING,
                                java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    }
                    skipData(in, size);
                    skipPadding(in, size);
                    break;
                }
                default: // Devices, FIFOs and anything else: skip.
                    skipData(in, size);
                    skipPadding(in, size);
                    continue;
            }
            entries++;
        }
        // Drain the rest of the compressed stream so the reported position reaches the end.
        byte[] buf = new byte[1 << 16];
        while (in.read(buf) >= 0) {
            // Discard.
        }
        return entries;
    }

    /** Refuses writes through a previously extracted symlink (lexical normalization alone is not enough). */
    private static boolean hasSymlinkParent(Path root, Path target) {
        Path current = root;
        Path relative = root.relativize(target);
        for (int i = 0; i < relative.getNameCount() - 1; i++) {
            current = current.resolve(relative.getName(i));
            if (Files.isSymbolicLink(current)) return true;
        }
        return false;
    }

    private static Path safeTarget(Path root, String name) {
        if (name == null || name.isEmpty()) return null;
        String clean = name.startsWith("./") ? name.substring(2) : name;
        while (clean.startsWith("/")) clean = clean.substring(1);
        if (clean.isEmpty() || clean.equals(".")) return null;
        Path p = root.resolve(clean).normalize();
        if (!p.startsWith(root) || p.equals(root)) return null;
        return p;
    }

    private static void writeFile(InputStream in, long size, Path target, int mode) throws IOException {
        deleteIfExists(target);
        Files.createDirectories(target.getParent());
        File f = target.toFile();
        try (OutputStream out = new FileOutputStream(f)) {
            byte[] buf = new byte[1 << 16];
            long left = size;
            while (left > 0) {
                int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                if (n < 0) throw new IOException("Unexpected end of archive");
                out.write(buf, 0, n);
                left -= n;
            }
        }
        f.setReadable(true, false);
        f.setWritable(true, true);
        if ((mode & 0111) != 0) {
            f.setExecutable(true, false);
        }
    }

    private static void deleteIfExists(Path p) throws IOException {
        if (Files.exists(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return; // Keep directories; their contents are overwritten as needed.
            }
            Files.delete(p);
        }
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new IOException("Truncated archive");
            off += n;
        }
    }

    private static String readData(InputStream in, long size) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(size, 1 << 20));
        byte[] buf = new byte[4096];
        long left = size;
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) throw new IOException("Truncated archive");
            bos.write(buf, 0, n);
            left -= n;
        }
        String text = bos.toString("UTF-8");
        // GNU long names are NUL-terminated; drop the terminator.
        int end = text.indexOf('\0');
        return end >= 0 ? text.substring(0, end) : text;
    }

    private static void skipData(InputStream in, long size) throws IOException {
        long left = size;
        byte[] buf = new byte[4096];
        while (left > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) throw new IOException("Truncated archive");
            left -= n;
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long pad = (BLOCK - (size % BLOCK)) % BLOCK;
        if (pad > 0) {
            skipData(in, pad);
        }
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) {
            if (x != 0) return false;
        }
        return true;
    }

    private static long parseOctal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c == 0 || c == ' ') {
                if (v != 0) break;
                continue;
            }
            if (c < '0' || c > '7') break;
            v = v * 8 + (c - '0');
        }
        return v;
    }

    private static String cString(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    /** Reads one key from pax data made of "len key=value\n" records. */
    private static String paxValue(String pax, String key) {
        int i = 0;
        while (i < pax.length()) {
            int space = pax.indexOf(' ', i);
            if (space < 0) break;
            int len;
            try {
                len = Integer.parseInt(pax.substring(i, space));
            } catch (NumberFormatException e) {
                break;
            }
            if (len <= 0 || i + len > pax.length()) break;
            String record = pax.substring(space + 1, i + len - 1); // Drop the trailing newline.
            int eq = record.indexOf('=');
            if (eq > 0 && record.substring(0, eq).equals(key)) {
                return record.substring(eq + 1);
            }
            i += len;
        }
        return null;
    }
}
