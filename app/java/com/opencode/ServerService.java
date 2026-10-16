package com.opencode;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.file.Files;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Foreground service that owns the OpenCode server process and the
 * bundled static file server for the web UI.
 */
public class ServerService extends Service {
    private static final String TAG = "OpenCode";
    private static final String CHANNEL_ID = "opencode_server_quiet";
    private static final int NOTIFICATION_ID = 1;

    public static final int API_PORT = 49474;
    public static final int UI_PORT = 18080;

    private volatile boolean running;
    private Thread supervisor;
    private Process serverProcess;
    private StaticServer staticServer;
    private Notifier notifier;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (supervisor == null) {
            running = true;
            supervisor = new Thread(new Runnable() {
                @Override
                public void run() {
                    supervise();
                }
            }, "oc-supervisor");
            supervisor.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (notifier != null) {
            notifier.stop();
            notifier = null;
        }
        if (serverProcess != null) {
            serverProcess.destroy();
            serverProcess = null;
        }
        if (staticServer != null) {
            staticServer.stop();
            staticServer = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private NotificationManager notificationManager() {
        return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    private File files() {
        return getFilesDir();
    }

    private File homeDir() {
        return new File(files(), "home");
    }

    private void supervise() {
        // Boot order: environment first, interface second. Every phase is
        // reported to .boot.json (served as /boot.json) so the loader can
        // show real progress. The server starts only through oc-shell, i.e.
        // with exactly the environment the terminal sees.
        writeBoot("starting", "preparing directories");
        verifyAll();
        homeDir().mkdirs();
        new File(files(), "tmp").mkdirs();
        new File(files(), ".lasterror").delete();
        try {
            ensureServiceJson();
        } catch (IOException e) {
            Log.e(TAG, "service credentials failed", e);
        }
        try {
            staticServer = new StaticServer(
                    new File(files(), "www"), new File(files(), ".boot.json"),
                    new File(files(), ".oc-version"), UI_PORT);
            staticServer.start();
            Log.i(TAG, "static UI server on :" + UI_PORT);
        } catch (IOException e) {
            Log.e(TAG, "static server failed", e);
        }
        try {
            extractWwwOnce();
        } catch (IOException e) {
            Log.e(TAG, "www phase failed", e);
        }
        writeBoot("extract", "official Termux userland");
        try {
            extractBootstrapOnce();
        } catch (IOException e) {
            Log.e(TAG, "bootstrap phase failed, continuing degraded", e);
            writeSmallFile(new File(files(), ".lasterror"), "bootstrap: " + e);
        }
        writeBoot("tools", "native entry + guest libs");
        try {
            extractToolsOnce();
        } catch (IOException e) {
            Log.e(TAG, "tools phase failed, continuing degraded", e);
            writeSmallFile(new File(files(), ".lasterror"), "tools: " + e);
        }
        writeBoot("shell", "linking Termux-compatible shell");
        try {
            ensureLinks();
            cacheLinkerConfig();
            writeOcShell();
        } catch (Exception e) {
            Log.e(TAG, "links phase failed", e);
        }
        if (!waitForShell(6)) {
            Log.e(TAG, "shell check failed, continuing degraded");
            writeSmallFile(new File(files(), ".lasterror"), "shell check failed");
        }
        writeBoot("packages", "pkg update in background");
        promoteStaged();
        // Publish the managed server version for the UI (About line).
        // Best guess now; startVersionSync keeps it equal to the RUNNING
        // server afterwards (probes can lie when the shell is half-dead).
        try {
            expectedServerVersion = resolveExpectedVersion();
            if (expectedServerVersion.length() > 0) {
                writeSmallFile(new File(files(), ".oc-version"),
                        expectedServerVersion);
            }
        } catch (Exception e) {
            Log.i(TAG, "version publish: " + e);
        }
        startAptBackground();
        startAutoUpdate();
        notifier = new Notifier(this);
        notifier.start();
        startVersionSync();
        writeBoot("ready", "starting server inside the terminal env");
        int fastFails = 0;
        while (running) {
            long started = System.currentTimeMillis();
            try {
                runServerOnce();
            } catch (Exception e) {
                Log.e(TAG, "server run failed", e);
            }
            if (!running) {
                break;
            }
            // Crash-loop guard: instant exits back off instead of spinning.
            // After a few instant deaths, heal once (links, shell entry,
            // dead live binary) so a bad update cannot wedge the app.
            long lived = System.currentTimeMillis() - started;
            if (lived < 10000) {
                fastFails++;
                if (fastFails == 3) {
                    healServerEnv();
                }
            } else {
                fastFails = 0;
            }
            long sleep = 3000 + Math.min(fastFails, 6) * 5000;
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /**
     * One-shot health snapshot for ~/.cache/oc-health.log: proves which
     * server binary answers, whether models list, and whether a terminal
     * session spawns. Written after the API comes up; the user can paste
     * it when something looks dead.
     */
    /**
     * Crash-loop rescue, once per boot: when the server dies instantly a
     * few times in a row, the live binary or the shell entry is suspect.
     * Re-links, re-verifies a live binary that still executes, otherwise
     * drops it so the APK-bundled fallback takes over next restart.
     */
    private boolean healed;

    private void healServerEnv() {
        if (healed) {
            return;
        }
        healed = true;
        Log.i(TAG, "healing server env after fast crashes");
        try {
            ensureLinks();
            cacheLinkerConfig();
            writeOcShell();
        } catch (Exception e) {
            Log.e(TAG, "heal links failed", e);
        }
        try {
            File live = new File(files(), "usr/lib/" + LIVE_NAME);
            if (live.isFile()) {
                String ver = versionOf(probeGuestVersion(
                        GUEST_PREFIX + "/lib/" + LIVE_NAME));
                if (ver.length() == 0 || ver.equals("0")) {
                    Log.i(TAG, "live binary dead, dropping to bundled");
                    live.delete();
                } else {
                    Log.i(TAG, "live binary answers: " + ver);
                }
            }
        } catch (Exception e) {
            Log.i(TAG, "heal probe: " + e);
        }
        // Free update leftovers that pile up on tight storage.
        try {
            deleteRecursive(new File(homeDir(), ".cache/oc-upd"));
        } catch (Exception ignored) {
        }
    }

    /** Phase marker for the loader (served as /boot.json, localhost). */    private void writeBoot(String phase, String detail) {
        try {
            long ts = System.currentTimeMillis();
            String json = "{\"phase\":\"" + phase + "\",\"detail\":\""
                    + detail.replace("\\", "\\\\").replace("\"", "\\\"")
                    + "\",\"ts\":" + ts + "}";
            FileOutputStream fos = new FileOutputStream(new File(files(), ".boot.json"));
            try {
                fos.write(json.getBytes("UTF-8"));
            } finally {
                fos.close();
            }
        } catch (IOException ignored) {
        }
        Log.i(TAG, "boot: " + phase + " - " + detail);
    }

    /**
     * Heals partial installs/updates: any component with missing, empty,
     * unreadable or dangling entries loses its marker so it gets rebuilt.
     * Runs before layout. The bootstrap is the official userland and owns
     * every shell library; staged native files are only proot + its two
     * libs + the unmanaged libopentui + the pty helper.
     */
    private void verifyAll() {
        File usr = new File(files(), "usr");
        // Version-agnostic: apt upgrades may replace versioned libs
        // (readline 8.2 -> 8.3). Pin binaries + resolvable sonames,
        // never exact versioned filenames (that forced a full re-extract
        // after every upgrade and downgraded the prefix).
        String[] bootstrapKeys = {
                "bin/bash", "bin/dash", "bin/dpkg", "bin/login", "bin/pkg",
                "lib/libtermux-exec-ld-preload.so",
        };
        for (int i = 0; i < bootstrapKeys.length; i++) {
            if (!healthyFile(new File(usr, bootstrapKeys[i]), true)) {
                Log.i(TAG, "bootstrap broken, re-extracting");
                new File(files(), ".bootstrapbuild").delete();
                break;
            }
        }
        String[] toolKeys = {
                "bin/proot",
                "lib/libandroid-shmem.so",
                "lib/libopentui.so", "lib/librust_pty_arm64.so",
                "libexec/proot/loader",
        };
        for (int i = 0; i < toolKeys.length; i++) {
            if (!healthyFile(new File(usr, toolKeys[i]), true)) {
                Log.i(TAG, "tools broken, re-extracting");
                new File(files(), ".toolsbuild").delete();
                break;
            }
        }
        // libtalloc is version-pinned by filename upstream (2.5.0 today,
        // newer after upgrades): only its soname link must resolve.
        if (!healthyLink(new File(usr, "lib/libtalloc.so.2"))) {
            healOneLink(new File(usr, "lib"), "libtalloc.so.2");
            if (!healthyLink(new File(usr, "lib/libtalloc.so.2"))) {
                Log.i(TAG, "tools broken, re-extracting");
                new File(files(), ".toolsbuild").delete();
            }
        }
        // Links the loader actually resolves (dangling links look present).
        // A drifted versioned link is healed in place (apt upgrades it);
        // only truly unrepairable damage triggers a bootstrap re-extract.
        healSonameLinks(new File(files(), "usr/lib"));
        String[] linkKeys = {
                "lib/libreadline.so.8", "lib/libncursesw.so.6",
                "lib/libtalloc.so.2", "lib/libtermux-exec.so",
                "bin/sh",
        };
        for (int i = 0; i < linkKeys.length; i++) {
            if (!healthyLink(new File(usr, linkKeys[i]))) {
                Log.i(TAG, "links broken beyond heal, re-extracting bootstrap");
                new File(files(), ".bootstrapbuild").delete();
                break;
            }
        }
        if (!new File(files(), "www/index.html").isFile()) {
            deleteRecursive(new File(files(), "www"));
        }
    }

    /** Regular file exists, non-empty and readable (follows symlinks). */
    private static boolean healthyFile(File file, boolean executable) {
        try {
            if (!file.isFile()) {
                return false;
            }
            if (file.length() == 0) {
                return false;
            }
            if (!file.canRead()) {
                return false;
            }
            if (executable && !file.canExecute()) {
                return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Symlink exists and its target exists and is non-empty. */
    private static boolean healthyLink(File link) {
        try {
            if (!Files.isSymbolicLink(link.toPath())) {
                return false;
            }
            File target = new File(link.getParentFile(),
                    Files.readSymbolicLink(link.toPath()).toString());
            return target.isFile() && target.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static final String BOOTSTRAP_BUILD = "3";

    /** Official Termux bootstrap: the full userland this env is built on. */
    private void extractBootstrapOnce() throws IOException {
        File usr = new File(files(), "usr");
        File marker = new File(files(), ".bootstrapbuild");
        if (BOOTSTRAP_BUILD.equals(readSmallFile(marker))
                && new File(usr, "bin/bash").isFile()
                && new File(usr, "bin/dpkg").isFile()) {
            return;
        }
        InputStream assets = null;
        ZipInputStream zip = null;
        try {
            assets = getAssets().open("bootstrap.zip");
            zip = new ZipInputStream(assets);
            byte[] buffer = new byte[65536];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File out = canonicalChild(usr, entry.getName());
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    copyStreamToFile(zip, out, buffer);
                }
                zip.closeEntry();
            }
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                }
            } else if (assets != null) {
                try {
                    assets.close();
                } catch (IOException ignored) {
                }
            }
        }
        chmodRecursiveExecutable(new File(usr, "bin"));
        chmodRecursiveExecutable(new File(usr, "lib"));
        applySymlinksTxt(usr);
        String[] required = {
                "bin/bash", "bin/dash", "bin/dpkg", "bin/login", "bin/pkg",
                "lib/libtermux-exec-ld-preload.so",
        };
        for (int i = 0; i < required.length; i++) {
            if (!healthyFile(new File(usr, required[i]), true)) {
                Log.e(TAG, "bootstrap incomplete, missing " + required[i]);
                writeSmallFile(marker, "");
                throw new IOException("bootstrap incomplete");
            }
        }
        // Sonames must resolve (whatever version the files carry).
        String[] sonames = {"lib/libreadline.so.8", "lib/libncursesw.so.6"};
        for (int i = 0; i < sonames.length; i++) {
            if (!healthyLink(new File(usr, sonames[i]))) {
                Log.e(TAG, "bootstrap incomplete, dangling " + sonames[i]);
                writeSmallFile(marker, "");
                throw new IOException("bootstrap incomplete");
            }
        }
        writeSmallFile(marker, BOOTSTRAP_BUILD);
        // Re-extracted files are the pinned (older) set; force the
        // auto-updater to refresh them soon instead of waiting 24h.
        new File(files(), ".aptstamp").delete();
        Log.i(TAG, "bootstrap extracted");
    }

    private static final String TOOLS_BUILD = "6";

    /**
     * Staged native files (mirror of assets/tools): proot to enter the
     * guest, its two loader deps, the unmanaged libopentui the guest
     * opencode dlopens, and the pty helper. Everything else (shell, apt,
     * libraries) belongs to the bootstrap and is never overwritten.
     */
    private void extractToolsOnce() throws IOException {
        File usr = new File(files(), "usr");
        File marker = new File(files(), ".toolsbuild");
        if (TOOLS_BUILD.equals(readSmallFile(marker))
                && new File(usr, "bin/proot").isFile()
                && new File(usr, "lib/libopentui.so").isFile()) {
            return;
        }
        copyAssetTree("tools", usr);
        chmodRecursiveExecutable(new File(usr, "bin"));
        chmodRecursiveExecutable(new File(usr, "lib"));
        chmodRecursiveExecutable(new File(usr, "libexec"));
        // libtalloc ships versioned only; proot needs the SONAME name.
        // Heal (never force the staged filename: apt may own a newer one).
        healOneLink(new File(usr, "lib"), "libtalloc.so.2");
        writeSmallFile(marker, TOOLS_BUILD);
        Log.i(TAG, "shell env extracted");
    }

    private void copyAssetTree(String assetPath, File dest) throws IOException {
        String[] entries = null;
        try {
            entries = getAssets().list(assetPath);
        } catch (IOException ignored) {
        }
        if (entries != null && entries.length > 0) {
            dest.mkdirs();
            for (int i = 0; i < entries.length; i++) {
                copyAssetTree(assetPath + "/" + entries[i], new File(dest, entries[i]));
            }
            return;
        }
        // Empty list means a file - or an empty dir. Try file first.
        InputStream probe = null;
        try {
            probe = getAssets().open(assetPath);
        } catch (IOException openFailed) {
            // Empty directory.
            dest.mkdirs();
            return;
        }
        try {
            copyStreamToFile(probe, dest);
        } finally {
            try {
                probe.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void copyAssetFile(String assetPath, File dest) throws IOException {
        InputStream in = getAssets().open(assetPath);
        try {
            copyStreamToFile(in, dest);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Writes fully and syncs (ENOSPC surfaces here, not silently at
        close). Empty files are legitimate (e.g. dpkg databases). */
    private static void copyStreamToFile(InputStream in, File dest, byte[] buffer) throws IOException {
        File parent = dest.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        FileOutputStream fos = new FileOutputStream(dest);
        boolean ok = false;
        try {
            int n;
            while ((n = in.read(buffer)) >= 0) {
                fos.write(buffer, 0, n);
            }
            fos.getFD().sync();
            fos.close();
            ok = true;
        } finally {
            if (!ok) {
                try {
                    fos.close();
                } catch (IOException ignored) {
                }
                dest.delete();
            }
        }
    }

    private void copyStreamToFile(InputStream in, File dest) throws IOException {
        copyStreamToFile(in, dest, new byte[32768]);
    }

    /** Zip-Slip guard: entry must stay inside the destination root. */
    private static File canonicalChild(File root, String name) throws IOException {
        File out = new File(root, name);
        String base = root.getCanonicalPath() + File.separator;
        String target = out.getCanonicalPath();
        if (!target.equals(root.getCanonicalPath()) && !target.startsWith(base)) {
            throw new IOException("entry escapes root: " + name);
        }
        return out;
    }

    private static void chmodRecursiveExecutable(File dir) {
        if (dir.isFile()) {
            dir.setExecutable(true, false);
            dir.setReadable(true, false);
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (int i = 0; i < children.length; i++) {
            chmodRecursiveExecutable(children[i]);
        }
    }

    /**
     * Self-healing links. The bootstrap manifest (SYMLINKS.txt) provides
     * every applet and versioned-library link, but only for MISSING links:
     * apt-managed upgrades own their links afterwards and must never be
     * overwritten with stale bootstrap targets (that dangled every shell
     * after a readline-style upgrade). Drifted versioned links are healed
     * dynamically to whatever matching library exists.
     */
    private void ensureLinks() {
        File usr = new File(files(), "usr");
        File bin = new File(files(), "bin");
        bin.mkdirs();
        healUsrBinBash();
        applySymlinksTxt(usr);
        healSonameLinks(new File(usr, "lib"));
        // No login greeting in the interactive terminal: hushlogin file
        // (standard mechanism honored by the guest login script) plus
        // TERMUX_HUSHLOGIN exported by oc-shell.
        writeSmallFile(new File(homeDir(), ".hushlogin"), "");
        // Lost +x is never healed otherwise: enforce on every start.
        chmodRecursiveExecutable(new File(usr, "bin"));
        chmodRecursiveExecutable(new File(usr, "lib"));
        chmodRecursiveExecutable(new File(usr, "libexec"));
        chmodRecursiveExecutable(bin);
    }

    /**
     * Points a dangling or missing versioned link (libreadline.so.8 and
     * friends) at the best matching library actually present, whatever
     * version apt installed. Never touches healthy links.
     * Besides the known sonames, any other dangling *.so* link in the lib
     * dir is healed the same way (apt renames are the usual cause).
     */
    private void healSonameLinks(File lib) {
        String[] sonames = {"libreadline.so.8", "libncursesw.so.6",
                "libtalloc.so.2", "libtermux-exec.so"};
        for (int i = 0; i < sonames.length; i++) {
            healOneLink(lib, sonames[i]);
        }
        File[] files;
        try {
            files = lib.listFiles();
        } catch (Exception e) {
            return;
        }
        if (files == null) {
            return;
        }
        for (int i = 0; i < files.length; i++) {
            try {
                String name = files[i].getName();
                if (!name.contains(".so")) {
                    continue;
                }
                if (!Files.isSymbolicLink(files[i].toPath())) {
                    continue;
                }
                if (healthyLink(files[i])) {
                    continue;
                }
                boolean known = false;
                for (int k = 0; k < sonames.length; k++) {
                    if (name.equals(sonames[k])) {
                        known = true;
                        break;
                    }
                }
                if (!known) {
                    Log.i(TAG, "healing drifted link " + name);
                    healOneLink(lib, name);
                }
            } catch (Exception e) {
                Log.i(TAG, "soname scan: " + e);
            }
        }
    }

    private void healOneLink(File lib, String soname) {
        try {
            File link = new File(lib, soname);
            if (healthyLink(link)) {
                return;
            }
            File[] files = lib.listFiles();
            if (files == null) {
                return;
            }
            String best = null;
            String bestVer = null;
            for (int j = 0; j < files.length; j++) {
                String name = files[j].getName();
                if (!files[j].isFile() || !name.startsWith(soname + ".")) {
                    continue;
                }
                String ver = name.substring(soname.length() + 1);
                if (bestVer == null || compareVersions(ver, bestVer) > 0) {
                    bestVer = ver;
                    best = name;
                }
            }
            if (best != null) {
                Log.i(TAG, "relinking " + soname + " -> " + best);
                link.delete();
                android.system.Os.symlink(best,
                        link.getAbsolutePath());
            }
        } catch (Exception e) {
            Log.i(TAG, "soname heal: " + e);
        }
    }

    /**
     * Restores files the app itself may have clobbered in older builds
     * (usr/bin/bash must be a real binary, never a coreutils link).
     */
    private void healUsrBinBash() {
        File bash = new File(files(), "usr/bin/bash");
        boolean bad = false;
        try {
            bad = java.nio.file.Files.isSymbolicLink(bash.toPath());
        } catch (Exception ignored) {
        }
        if (!bad) {
            // Must be a real ELF binary (agents sometimes replace it with
            // a POSIX fallback script; keep working, but restore GNU bash).
            try {
                java.io.FileInputStream fis = new java.io.FileInputStream(bash);
                try {
                    byte[] magic = new byte[4];
                    int n = fis.read(magic);
                    bad = n < 4 || magic[0] != 0x7f || magic[1] != 'E'
                            || magic[2] != 'L' || magic[3] != 'F';
                } finally {
                    fis.close();
                }
            } catch (Exception e) {
                bad = true;
            }
        }
        if (!bad) {
            return;
        }
        Log.i(TAG, "healing usr/bin/bash");
        File tmp = new File(files(), "usr/bin/bash.tmp");
        try {
            extractSingleAssetFile("bootstrap.zip", "bin/bash", tmp);
            tmp.setExecutable(true, false);
            if (!tmp.renameTo(bash)) {
                bash.delete();
                if (!tmp.renameTo(bash)) {
                    throw new IOException("rename failed");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "bash heal failed", e);
            tmp.delete();
            new File(files(), ".bootstrapbuild").delete();
        }
    }

    private void extractSingleAssetFile(String zipAsset, String entryName, File dest) throws IOException {
        InputStream assets = null;
        ZipInputStream zip = null;
        try {
            assets = getAssets().open(zipAsset);
            zip = new ZipInputStream(assets);
            byte[] buffer = new byte[65536];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entryName.equals(entry.getName())) {
                    File parent = dest.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    FileOutputStream fos = new FileOutputStream(dest);
                    try {
                        int n;
                        while ((n = zip.read(buffer)) >= 0) {
                            fos.write(buffer, 0, n);
                        }
                    } finally {
                        fos.close();
                    }
                    return;
                }
                zip.closeEntry();
            }
            throw new IOException("entry missing: " + entryName);
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                }
            } else if (assets != null) {
                try {
                    assets.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void symlink(String target, String path) {
        try {
            new File(path).delete();
            android.system.Os.symlink(target, path);
        } catch (Exception e) {
            Log.e(TAG, "symlink failed: " + path + " -> " + target);
        }
    }

    private static String readSmallFile(File file) {
        if (!file.isFile()) {
            return "";
        }
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            try {
                byte[] buffer = new byte[64];
                int n = fis.read(buffer);
                if (n <= 0) {
                    return "";
                }
                return new String(buffer, 0, n, "UTF-8").trim();
            } finally {
                fis.close();
            }
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private static void writeSmallFile(File file, String value) {
        try {
            FileOutputStream fos = new FileOutputStream(file);
            try {
                fos.write(value.getBytes("UTF-8"));
            } finally {
                fos.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "cannot write " + file);
        }
    }

    /** Stable server credentials owned by the app (survive restarts). */
    private void ensureServiceJson() throws IOException {
        File configDir = new File(homeDir(), ".config/opencode");
        configDir.mkdirs();
        File serviceJson = new File(configDir, "service.json");
        if (serviceJson.isFile()) {
            return;
        }
        String password = randomPassword();
        String json = "{\"hostname\":\"127.0.0.1\","
                + "\"port\":" + API_PORT + ","
                + "\"password\":\"" + password + "\","
                + "\"cors\":[\"http://127.0.0.1:" + UI_PORT + "\"]}";
        FileOutputStream fos = new FileOutputStream(serviceJson);
        try {
            fos.write(json.getBytes("UTF-8"));
        } finally {
            fos.close();
        }
        Log.i(TAG, "service credentials created");
    }

    private static String randomPassword() {
        byte[] bytes = new byte[24];
        new java.security.SecureRandom().nextBytes(bytes);
        return android.util.Base64.encodeToString(
                bytes,
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP);
    }

    /** Guest prefix the Termux packages were compiled for (aarch64). */
    private static final String GUEST_PREFIX = "/data/data/com.termux/files/usr";
    private static final String GUEST_HOME = "/data/data/com.termux/files/home";
    /**
     * The single managed server binary. Lives in usr/lib: never on PATH,
     * so the terminal has no `opencode` command and nothing nested can
     * start from there. The server runs it by absolute path.
     */
    private static final String LIVE_NAME = "opencode-server";

    /**
     * Canonical Termux-compatible entry point (files/bin/oc-shell).
     * Everything - interactive shells, pkg, the opencode server - runs
     * through here, so all of them see the same guest. Layout mirrors
     * termux-chroot, minus the /data bind: exposing host /data would let
     * the guest resolve /data/data/com.termux/... from a foreign prefix
     * (or fail on permissions) instead of our own files/usr. Only system,
     * storage and our own binds are visible.
     * Never fails with a proot dump: when the guest is broken it falls
     * back to the system shell so chats keep working.
     */
    /**
     * Caches the host linker config inside our prefix so the guest always
     * sees a readable /linkerconfig/ld.config.txt. Without it every guest
     * exec prints a harmless-but-noisy linker warning to stderr (Android
     * 12+ restricts the host directory for app UIDs). Refreshed every boot
     * so an OTA can never leave stale content behind; removed when
     * unreadable so we never serve a stale copy.
     */
    private void cacheLinkerConfig() {
        File dest = new File(new File(files(), "usr/etc"), "ld.config.app.txt");
        FileInputStream fis = null;
        try {
            fis = new FileInputStream("/linkerconfig/ld.config.txt");
            ByteArrayOutputStream buf = new ByteArrayOutputStream(4096);
            byte[] chunk = new byte[8192];
            int total = 0, n;
            while ((n = fis.read(chunk)) >= 0) {
                total += n;
                if (total > 524288) {
                    throw new IOException("linker config too large");
                }
                buf.write(chunk, 0, n);
            }
            String text = buf.toString("UTF-8");
            if (text.length() < 16 || text.indexOf("dir.") < 0
                    || text.indexOf(0) >= 0) {
                throw new IOException("linker config looks invalid");
            }
            File parent = dest.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            FileOutputStream fos = new FileOutputStream(dest);
            try {
                fos.write(buf.toByteArray());
                fos.getFD().sync();
            } finally {
                fos.close();
            }
            Log.i(TAG, "linker config cached (" + total + " bytes)");
        } catch (Exception e) {
            Log.i(TAG, "linker config not cached: " + e.getMessage());
            dest.delete();
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void writeOcShell() {
        String libDir = getApplicationInfo().nativeLibraryDir;
        String script = "#!/system/bin/sh\n"
                + "# oc-shell: enter the bundled Termux userland.\n"
                + "B=$(dirname \"$0\")\n"
                + "case \"$B\" in /*) ;; *) B=\"$(pwd)/$B\";; esac\n"
                + "F=$(dirname \"$B\")\n"
                + "F=$(cd \"$F\" 2>/dev/null && pwd -P 2>/dev/null || echo \"$F\")\n"
                + "G=\"" + GUEST_PREFIX + "\"\n"
                + "if [ ! -x \"$F/usr/bin/login\" ]; then\n"
                + "  [ -x /system/bin/sh ] && exec /system/bin/sh \"$@\"\n"
                + "  echo \"oc-shell: guest missing\" >&2; exit 127\n"
                + "fi\n"
                + "P=\"$F/usr/bin/proot\"\n"
                + "if [ ! -x \"$P\" ]; then echo \"oc-shell: no proot\" >&2; exit 127; fi\n"
                + "mkdir -p \"$F/tmp\" \"$F/home\"\n"
                + "unset LD_PRELOAD\n"
                + "export PROOT_TMP_DIR=\"$F/tmp\" TMPDIR=\"$F/tmp\"\n"
                + "export PROOT_LOADER=\"$F/usr/libexec/proot/loader\"\n"
                + "export PROOT_LOADER_32=\"$F/usr/libexec/proot/loader32\"\n"
                + "export LD_LIBRARY_PATH=\"$F/usr/lib:$GUEST_PREFIX/lib\"\n"
                + "export TERMUX_HUSHLOGIN=1\n"
                + "A=\"--kill-on-exit\"\n"
                + "A=\"$A -b /system:/system\"\n"
                + "[ -d /vendor ] && A=\"$A -b /vendor:/vendor\"\n"
                + "[ -d /apex ] && A=\"$A -b /apex:/apex\"\n"
                + "LC=\"$F/usr/etc/ld.config.app.txt\"\n"
                + "if [ -r \"$LC\" ]; then A=\"$A -b $LC:/linkerconfig/ld.config.txt\";\n"
                + "elif [ -e /linkerconfig/ld.config.txt ]; then A=\"$A -b /linkerconfig/ld.config.txt:/linkerconfig/ld.config.txt\"; fi\n"
                + "[ -f /property_contexts ] && A=\"$A -b /property_contexts:/property_contexts\"\n"
                + "[ -d /storage ] && A=\"$A -b /storage:/storage\"\n"
                + "A=\"$A -b $F/usr:$G\"\n"
                + "A=\"$A -b $F/home:/data/data/com.termux/files/home\"\n"
                + "A=\"$A -b $F/home:/home\"\n"
                + "A=\"$A -b $F/tmp:/tmp\"\n"
                + "A=\"$A -b $F/usr:/usr\"\n"
                + "for f in bin etc lib share tmp var; do A=\"$A -b $F/usr/$f:/$f\"; done\n"
                + "for f in dev proc; do [ -e \"/$f\" ] && A=\"$A -b /$f:/$f\"; done\n"
                + "[ -d \"" + libDir + "\" ] && A=\"$A -b " + libDir + ":/opt/oc-lib\"\n"
                + "A=\"$A -r $F --cwd=/home\"\n"
                + "export HOME=\"/data/data/com.termux/files/home\"\n"
                + "export PREFIX=\"$G\"\n"
                + "export PATH=\"$G/bin:/system/bin:/system/xbin\"\n"
                + "export TERM=\"${TERM:-xterm-256color}\"\n"
                + "export LANG=\"en_US.UTF-8\"\n"
                + "export TERMINFO=\"$G/share/terminfo\"\n"
                + "export BUN_PTY_LIB=\"$G/lib/librust_pty_arm64.so\"\n"
                + "if [ $# = 0 ]; then\n"
                + "  exec \"$P\" $A \"$G/bin/login\"\n"
                + "else\n"
                + "  exec \"$P\" $A \"$G/bin/login\" \"$@\"\n"
                + "fi\n";
        File bin = new File(files(), "bin");
        bin.mkdirs();
        File shell = new File(bin, "oc-shell");
        try {
            FileOutputStream fos = new FileOutputStream(shell);
            try {
                fos.write(script.getBytes("UTF-8"));
            } finally {
                fos.close();
            }
            shell.setExecutable(true, false);
        } catch (IOException e) {
            Log.e(TAG, "cannot write oc-shell");
        }
        // Compat entry kept for older callers.
        symlink("oc-shell", new File(bin, "termux-sh").getAbsolutePath());
    }

    /**
     * Blocks until a guest command round-trips through oc-shell.
     * tries attempts, 5s apart. True when the terminal env is alive.
     */
    private boolean waitForShell(int tries) {
        File shell = new File(files(), "bin/oc-shell");
        for (int i = 0; i < tries && running; i++) {
            try {
                Process process = new ProcessBuilder(
                        shell.getAbsolutePath(), "-c", "echo OC-SHELL-OK")
                        .directory(homeDir())
                        .redirectErrorStream(true)
                        .start();
                StringBuilder out = new StringBuilder();
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(process.getInputStream()));
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (out.length() < 500) {
                            out.append(line).append('\n');
                        }
                    }
                } finally {
                    reader.close();
                }
                boolean done = false;
                try {
                    done = process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                if (done && process.exitValue() == 0
                        && out.toString().contains("OC-SHELL-OK")) {
                    Log.i(TAG, "shell alive");
                    return true;
                }
                Log.i(TAG, "shell probe " + i + ": exit="
                        + (done ? process.exitValue() : "timeout"));
            } catch (IOException e) {
                Log.i(TAG, "shell probe " + i + " failed: " + e);
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * Applies the official SYMLINKS.txt manifest (TARGET←LINK per line).
     * The bootstrap zip stores no zip-level symlinks; without this, applet
     * and versioned-library links are all missing. Create-only: existing
     * links belong to apt after upgrades and are never overwritten here
     * (see healSonameLinks for drifted ones).
     */
    private void applySymlinksTxt(File usr) {
        File manifest = new File(usr, "SYMLINKS.txt");
        if (!manifest.isFile()) {
            return;
        }
        int done = 0;
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(manifest);
            try {
                byte[] buffer = new byte[(int) Math.min(manifest.length(), 1 << 20)];
                int n = fis.read(buffer);
                if (n <= 0) {
                    return;
                }
                String[] lines = new String(buffer, 0, n, "UTF-8").split("\n");
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i].trim();
                    if (line.length() == 0) {
                        continue;
                    }
                    int arrow = line.indexOf('←');
                    if (arrow < 0) {
                        continue;
                    }
                    String target = line.substring(0, arrow);
                    String link = line.substring(arrow + 1);
                    if (link.startsWith("./")) {
                        link = link.substring(2);
                    }
                    if (target.length() == 0 || link.length() == 0
                            || link.contains("..")) {
                        continue;
                    }
                    try {
                        File linkFile = canonicalChild(usr, link);
                        if (linkFile.exists()
                                || java.nio.file.Files.isSymbolicLink(
                                        linkFile.toPath())) {
                            continue;
                        }
                        File parent = linkFile.getParentFile();
                        if (parent != null) {
                            parent.mkdirs();
                        }
                        android.system.Os.symlink(target, linkFile.getAbsolutePath());
                        done++;
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                fis.close();
            }
        } catch (Exception e) {
            Log.e(TAG, "symlinks manifest failed", e);
        }
        Log.i(TAG, "symlinks applied: " + done);
    }

    private void extractWwwOnce() throws IOException {
        File www = new File(files(), "www");
        File marker = new File(www, ".apkbuild");
        String build = "19";
        if (marker.isFile()) {
            // Best effort: re-extract when the marker differs.
            try {
                java.util.Scanner scanner = new java.util.Scanner(marker, "UTF-8");
                try {
                    String current = scanner.hasNext() ? scanner.next() : "";
                    if (build.equals(current) && new File(www, "index.html").isFile()) {
                        scanner.close();
                        return;
                    }
                } finally {
                    scanner.close();
                }
            } catch (IOException ignored) {
            }
        }
        deleteRecursive(www);
        www.mkdirs();
        InputStream assets = null;
        ZipInputStream zip = null;
        try {
            assets = getAssets().open("www.zip");
            zip = new ZipInputStream(assets);
            byte[] buffer = new byte[32768];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File out = canonicalChild(www, entry.getName());
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    copyStreamToFile(zip, out, buffer);
                }
                zip.closeEntry();
            }
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                }
            } else if (assets != null) {
                try {
                    assets.close();
                } catch (IOException ignored) {
                }
            }
        }
        FileWriter writer = new FileWriter(marker);
        try {
            writer.write(build);
        } finally {
            writer.close();
        }
        Log.i(TAG, "web UI extracted");
    }

    private static void deleteRecursive(File file) {
        try {
            // Never follow symlinks: delete the link itself.
            if (Files.isSymbolicLink(file.toPath())) {
                file.delete();
                return;
            }
        } catch (Exception ignored) {
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (int i = 0; i < children.length; i++) {
                    deleteRecursive(children[i]);
                }
            }
        }
        file.delete();
    }

    private static final String APT_BUILD = "3";

    /**
     * Package setup in the background, after the server is already up:
     * hope repo + guest proot/git/curl, all inside the same terminal env
     * the server uses. Never blocks boot; retries next start when the
     * marker is absent. The opencode updater runs every start regardless
     * of the marker: it is self-gating (a couple of small feed fetches
     * when current, download only when a newer release exists).
     */
    private void startAptBackground() {
        File shell = new File(files(), "bin/oc-shell");
        File bash = new File(files(), "usr/bin/bash");
        if (!shell.isFile() || !bash.isFile()) {
            Log.i(TAG, "apt phase deferred: shell not ready");
            return;
        }
        File marker = new File(files(), ".apt2");
        if (!APT_BUILD.equals(readSmallFile(marker))) {
            Thread apt = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        runAptPhaseBlocking();
                    } catch (Exception e) {
                        Log.e(TAG, "apt phase failed, will retry", e);
                    }
                }
            }, "oc-apt");
            apt.setDaemon(true);
            apt.start();
            return;
        }
        Thread upd = new Thread(new Runnable() {
            @Override
            public void run() {
                updateOpencodeDeb();
            }
        }, "oc-upd");
        upd.setDaemon(true);
        upd.start();
    }

    /** Body of the background apt phase (see startAptBackground). */
    private void runAptPhaseBlocking() {
        File marker = new File(files(), ".apt2");
        if (APT_BUILD.equals(readSmallFile(marker))) {
            return;
        }
        File shell = new File(files(), "bin/oc-shell");
        File bash = new File(files(), "usr/bin/bash");
        if (!shell.isFile() || !bash.isFile()) {
            Log.i(TAG, "apt phase deferred: shell not ready");
            return;
        }
        File listDir = new File(files(), "usr/etc/apt/sources.list.d");
        listDir.mkdirs();
        writeSmallFile(new File(listDir, "hope333.list"),
                "deb [trusted=yes arch=aarch64] https://hope2333.github.io/repo/Termux/apt/ ./\n");
        // Tolerates dead sibling repos (|| true): one 404 must not abort
        // the whole phase. Base tools via apt; opencode itself comes from
        // its release feed (see updateOpencodeDeb) so it never downgrades
        // through a stale apt pin. Dir::Cache keeps downloads inside our
        // prefix: the compiled-in Termux cache path is unreachable here.
        String cache = " -o Dir::Cache=/data/data/com.termux/files/usr/var/cache/apt";
        String script = "export DEBIAN_FRONTEND=noninteractive;"
                + " mkdir -p /data/data/com.termux/files/usr/var/cache/apt/archives/partial;"
                + " dpkg --configure -a;"
                + " apt-get clean;"
                + " apt-mark hold opencode >/dev/null 2>&1;"
                + " apt-get update" + cache
                + " -o Acquire::AllowInsecureRepositories=true"
                + " -o Acquire::AllowDowngradeToInsecureRepositories=true || true;"
                + " apt-get install -y" + cache
                + " -o Dpkg::Options::=--force-confdef"
                + " -o Dpkg::Options::=--force-confold"
                + " proot git curl openssl ca-certificates hope2333-mirrorlist || true;"
                + " apt-get update" + cache
                + " -o Acquire::AllowInsecureRepositories=true"
                + " -o Acquire::AllowDowngradeToInsecureRepositories=true || true;";
        writeBoot("packages", "apt update + install proot git curl");
        try {
            String tail = runGuest(script, 15 * 60 * 1000);
            Log.i(TAG, "apt done: " + tail);
            writeSmallFile(marker, APT_BUILD);
            writeSmallFile(new File(files(), ".aptstamp"),
                    String.valueOf(System.currentTimeMillis()));
            hideAptOpencode();
            updateOpencodeDeb();
        } catch (Exception e) {
            writeBoot("packages", "apt failed, retrying in background");
            throw new RuntimeException(e);
        }
    }

    /**
     * opencode straight from its release feed: newest Push tag -> its
     * SHA256SUMS -> best native v2 deb -> install only when newer than
     * both the pkg-managed and the bundled binaries (never downgrades).
     * Downloading happens in Java on purpose: the guest curl may be
     * broken (library skew) exactly when an update is needed. The guest
     * only verifies and installs (dpkg + sha256sum have no ssl deps).
     * Best effort: any hiccup just keeps the current binary.
     */
    private void updateOpencodeDeb() {
        try {
            updateOpencodeDebInner();
        } catch (Exception e) {
            Log.e(TAG, "opencode update failed", e);
            recordUpdateStatus("failed: " + e);
        }
    }

    private void updateOpencodeDebInner() throws Exception {
        String feed = httpGetText(
                "https://github.com/Hope2333/opencode-termux/tags.atom",
                60000, 65536);
        String tag = newestPushTag(feed);
        if (tag == null) {
            recordUpdateStatus("no tag in feed");
            return;
        }
        String sums = httpGetText(
                "https://github.com/Hope2333/opencode-termux/releases/download/"
                        + tag + "/SHA256SUMS.txt",
                60000, 65536);
        String[] best = bestNativeDeb(sums);
        if (best == null) {
            recordUpdateStatus("no native deb in " + tag);
            return;
        }
        String liveVer = versionOf(probeGuestVersion(
                GUEST_PREFIX + "/lib/" + LIVE_NAME));
        String bundledVer = versionOf(nativeOpencodeVersion());
        String cur = compareVersions(liveVer, bundledVer) >= 0
                ? liveVer : bundledVer;
        if (compareVersions(best[0], cur) <= 0) {
            recordUpdateStatus("current " + cur + " >= " + best[0]
                    + " (" + tag + ")");
            return;
        }
        if (!best[1].matches("[A-Za-z0-9._-]+")) {
            recordUpdateStatus("bad filename");
            return;
        }
        // Replace, never pile up: need room for deb + unpacked binary.
        if (new File(files(), "home").getUsableSpace() < 500L * 1024 * 1024) {
            recordUpdateStatus("low disk, want " + best[0] + ", skipped");
            return;
        }
        File dir = new File(homeDir(), ".cache/oc-upd");
        dir.mkdirs();
        File deb = new File(dir, best[1]);
        httpDownload(
                "https://github.com/Hope2333/opencode-termux/releases/download/"
                        + tag + "/" + best[1],
                deb, 600000L, 400L * 1024 * 1024);
        if (!sha256Hex(deb).equalsIgnoreCase(best[2])) {
            deb.delete();
            recordUpdateStatus("sha mismatch for " + best[1]);
            return;
        }
        // Extract-only: no dpkg install, nothing lands on PATH.
        // The swap into the live location happens at boot (restart).
        String script = "cd ~/.cache/oc-upd;"
                + " rm -rf stage;"
                + " dpkg-deb -x \"" + best[1] + "\" stage"
                + " || { echo \"extract failed\"; exit 0; };"
                + " stage/data/data/com.termux/files/usr/bin/opencode --version"
                + " || { echo \"staged binary dead\"; exit 0; };";
        String tail = runGuest(script, 5 * 60 * 1000);
        String stagedVer = versionOf(tail);
        if (!stagedVer.equals(best[0])) {
            runGuest("rm -rf ~/.cache/oc-upd/stage", 60000);
            recordUpdateStatus("staged binary reports " + stagedVer
                    + ", want " + best[0]);
            return;
        }
        File staged = new File(files(), ".oc-staged");
        deleteRecursive(staged);
        staged.mkdirs();
        File bin = new File(homeDir(), ".cache/oc-upd/stage/data/data/com.termux/files/usr/bin/opencode");
        if (!bin.isFile() || !bin.renameTo(new File(staged, "bin"))) {
            recordUpdateStatus("stage move failed");
            return;
        }
        writeSmallFile(new File(staged, "ver"), best[0]);
        runGuest("rm -rf ~/.cache/oc-upd", 60000);
        Log.i(TAG, "opencode staged: " + best[0]);
        recordUpdateStatus("staged " + best[0] + " (" + tag
                + ") - applies after restart");
    }

    /** Reads a small https document, following redirects, capped. */
    private static String httpGetText(String url, int timeoutMs, int maxBytes)
            throws IOException {
        java.net.HttpURLConnection connection = null;
        String current = url;
        for (int hop = 0; hop < 5; hop++) {
            connection = (java.net.HttpURLConnection)
                    new URL(current).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs);
            connection.setRequestProperty("User-Agent", "OpenCode-android");
            int code = connection.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307
                    || code == 308) {
                String next = connection.getHeaderField("Location");
                connection.disconnect();
                if (next == null || next.length() == 0) {
                    throw new IOException("redirect without location");
                }
                current = next;
                continue;
            }
            if (code != 200) {
                throw new IOException("http " + code);
            }
            InputStream in = connection.getInputStream();
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, n);
                    if (out.size() > maxBytes) {
                        throw new IOException("response too large");
                    }
                }
                return out.toString("UTF-8");
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
                connection.disconnect();
            }
        }
        throw new IOException("too many redirects");
    }

    /** Downloads a file, following redirects, with a size cap. */
    private static void httpDownload(String url, File dest, long timeoutMs,
            long maxBytes) throws IOException {
        String current = url;
        for (int hop = 0; hop < 5; hop++) {
            java.net.HttpURLConnection connection =
                    (java.net.HttpURLConnection) new URL(current).openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "OpenCode-android");
            int code = connection.getResponseCode();
            if (code == 301 || code == 302 || code == 303 || code == 307
                    || code == 308) {
                String next = connection.getHeaderField("Location");
                connection.disconnect();
                if (next == null || next.length() == 0) {
                    throw new IOException("redirect without location");
                }
                current = next;
                continue;
            }
            if (code != 200) {
                connection.disconnect();
                throw new IOException("http " + code);
            }
            InputStream in = connection.getInputStream();
            long deadline = System.currentTimeMillis() + timeoutMs;
            try {
                FileOutputStream fos = new FileOutputStream(dest);
                boolean ok = false;
                try {
                    byte[] buffer = new byte[65536];
                    long total = 0;
                    int n;
                    while ((n = in.read(buffer)) >= 0) {
                        if (System.currentTimeMillis() > deadline) {
                            throw new IOException("download timed out");
                        }
                        total += n;
                        if (total > maxBytes) {
                            throw new IOException("file too large");
                        }
                        fos.write(buffer, 0, n);
                    }
                    fos.getFD().sync();
                    ok = true;
                } finally {
                    try {
                        fos.close();
                    } catch (IOException ignored) {
                    }
                    if (!ok) {
                        dest.delete();
                    }
                }
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
                connection.disconnect();
            }
            return;
        }
        throw new IOException("too many redirects");
    }

    private static String sha256Hex(File file) throws IOException {
        try {
            java.security.MessageDigest digest =
                    java.security.MessageDigest.getInstance("SHA-256");
            FileInputStream fis = new FileInputStream(file);
            try {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = fis.read(buffer)) >= 0) {
                    digest.update(buffer, 0, n);
                }
            } finally {
                fis.close();
            }
            byte[] hash = digest.digest();
            StringBuilder out = new StringBuilder(hash.length * 2);
            for (int i = 0; i < hash.length; i++) {
                int v = hash[i] & 0xff;
                if (v < 16) {
                    out.append('0');
                }
                out.append(Integer.toHexString(v));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("no sha256");
        }
    }

