package app.desktop;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs commands inside the Debian rootfs through the bundled proot (libproot.so).
 * Everything the desktop needs is started through here.
 */
public final class Session {

    public static final int VNC_PORT = 5901;
    /** Desktop size in pixels. The view scales it to the screen. */
    public static final int DESK_W = 1280;
    public static final int DESK_H = 720;
    private static final String PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin";
    private static final String ALNUM = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private static final String PREFS = "desktop";
    private static final String PREF_VNC_PASSWORD = "vnc_password";

    private static Process desktopProcess;
    private static String vncPassword;
    /** True when a VNC server answered on VNC_PORT, even if this app process did not start it. */
    private static volatile boolean vncUp;

    private Session() { }

    public static File rootfsDir(Context c) { return new File(c.getFilesDir(), "rootfs"); }

    public static File installMarker(Context c) { return new File(c.getFilesDir(), "rootfs.installed"); }

    public static boolean isInstalled(Context c) {
        return installMarker(c).exists() && new File(rootfsDir(c), "bin/sh").exists();
    }

    public static String busyboxPath(Context c) {
        return c.getApplicationInfo().nativeLibraryDir + "/libbusybox.so";
    }

    static String prootPath(Context c) {
        return c.getApplicationInfo().nativeLibraryDir + "/libproot.so";
    }

    static File logFile(Context c) { return new File(c.getFilesDir(), "desktop.log"); }

    /** Builds a proot command that runs guestArgs inside the rootfs. extraEnv entries look like KEY=value. */
    static List<String> prootCommand(Context c, List<String> extraEnv, String... guestArgs) {
        File tmp = new File(c.getFilesDir(), "guest-tmp");
        tmp.mkdirs();
        List<String> cmd = new ArrayList<>();
        cmd.add(prootPath(c));
        cmd.add("--kill-on-exit");
        cmd.add("--link2symlink");
        cmd.add("-r");
        cmd.add(rootfsDir(c).getAbsolutePath());
        cmd.add("-b");
        cmd.add("/dev");
        cmd.add("-b");
        cmd.add("/proc");
        cmd.add("-b");
        cmd.add("/sys");
        if (new File("/dev/pts").isDirectory()) {
            // Terminal emulators need pseudo-terminals.
            cmd.add("-b");
            cmd.add("/dev/pts");
        }
        cmd.add("-b");
        cmd.add(tmp.getAbsolutePath() + ":/tmp");
        cmd.add("-w");
        cmd.add("/root");
        cmd.add("/usr/bin/env");
        cmd.add("-i");
        cmd.add("HOME=/root");
        cmd.add("USER=root");
        cmd.add("LANG=C.UTF-8");
        cmd.add("TERM=xterm-256color");
        cmd.add("PATH=" + PATH);
        cmd.addAll(extraEnv);
        cmd.addAll(Arrays.asList(guestArgs));
        return cmd;
    }

    private static ProcessBuilder prootBuilder(Context c, List<String> cmd) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        // proot needs a writable temp dir for its embedded loader. Seccomp is off because
        // some Android kernels reject the filter.
        File ptmp = new File(c.getFilesDir(), "proot-tmp");
        ptmp.mkdirs();
        pb.environment().clear();
        pb.environment().put("PROOT_TMP_DIR", ptmp.getAbsolutePath());
        pb.environment().put("PROOT_NO_SECCOMP", "1");
        return pb;
    }

    /** True if this app started the desktop, or a VNC server is answering (for example after the app restarted). */
    public static boolean isDesktopRunning() {
        Process p;
        synchronized (Session.class) {
            p = desktopProcess;
        }
        return (p != null && p.isAlive()) || vncUp;
    }

    /**
     * The VNC password is generated once and kept in the app's preferences, so reopening the app
     * (after Android restarted its process) can still connect to a desktop that is still running.
     */
    public static synchronized String vncPassword(Context c) {
        if (vncPassword == null) {
            android.content.SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String saved = prefs.getString(PREF_VNC_PASSWORD, null);
            if (saved == null || saved.length() < 6) {
                saved = randomPassword(8);
                prefs.edit().putString(PREF_VNC_PASSWORD, saved).apply();
            }
            vncPassword = saved;
        }
        return vncPassword;
    }

    /** Checks whether something is listening on the VNC port. Blocks for up to about 300 ms. */
    public static boolean vncListening() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", VNC_PORT), 300);
            vncUp = true;
        } catch (IOException e) {
            vncUp = false;
        }
        return vncUp;
    }

    /** Starts Xvfb, x11vnc and XFCE in the background. Does nothing if a desktop is already up. */
    public static synchronized void startDesktop(Context c) throws IOException {
        if (isDesktopRunning() || vncListening()) return;
        List<String> env = new ArrayList<>();
        env.add("VNC_PASSWORD=" + vncPassword(c));
        env.add("DESK_RES=" + DESK_W + "x" + DESK_H);
        // A fresh log per start, so the error screen shows only this attempt.
        logFile(c).delete();
        ProcessBuilder pb = prootBuilder(c, prootCommand(c, env, "/bin/sh", "/usr/local/bin/desktop-start"));
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile(c)));
        desktopProcess = pb.start();
    }

    /** Says how the desktop process ended, for the error screen. Empty while it is still running. */
    public static synchronized String desktopExitInfo() {
        Process p = desktopProcess;
        if (p == null || p.isAlive()) return "";
        return " (proot exited with code " + p.exitValue() + ")";
    }

    /** The last lines of desktop.log for this start, shortened so they fit on the error screen. */
    public static String logTail(Context c, int maxLines) {
        File f = logFile(c);
        if (!f.exists()) return "(no desktop.log was written)";
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            long from = Math.max(0, len - 16384);
            byte[] buf = new byte[(int) (len - from)];
            raf.seek(from);
            raf.readFully(buf);
            String[] lines = new String(buf, "UTF-8").split("\\r?\\n");
            StringBuilder out = new StringBuilder();
            int start = Math.max(0, lines.length - maxLines);
            for (int i = start; i < lines.length; i++) {
                String line = lines[i].trim();
                if (line.isEmpty()) continue;
                if (line.length() > 160) line = line.substring(0, 160) + "...";
                out.append(line).append('\n');
            }
            return out.length() == 0 ? "(desktop.log is empty)" : out.toString().trim();
        } catch (IOException e) {
            return "(cannot read desktop.log: " + e.getMessage() + ")";
        }
    }

    /** Waits until the VNC port accepts connections. Returns false on timeout or if the desktop died. */
    public static boolean waitForVnc(long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (!isDesktopRunning()) return false;
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", VNC_PORT), 500);
                return true;
            } catch (IOException notYet) {
                try { Thread.sleep(500); } catch (InterruptedException e) { return false; }
            }
        }
        return false;
    }

    /** Stops XFCE, Xvfb and x11vnc, then the proot process. */
    public static synchronized void stopDesktop(Context c) {
        // Runs even if this app process did not start the desktop, so leftovers can be stopped too.
        try {
            runGuest(c, "/usr/local/bin/desktop-stop");
        } catch (IOException | InterruptedException ignored) {
            // Killing the proot process below also cleans up its children.
        }
        if (desktopProcess != null) {
            desktopProcess.destroy();
            desktopProcess = null;
        }
        vncUp = false;
    }

    /** Shows the login screen on the desktop. The desktop keeps running, only the screen is locked. */
    public static void lockDesktop(Context c) {
        try {
            runGuest(c, "/usr/local/bin/desktop-lock");
        } catch (IOException | InterruptedException ignored) {
            // The login screen is shown on desktop start anyway.
        }
    }

    /** Runs a short command in the rootfs and waits for it. */
    public static int runGuest(Context c, String... guestArgs) throws IOException, InterruptedException {
        ProcessBuilder pb = prootBuilder(c, prootCommand(c, new ArrayList<String>(), guestArgs));
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile(c)));
        return pb.start().waitFor();
    }

    /** Starts a graphical program on the desktop display. Returns after the process starts. */
    public static void launchApp(Context c, String... command) throws IOException {
        if (!isDesktopRunning() && !vncListening()) {
            throw new IOException("The desktop is not running");
        }
        String[] guest = new String[command.length + 1];
        guest[0] = "/usr/local/bin/app-run";
        System.arraycopy(command, 0, guest, 1, command.length);
        ProcessBuilder pb = prootBuilder(c, prootCommand(c, new ArrayList<String>(), guest));
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile(c)));
        pb.start();
    }

    static String randomPassword(int n) {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(ALNUM.charAt(r.nextInt(ALNUM.length())));
        }
        return sb.toString();
    }
}