    /** Newest Push tag from the release feed, or null. */
    private static String newestPushTag(String feed) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "releases/tag/(Push\\d+)").matcher(feed);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * Best native v2 deb from a SHA256SUMS listing.
     * Returns {version, filename, sha} or null. Skips wrapper/compressed/
     * standalone/glibc/v1 families by strict name shape.
     */
    private static String[] bestNativeDeb(String sums) {
        String bestVer = null;
        String bestFile = null;
        String bestSha = null;
        String[] lines = sums.split("\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.length() == 0 || line.charAt(0) == '#') {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            String name = parts[parts.length - 1];
            if (name.startsWith("*")) {
                name = name.substring(1);
            }
            if (!name.matches("opencode_[0-9][A-Za-z0-9.+-]*_aarch64\\.deb")) {
                continue;
            }
            String ver = name.substring("opencode_".length(),
                    name.length() - "_aarch64.deb".length());
            if (bestVer == null || compareVersions(ver, bestVer) > 0) {
                bestVer = ver;
                bestFile = name;
                bestSha = parts[0];
            }
        }
        if (bestVer == null) {
            return null;
        }
        return new String[]{bestVer, bestFile, bestSha};
    }

    /** Numeric dot-version compare: >0 when a is newer. */
    private static int compareVersions(String a, String b) {
        String[] pa = a.split("[^0-9]+");
        String[] pb = b.split("[^0-9]+");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            long va = 0;
            long vb = 0;
            try {
                va = i < pa.length && pa[i].length() > 0
                        ? Long.parseLong(pa[i]) : 0;
            } catch (NumberFormatException ignored) {
            }
            try {
                vb = i < pb.length && pb[i].length() > 0
                        ? Long.parseLong(pb[i]) : 0;
            } catch (NumberFormatException ignored) {
            }
            if (va != vb) {
                return va < vb ? -1 : 1;
            }
        }
        return 0;
    }

    /** Strips channel output like "opencode v2.0.27" down to "2.0.27". */
    private static String versionOf(String raw) {
        if (raw == null) {
            return "0";
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "(\\d[\\d.]*)").matcher(raw);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "0";
    }

    /** Guest binary version line ("opencode vX" / ""), via oc-shell. */
    private String probeGuestVersion(String guestPath) {
        try {
            Process probe = new ProcessBuilder(
                    new File(files(), "bin/oc-shell").getAbsolutePath(),
                    "-c", "\"" + guestPath + "\" --version 2>/dev/null"
                            + " || echo missing")
                    .directory(homeDir())
                    .redirectErrorStream(true)
                    .start();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(probe.getInputStream()));
            try {
                StringBuilder out = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line);
                }
                boolean done = false;
                try {
                    done = probe.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (done && probe.exitValue() == 0) {
                    return out.toString().trim();
                }
            } finally {
                reader.close();
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    /** Bundled native binary version, run directly (bionic, no guest). */
    private String nativeOpencodeVersion() {
        try {
            String libDir = getApplicationInfo().nativeLibraryDir;
            ProcessBuilder builder = new ProcessBuilder(
                    libDir + "/libopencode.so", "--version");
            builder.directory(homeDir());
            builder.redirectErrorStream(true);
            Map<String, String> env = builder.environment();
            env.put("LD_LIBRARY_PATH", libDir);
            Process probe = builder.start();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(probe.getInputStream()));
            try {
                StringBuilder out = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line);
                }
                boolean done = false;
                try {
                    done = probe.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (done && probe.exitValue() == 0) {
                    return out.toString().trim();
                }
            } finally {
                reader.close();
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    /**
     * Human-readable updater status the user (and the chat) can read at
     * ~/.cache/oc-update.log inside the terminal env.
     */
    private void recordUpdateStatus(String tail) {
        try {
            File dir = new File(homeDir(), ".cache");
            dir.mkdirs();
            String live = probeGuestVersion(
                    GUEST_PREFIX + "/lib/" + LIVE_NAME);
            if (live.length() == 0) {
                live = nativeOpencodeVersion();
            }
            if (live.length() == 0) {
                live = "missing";
            }
            String body = "time=" + new java.util.Date(System.currentTimeMillis())
                    + "\nlive=" + live + "\n" + tail;
            if (body.length() > 3000) {
                body = body.substring(body.length() - 3000);
            }
            FileOutputStream fos = new FileOutputStream(
                    new File(dir, "oc-update.log"));
            try {
                fos.write(body.getBytes("UTF-8"));
            } finally {
                fos.close();
            }
        } catch (IOException e) {
            Log.i(TAG, "cannot record update status");
        }
    }

    /**
     * Boot-time promotion (the ONLY moment updates apply): a staged binary
     * replaces the live one, never beside it. Also heals the old dpkg era:
     * a pkg-managed /usr/bin/opencode is removed so the terminal has no
     * `opencode` command and only one copy exists. Runs before the server
     * loop, never while the server runs.
     */
    private void promoteStaged() {
        try {
            // Old era cleanup: dpkg-owned copy on PATH.
            String hasPkg = runGuest(
                    "dpkg-query -W opencode 2>/dev/null || echo none",
                    60000).trim();
            if (hasPkg.startsWith("opencode")) {
                Log.i(TAG, "removing pkg opencode, single-copy layout");
                runGuest("export DEBIAN_FRONTEND=noninteractive;"
                        + " dpkg -r opencode 2>&1 | tail -n 2; exit 0", 120000);
            }
        } catch (Exception e) {
            Log.i(TAG, "pkg cleanup: " + e);
        }
        try {
            // Stray binary on PATH without a package: adopt or drop.
            File stray = new File(files(), "usr/bin/opencode");
            File live = new File(files(), "usr/lib/" + LIVE_NAME);
            if (stray.isFile() && !live.isFile()) {
                String strayVer = versionOf(probeGuestVersion(
                        GUEST_PREFIX + "/bin/opencode"));
                String bundledVer = versionOf(nativeOpencodeVersion());
                if (strayVer.length() > 0 && bundledVer.length() > 0
                        && compareVersions(strayVer, bundledVer) > 0) {
                    if (stray.renameTo(live)) {
                        live.setExecutable(true, false);
                        Log.i(TAG, "adopted stray opencode " + strayVer);
                    }
                } else {
                    stray.delete();
                }
            } else if (stray.isFile()) {
                stray.delete();
            }
        } catch (Exception e) {
            Log.i(TAG, "stray cleanup: " + e);
        }
        try {
            File staged = new File(files(), ".oc-staged");
            File bin = new File(staged, "bin");
            File verFile = new File(staged, "ver");
            if (bin.isFile() && verFile.isFile()) {
                String stagedVer = readSmallFile(verFile);
                File live = new File(files(), "usr/lib/" + LIVE_NAME);
                String liveVer = live.isFile() ? versionOf(probeGuestVersion(
                        GUEST_PREFIX + "/lib/" + LIVE_NAME)) : "0";
                String bundledVer = versionOf(nativeOpencodeVersion());
                String best = compareVersions(liveVer, bundledVer) >= 0
                        ? liveVer : bundledVer;
                if (stagedVer.length() > 0
                        && compareVersions(stagedVer, best) > 0) {
                    live.delete();
                    if (bin.renameTo(live)) {
                        live.setExecutable(true, false);
                        Log.i(TAG, "promoted opencode " + stagedVer);
                    }
                } else {
                    Log.i(TAG, "staged " + stagedVer + " not newer, dropped");
                }
            }
            deleteRecursive(staged);
        } catch (Exception e) {
            Log.i(TAG, "promote: " + e);
        }
        try {
            // No deb leftovers, no staged cruft: replace, don't pile up.
            runGuest("rm -rf ~/.cache/oc-upd", 60000);
        } catch (Exception ignored) {
        }
        new File(files(), ".opencode-bin").delete();
    }

    /**
     * Keeps `opencode` out of the terminal PATH: holds the apt package so
     * upgrades never reinstall it, and diverts any present copy away.
     * The managed server runs by absolute path and is unaffected.
     * Never throws (best effort).
     */
    private void hideAptOpencode() {
        try {
            String frag = "G=/data/data/com.termux/files/usr;"
                    + " apt-mark hold opencode >/dev/null 2>&1;"
                    + " if [ -e \"$G/bin/opencode\" ] || [ -L \"$G/bin/opencode\" ]; then"
                    + " dpkg-divert --add --rename --divert \"$G/bin/.opencode-hidden\""
                    + " \"$G/bin/opencode\" >/dev/null 2>&1 ||"
                    + " mv -f \"$G/bin/opencode\" \"$G/bin/.opencode-hidden\" 2>/dev/null;"
                    + " fi; exit 0;";
            runGuest(frag, 120000);
        } catch (Exception e) {
            Log.i(TAG, "hide opencode: " + e);
        }
    }

    /**
     * Daily auto-update, in the background, never blocking boot:
     * refreshes lists and upgrades only when something is upgradable,
     * then stages a newer opencode (applied at next restart).
     * The bundled web UI (www.zip) is versioned separately and untouched.
     */
    private void startAutoUpdate() {
        Thread auto = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(60 * 1000);
                } catch (InterruptedException e) {
                    return;
                }
                if (!running) {
                    return;
                }
                try {
                    long now = System.currentTimeMillis();
                    long stamp = 0;
                    try {
                        stamp = Long.parseLong(readSmallFile(
                                new File(files(), ".aptstamp")));
                    } catch (NumberFormatException ignored) {
                    }
                    if (now - stamp < 24L * 60 * 60 * 1000) {
                        return;
                    }
                    Log.i(TAG, "auto-update: checking");
                    String cache = " -o Dir::Cache=/data/data/com.termux/files/usr/var/cache/apt";
                    String script = "export DEBIAN_FRONTEND=noninteractive;"
                            + " mkdir -p /data/data/com.termux/files/usr/var/cache/apt/archives/partial;"
                            + " apt-get clean;"
                            + " apt-mark hold opencode >/dev/null 2>&1;"
                            + " apt-get update -qq" + cache + ";"
                            + " n=$(apt list --upgradable 2>/dev/null | grep -c upgradable);"
                            + " if [ \"$n\" -gt 0 ]; then"
                            + " apt-get upgrade -y" + cache
                            + " -o Dpkg::Options::=--force-confdef"
                            + " -o Dpkg::Options::=--force-confold;"
                            + " echo UPGRADED;"
                            + " else echo CLEAN; fi";
                    String tail = runGuest(script, 20 * 60 * 1000);
                    Log.i(TAG, "auto-update done: " + tail);
                    writeSmallFile(new File(files(), ".aptstamp"),
                            String.valueOf(System.currentTimeMillis()));
                    hideAptOpencode();
                    updateOpencodeDeb();
                } catch (Exception e) {
                    Log.e(TAG, "auto-update failed", e);
                }
            }
        }, "oc-autoupdate");
        auto.setDaemon(true);
        auto.start();
    }

    /**
     * Runs a guest shell fragment through oc-shell, returns the tail of
     * its output. Throws when the guest exits non-zero or times out.
     */
    private String runGuest(String fragment, long timeoutMs) throws Exception {
        String shell = new File(files(), "bin/oc-shell").getAbsolutePath();
        Process process = new ProcessBuilder(shell, "-c", fragment)
                .directory(homeDir())
                .redirectErrorStream(true)
                .start();
        StringBuilder tail = new StringBuilder();
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
        try {
            String line;
            while ((line = reader.readLine()) != null) {
                if (tail.length() < 2000) {
                    tail.append(line).append('\n');
                }
                if (line.length() < 200) {
                    Log.i(TAG, "guest: " + line);
                }
            }
        } finally {
            reader.close();
        }
        boolean done = false;
        try {
            done = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (!done) {
            process.destroy();
            throw new IOException("guest timed out");
        }
        int code = process.exitValue();
        if (code != 0) {
            throw new IOException("guest exit " + code + ": " + tail);
        }
        return tail.toString();
    }

    /**
     * Server start: the single managed live binary (absolute path, never
     * on PATH), else the APK-bundled fallback. Both run DIRECTLY in the
     * host mount namespace (never inside proot): the app passes absolute
     * host paths everywhere (home, projects, pty cwd) and proot would hide
     * them, breaking home listing, sessions and terminal spawns. The
     * terminal still lands in the guest via SHELL=oc-shell + BUN_PTY_LIB.
     * The choice is fixed for the whole run: staged updates only promote
     * at boot (restart).
     */
    private void runServerOnce() throws IOException, InterruptedException {
        if (adoptRunningServer()) {
            return;
        }
        if (!waitForPortFree()) {
            // Still busy (unkillable holder): do NOT spawn a server that
            // would self-assign another port while the UI stays pinned
            // here. Retry next loop iteration.
            Log.i(TAG, "port still busy, retrying later");
            Thread.sleep(10000);
            return;
        }
        File live = new File(files(), "usr/lib/" + LIVE_NAME);
        if (live.isFile() && live.canExecute()) {
            runServerDirectLive(live.getAbsolutePath());
        } else {
            runServerDirect();
        }
    }

    /** Server version we run this boot (live staged or bundled fallback). */
    private volatile String expectedServerVersion = "";

    /**
     * If the API port already answers with OUR password, someone (stale
     * process, nested launch from the terminal) is serving our home dir.
     * Adopt it only when its version matches ours AND its model list
     * works; a version mismatch is always reclaimed by pid. A matching
     * but slow (plugin activation) server is adopted, never murdered:
     * killing a healthy slow starter would wedge models forever.
     * Returns true when adopted (monitored until it goes away).
     */
    private boolean adoptRunningServer() {
        String password = readServicePassword();
        if (password == null) {
            return false;
        }
        String[] info = apiInfo(password);
        if (info == null) {
            // Port may still be busy with a foreign (other-password)
            // holder: try to reclaim by command line, never spawn blind
            // (that would self-assign another port = drift).
            if (portBusy()) {
                Log.i(TAG, "port busy, unauthenticated holder, reclaiming");
                killForeignServers();
                sleepQuiet(5000);
            }
            return false;
        }
        String runningVer = info[0] == null ? "" : info[0];
        String pid = info[1] == null ? "" : info[1];
        if (expectedServerVersion.length() == 0) {
            expectedServerVersion = resolveExpectedVersion();
        }
        if (expectedServerVersion.length() > 0
                && runningVer.length() > 0
                && !runningVer.equals(expectedServerVersion)) {
            Log.i(TAG, "foreign server on port (ver=" + runningVer
                    + " pid=" + pid + "), reclaiming");
            killPid(pid);
            return false;
        }
        // Same (or unknown) version: wait for a working model list, with
        // grace for cold plugin activation, then monitor. A slow starter
        // is adopted, not killed.
        if (!apiModelReady(password)) {
            Log.i(TAG, "server models not ready yet, monitoring anyway");
        }
        Log.i(TAG, "adopted already-running server " + runningVer);
        try {
            while (running && apiInfoAnswers(password)) {
                Thread.sleep(5000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    private void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** True when something accepts TCP on the API port right now. */
    private boolean portBusy() {
        java.net.Socket socket = null;
        try {
            socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress("127.0.0.1",
                    API_PORT), 2000);
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Kills same-UID processes whose command line looks like an opencode
     * server bound to our API port. Used only when the port answers
     * without our password (so it cannot be our tracked child, which
     * shares the password). Never kills PID 1, self, or non-matching.
     */
    private void killForeignServers() {
        long self = 0;
        try {
            self = android.os.Process.myPid();
        } catch (Exception ignored) {
        }
        File proc = new File("/proc");
        File[] pids = proc.listFiles();
        if (pids == null) {
            return;
        }
        for (int i = 0; i < pids.length; i++) {
            String name = pids[i].getName();
            if (!name.matches("[0-9]+")) {
                continue;
            }
            long pid;
            try {
                pid = Long.parseLong(name);
            } catch (NumberFormatException e) {
                continue;
            }
            if (pid <= 1 || pid == self) {
                continue;
            }
            try {
                java.io.FileInputStream fis =
                        new java.io.FileInputStream(new File(pids[i], "cmdline"));
                byte[] buffer = new byte[1024];
                int n;
                StringBuilder cmd = new StringBuilder();
                try {
                    while ((n = fis.read(buffer)) >= 0 && cmd.length() < 1024) {
                        for (int b = 0; b < n; b++) {
                            cmd.append(buffer[b] == 0 ? ' ' : (char) buffer[b]);
                        }
                    }
                } finally {
                    fis.close();
                }
                String line = cmd.toString();
                if (line.contains("opencode") && line.contains("serve")
                        && line.contains(String.valueOf(API_PORT))) {
                    Log.i(TAG, "killing foreign server pid=" + pid);
                    killPid(String.valueOf(pid));
                }
            } catch (Exception ignored) {
            }
        }
    }

    /** True once GET /api/model answers 200 (plugin activation grace). */
    private boolean apiModelReady(String password) {
        for (int i = 0; i < 12 && running; i++) {
            if (apiGetBody(password, "/api/model") != null) {
                return true;
            }
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return apiGetBody(password, "/api/model") != null;
    }

    private String apiGetBody(String password, String path) {
        java.net.HttpURLConnection connection = null;
        try {
            java.net.URL url = new java.net.URL("http://127.0.0.1:"
                    + API_PORT + path);
            connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(10000);
            connection.setRequestMethod("GET");
            String raw = "opencode:" + password;
            connection.setRequestProperty("Authorization", "Basic "
                    + android.util.Base64.encodeToString(raw.getBytes("UTF-8"),
                            android.util.Base64.NO_WRAP));
            if (connection.getResponseCode() != 200) {
                return null;
            }
            java.io.InputStream in = connection.getInputStream();
            StringBuilder body = new StringBuilder();
            try {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = in.read(buffer)) >= 0 && body.length() < 65536) {
                    body.append(new String(buffer, 0, n, "UTF-8"));
                }
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            return body.toString();
        } catch (IOException e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Keeps .oc-version (and the adoption gate) equal to the RUNNING
     * server, not just the probed one: polls authed /api/info and
     * overwrites the file whenever it reports a strict X.Y.Z.
     */
    private void startVersionSync() {
        Thread sync = new Thread(new Runnable() {
            @Override
            public void run() {
                while (running) {
                    try {
                        Thread.sleep(15000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!running) {
                        return;
                    }
                    try {
                        String password = readServicePassword();
                        if (password == null) {
                            continue;
                        }
                        String[] info = apiInfo(password);
                        if (info == null || info[0] == null) {
                            continue;
                        }
                        String ver = info[0].trim();
                        if (!ver.matches("\\d+\\.\\d+\\.\\d+")) {
                            continue;
                        }
                        expectedServerVersion = ver;
                        File vers = new File(files(), ".oc-version");
                        if (!ver.equals(readSmallFile(vers))) {
                            writeSmallFile(vers, ver);
                            Log.i(TAG, "version sync: " + ver);
                        }
                    } catch (Exception e) {
                        Log.i(TAG, "version sync: " + e);
                    }
                }
            }
        }, "oc-versync");
        sync.setDaemon(true);
        sync.start();
    }

    /** Best known server version for this boot (live staged or bundled). */
    private String resolveExpectedVersion() {
        try {
            File live = new File(files(), "usr/lib/" + LIVE_NAME);
            String liveVer = live.isFile() ? versionOf(probeGuestVersion(
                    GUEST_PREFIX + "/lib/" + LIVE_NAME)) : "0";
            String bundledVer = versionOf(nativeOpencodeVersion());
            String best = compareVersions(liveVer, bundledVer) >= 0
                    ? liveVer : bundledVer;
            Log.i(TAG, "expected server version: " + best
                    + " (live=" + liveVer + " bundled=" + bundledVer + ")");
            return best;
        } catch (Exception e) {
            Log.i(TAG, "expected version probe: " + e);
            return "";
        }
    }

    private void killPid(String pid) {
        if (pid == null || !pid.matches("[0-9]+")) {
            return;
        }
        try {
            long self = android.os.Process.myPid();
            if (Long.parseLong(pid) <= 1 || Long.parseLong(pid) == self) {
                return;
            }
        } catch (NumberFormatException e) {
            return;
        }
        try {
            Process killer = new ProcessBuilder("/system/bin/sh", "-c",
                    "kill -9 " + pid).start();
            try {
                killer.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } catch (IOException e) {
            Log.i(TAG, "kill failed: " + e);
        }
    }

    private String readServicePassword() {
        File file = new File(new File(files(), "home/.config/opencode"), "service.json");
        if (!file.isFile()) {
            return null;
        }
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            try {
                byte[] buffer = new byte[(int) Math.min(file.length(), 1024)];
                int n = fis.read(buffer);
                if (n <= 0) {
                    return null;
                }
                String json = new String(buffer, 0, n, "UTF-8");
                org.json.JSONObject object = new org.json.JSONObject(json);
                String password = object.optString("password", "");
                return password.length() == 0 ? null : password;
            } finally {
                fis.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private boolean apiInfoAnswers(String password) {
        return apiInfo(password) != null;
    }

    /** Authed /api/info as {version, pid}, or null when not ours/alive. */
    private String[] apiInfo(String password) {
        java.net.HttpURLConnection connection = null;
        try {
            java.net.URL url = new java.net.URL("http://127.0.0.1:"
                    + API_PORT + "/api/info");
            connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("GET");
            String raw = "opencode:" + password;
            connection.setRequestProperty("Authorization", "Basic "
                    + android.util.Base64.encodeToString(raw.getBytes("UTF-8"),
                            android.util.Base64.NO_WRAP));
            if (connection.getResponseCode() != 200) {
                return null;
            }
            java.io.InputStream in = connection.getInputStream();
            StringBuilder body = new StringBuilder();
            try {
                byte[] buffer = new byte[1024];
                int n;
                while ((n = in.read(buffer)) >= 0 && body.length() < 2048) {
                    body.append(new String(buffer, 0, n, "UTF-8"));
                }
            } finally {
                try {
                    in.close();
                } catch (IOException ignored) {
                }
            }
            try {
                org.json.JSONObject info =
                        new org.json.JSONObject(body.toString());
                return new String[]{
                        info.optString("version", ""),
                        String.valueOf(info.optLong("pid", -1))};
            } catch (Exception e) {
                return null;
            }
        } catch (IOException e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * If something (a stale or nested server) still holds the API port,
     * wait for it to free up instead of crash-looping against EADDRINUSE.
     * Returns true when the port is free.
     */
    private boolean waitForPortFree() {
        for (int i = 0; i < 15 && running; i++) {
            java.net.Socket socket = null;
            try {
                socket = new java.net.Socket();
                socket.connect(new java.net.InetSocketAddress("127.0.0.1",
                        API_PORT), 2000);
                // Port busy: wait it out.
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            } catch (IOException e) {
                return true;
            } finally {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        return false;
    }

    private void runServerDirectLive(String binary) throws IOException, InterruptedException {
        String libDir = getApplicationInfo().nativeLibraryDir;
        ProcessBuilder builder = new ProcessBuilder(
                binary,
                "serve",
                "--service",
                "--port", String.valueOf(API_PORT));
        builder.directory(homeDir());
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        String ocShell = new File(files(), "bin/oc-shell").getAbsolutePath();
        String toolsLib = new File(files(), "usr/lib").getAbsolutePath();
        env.put("HOME", homeDir().getAbsolutePath());
        env.put("TMPDIR", new File(files(), "tmp").getAbsolutePath());
        env.put("LD_LIBRARY_PATH", toolsLib + ":" + libDir);
        env.put("PATH", new File(files(), "usr/bin").getAbsolutePath()
                + ":/system/bin:/system/xbin");
        env.put("SHELL", ocShell);
        env.put("BUN_PTY_LIB", toolsLib + "/librust_pty_arm64.so");
        env.put("TERMINFO", new File(files(), "usr/share/terminfo").getAbsolutePath());
        env.put("TERM", "xterm-256color");
        env.put("PREFIX", files().getAbsolutePath());
        env.put("LANG", "en_US.UTF-8");
        Log.i(TAG, "starting server direct (live): " + binary);
        serverProcess = builder.start();
        drainOutput(serverProcess.getInputStream());
        int code = serverProcess.waitFor();
        serverProcess = null;
        Log.i(TAG, "server exited: " + code);
    }

    private void runServerDirect() throws IOException, InterruptedException {
        String libDir = getApplicationInfo().nativeLibraryDir;
        String binary = libDir + "/libopencode.so";
        if (!new File(binary).isFile()) {
            Log.e(TAG, "server binary missing: " + binary);
            return;
        }
        ProcessBuilder builder = new ProcessBuilder(
                binary,
                "serve",
                "--service",
                "--port", String.valueOf(API_PORT));
        builder.directory(homeDir());
        builder.redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        String ocShell = new File(files(), "bin/oc-shell").getAbsolutePath();
        String toolsLib = new File(files(), "usr/lib").getAbsolutePath();
        env.put("HOME", homeDir().getAbsolutePath());
        env.put("TMPDIR", new File(files(), "tmp").getAbsolutePath());
        env.put("LD_LIBRARY_PATH", toolsLib + ":" + libDir);
        env.put("PATH", new File(files(), "usr/bin").getAbsolutePath()
                + ":/system/bin:/system/xbin");
        env.put("SHELL", ocShell);
        env.put("BUN_PTY_LIB", toolsLib + "/librust_pty_arm64.so");
        env.put("TERMINFO", new File(files(), "usr/share/terminfo").getAbsolutePath());
        env.put("TERM", "xterm-256color");
        env.put("PREFIX", files().getAbsolutePath());
        env.put("LANG", "en_US.UTF-8");
        Log.i(TAG, "starting server direct: " + binary);
        serverProcess = builder.start();
        drainOutput(serverProcess.getInputStream());
        int code = serverProcess.waitFor();
        serverProcess = null;
        Log.i(TAG, "server exited: " + code);
    }

    private void drainOutput(InputStream raw) {
        final BufferedReader reader =
                new BufferedReader(new InputStreamReader(raw));
        Thread drain = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.length() < 400) {
                            Log.i(TAG, "server: " + line);
                        }
                    }
                } catch (IOException ignored) {
                } finally {
                    try {
                        reader.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }, "oc-drain");
        drain.setDaemon(true);
        drain.start();
    }

    private void startForegroundNotification() {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.service_title),
                    NotificationManager.IMPORTANCE_MIN);
            manager.createNotificationChannel(channel);
        }
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= 26) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        Notification notification = builder
                .setContentTitle(getString(R.string.service_title))
                .setContentText(getString(R.string.service_text))
                .setSmallIcon(R.drawable.ic_launcher)
                .build();
        startForeground(NOTIFICATION_ID, notification);
    }
}
