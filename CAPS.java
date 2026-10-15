package finnLabs;

/*
 * finnLabs CAPS  -  Canvas And Presentation Scenes
 *
 * An OBS Studio-style screen + audio recorder written in Java.
 *
 *   UI      : Java Swing (part of the JDK, no libraries needed): dark theme, live preview,
 *             Scenes / Sources / Audio Mixer / Controls docks, status bar, Settings dialog.
 *   Engine  : FFmpeg, launched as a child process. It does the capture, audio mixing,
 *             hardware/software encoding and muxing - the same job it does inside OBS - so the
 *             Java side stays idle and recording stays cheap.
 *
 * What is real (not decoration):
 *   - Audio Mixer: live level meters (RMS + peak), fader and mute that also work WHILE recording.
 *   - Scenes: named capture setups (display / region / window), saved between runs.
 *   - Status bar: real CPU use (app + FFmpeg), fps, bitrate and dropped frames.
 *   - Preview: a scaled live view of the capture area (View > Preview to turn it off).
 *
 * Icons: PNG files are loaded from  GSource/icons/  (relative to the folder you start it from, or
 * to the folder CAPS.java sits in; override with -Dgsource.icons=DIR or $GSOURCE_ICONS).
 * Anything missing falls back to a built-in vector icon, so it always runs. Settings > Advanced
 * shows which folder was used. The application icon (32x32, drawn in code) can be replaced by
 * putting your own  caps.png  into that folder.
 *
 * Run (JDK 17+ with a desktop/headful runtime, and ffmpeg on PATH; no build step):
 *
 *     cd ~/Console            # the folder that contains GSource/
 *     java CAPS.java
 *
 * Or build a jar:
 *
 *     javac CAPS.java
 *     jar cfe CAPS.jar CAPS *.class
 *     java -jar CAPS.jar
 */

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CAPS {

    // ======================================================================
    // Platform & constants
    // ======================================================================

    static final String OS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    static final boolean WIN = OS.startsWith("windows");
    static final boolean MAC = OS.startsWith("mac");
    static final boolean LINUX = !WIN && !MAC;

    static final String APP_NAME = "finnLabs CAPS";
    static final String APP_TAGLINE = "Canvas And Presentation Scenes";
    static final String VAAPI_DEVICE = "/dev/dri/renderD128";

    static final Map<String, Integer> QUALITY = ordered(
            "Low (small files)", 30, "Medium", 26, "High", 22, "Very high", 18);
    static final Map<String, Integer> VT_MBPS = ordered(
            "Low (small files)", 5, "Medium", 9, "High", 15, "Very high", 25);
    static final Map<String, String> X264_PRESETS = ordered(
            "Lowest CPU (ultrafast)", "ultrafast", "Low CPU (superfast)", "superfast",
            "Balanced (veryfast)", "veryfast", "Smaller files (medium)", "medium");
    static final Map<String, Integer> RESOLUTIONS = ordered(
            "Native", 0, "1440p", 1440, "1080p", 1080, "720p", 720, "480p", 480);
    static final String[] FPS_CHOICES = {"15", "24", "30", "60"};
    static final Map<String, String> ENC_LABEL = ordered(
            "h264_nvenc", "NVIDIA NVENC (GPU)", "h264_amf", "AMD AMF (GPU)",
            "h264_qsv", "Intel Quick Sync (GPU)", "h264_vaapi", "VAAPI (GPU)",
            "h264_videotoolbox", "Apple VideoToolbox (GPU)", "libx264", "x264 (CPU)");

    static final Pattern LOOPBACK = Pattern.compile(
            "stereo mix|what u hear|wave out|loopback|virtual-audio|cable output|blackhole|soundflower|monitor of|\\.monitor",
            Pattern.CASE_INSENSITIVE);

    @SuppressWarnings("unchecked")
    static <V> Map<String, V> ordered(Object... kv) {
        Map<String, V> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (V) kv[i + 1]);
        return Collections.unmodifiableMap(m);
    }

    static List<String> gpuEncoderOrder() {
        if (WIN) return List.of("h264_nvenc", "h264_amf", "h264_qsv");
        if (MAC) return List.of("h264_videotoolbox");
        return List.of("h264_nvenc", "h264_vaapi", "h264_qsv");
    }

    // ======================================================================
    // Small utilities
    // ======================================================================

    record Result(int code, String out) {}

    /** Runs a short-lived command and returns its (stdout+stderr) output. */
    static Result run(List<String> cmd, int timeoutSec, Map<String, String> env) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            if (env != null) pb.environment().putAll(env);
            Process p = pb.start();
            p.getOutputStream().close();
            CompletableFuture<String> out = CompletableFuture.supplyAsync(() -> {
                try {
                    return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    return "";
                }
            });
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) p.destroyForcibly();
            String text = out.get(5, TimeUnit.SECONDS);
            return new Result(p.isAlive() ? -1 : p.exitValue(), text);
        } catch (Exception e) {
            return new Result(-1, String.valueOf(e.getMessage()));
        }
    }

    static String pretty(List<String> cmd) {
        StringBuilder sb = new StringBuilder();
        for (String a : cmd) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(a.matches("[\\w@%+=:,./-]+") ? a : "\"" + a.replace("\"", "\\\"") + "\"");
        }
        return sb.toString();
    }

    static String findExe(String name) {
        String exe = WIN ? name + ".exe" : name;
        List<Path> cands = new ArrayList<>();
        String path = System.getenv("PATH");
        if (path != null) {
            for (String d : path.split(File.pathSeparator)) {
                try {
                    if (!d.isBlank()) cands.add(Paths.get(d).resolve(exe));
                } catch (InvalidPathException ignored) { }
            }
        }
        if (MAC) {
            cands.add(Paths.get("/opt/homebrew/bin", exe));
            cands.add(Paths.get("/usr/local/bin", exe));
        }
        for (Path p : cands) if (Files.isRegularFile(p) && Files.isExecutable(p)) return p.toString();
        return null;
    }

    static String findFfmpeg(String custom) {
        String exe = WIN ? "ffmpeg.exe" : "ffmpeg";
        List<Path> cands = new ArrayList<>();
        try {
            if (notEmpty(custom)) cands.add(Paths.get(custom));
            String env = System.getenv("FFMPEG_BINARY");
            if (notEmpty(env)) cands.add(Paths.get(env));
            Path here = Paths.get("").toAbsolutePath();
            cands.add(here.resolve(exe));
            cands.add(here.resolve("ffmpeg").resolve("bin").resolve(exe));
        } catch (InvalidPathException ignored) { }
        for (Path p : cands) if (Files.isRegularFile(p) && Files.isExecutable(p)) return p.toString();
        return findExe("ffmpeg");
    }

    static boolean notEmpty(String s) { return s != null && !s.isBlank(); }

    static String fmtHms(double sec) {
        long s = (long) sec;
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    static String humanSize(long n) {
        double v = n;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int i = 0;
        while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
        return i == 0 ? String.format("%.0f %s", v, units[i]) : String.format("%.1f %s", v, units[i]);
    }

    static long fileSize(Path p) {
        try { return Files.size(p); } catch (IOException e) { return 0; }
    }

    static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    static String defaultVideosDir() {
        Path home = Paths.get(System.getProperty("user.home"));
        for (String n : new String[]{"Videos", "Movies"}) if (Files.isDirectory(home.resolve(n))) return home.resolve(n).toString();
        return home.toString();
    }

    // ======================================================================
    // Settings (persisted to ~/.finnlabs-caps.properties)
    // ======================================================================

    /** A named capture setup (OBS 'scene'): what to record. Audio/encoding settings are global. */
    static class Scene {
        String name, mode = "monitor", window = "";
        int monitor = 0;
        int[] region = null;
        boolean cursor = true;
        Scene(String name) { this.name = name; }
    }

    static class Config {
        String captureMode = "monitor";          // monitor | region | window
        int monitorIndex = 0;
        int[] region = null;                     // x, y, w, h in physical pixels
        String windowTitle = "";
        String captureMethod = "auto";           // auto (DXGI -> GDI) | gdi   (Windows only)
        int fps = 30;
        String resolution = "Native";
        String quality = "High";
        String encoder = "auto";
        String x264Preset = "Balanced (veryfast)";
        boolean cursor = true;
        String systemAudio = null;               // null = auto-pick, "" = none
        String micAudio = null;
        int systemGain = 100, micGain = 100;
        int audioOffsetMs = 0;                   // + delays audio, - plays it earlier
        boolean systemMuted = false, micMuted = false;
        String noiseReduction = "Maximum";       // Off / Light / Medium / Strong / Maximum - FFT noise reduction (ffmpeg afftdn)
        boolean previewOn = true, metersOn = true, metersWhileRecording = false;
        boolean previewWhileRecording = false;   // off by default: the live preview grabs its own screenshot,
                                                   // competing with FFmpeg's own capture for CPU and causing drops
        final List<Scene> scenes = new ArrayList<>();
        int activeScene = 0;
        boolean separateTracks = false;
        String outDir = defaultVideosDir();
        String container = "mkv";
        boolean minimizeOnStart = false;
        boolean showLog = false;
        String ffmpegPath = "";

        static final Path FILE = Paths.get(System.getProperty("user.home"), ".finnlabs-caps.properties");
        static final Path LEGACY = Paths.get(System.getProperty("user.home"), ".screen_recorder.properties");   // earlier name, read once

        static Config load() {
            Config c = new Config();
            Properties p = new Properties();
            try (Reader r = Files.newBufferedReader(Files.exists(FILE) ? FILE : LEGACY, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                return c;
            }
            c.captureMode = p.getProperty("captureMode", c.captureMode);
            c.monitorIndex = intProp(p, "monitorIndex", c.monitorIndex);
            c.region = parseRegion(p.getProperty("region"));
            c.windowTitle = p.getProperty("windowTitle", c.windowTitle);
            c.captureMethod = p.getProperty("captureMethod", c.captureMethod);
            c.fps = intProp(p, "fps", c.fps);
            c.resolution = p.getProperty("resolution", c.resolution);
            c.quality = p.getProperty("quality", c.quality);
            c.encoder = p.getProperty("encoder", c.encoder);
            c.x264Preset = p.getProperty("x264Preset", c.x264Preset);
            c.cursor = Boolean.parseBoolean(p.getProperty("cursor", String.valueOf(c.cursor)));
            c.systemAudio = p.getProperty("systemAudio");
            c.micAudio = p.getProperty("micAudio");
            c.systemGain = intProp(p, "systemGain", c.systemGain);
            c.micGain = intProp(p, "micGain", c.micGain);
            c.audioOffsetMs = intProp(p, "audioOffsetMs", c.audioOffsetMs);
            c.noiseReduction = p.getProperty("noiseReduction", "Maximum");
            // Older builds stored this as a plain true/false, and any other stray value would otherwise
            // silently disable denoising (falls through to "Off" in the switch) - normalize it instead.
            if (!List.of("Off", "Light", "Medium", "Strong", "Maximum").contains(c.noiseReduction)) {
                c.noiseReduction = "true".equalsIgnoreCase(c.noiseReduction) ? "Medium"
                        : "false".equalsIgnoreCase(c.noiseReduction) ? "Off" : "Maximum";
            }
            c.separateTracks = Boolean.parseBoolean(p.getProperty("separateTracks", "false"));
            c.outDir = p.getProperty("outDir", c.outDir);
            c.container = p.getProperty("container", c.container);
            c.minimizeOnStart = Boolean.parseBoolean(p.getProperty("minimizeOnStart", "false"));
            c.showLog = Boolean.parseBoolean(p.getProperty("showLog", "false"));
            c.ffmpegPath = p.getProperty("ffmpegPath", c.ffmpegPath);
            c.systemMuted = Boolean.parseBoolean(p.getProperty("systemMuted", "false"));
            c.micMuted = Boolean.parseBoolean(p.getProperty("micMuted", "false"));
            c.previewOn = Boolean.parseBoolean(p.getProperty("previewOn", "true"));
            c.metersOn = Boolean.parseBoolean(p.getProperty("metersOn", "true"));
            c.metersWhileRecording = Boolean.parseBoolean(p.getProperty("metersWhileRecording", "false"));
            c.previewWhileRecording = Boolean.parseBoolean(p.getProperty("previewWhileRecording", "false"));
            int n = intProp(p, "scene.count", 0);
            for (int i = 0; i < n; i++) {
                String k = "scene." + i + ".";
                Scene sc = new Scene(p.getProperty(k + "name", "Scene " + (i + 1)));
                sc.mode = p.getProperty(k + "mode", "monitor");
                sc.monitor = intProp(p, k + "monitor", 0);
                sc.region = parseRegion(p.getProperty(k + "region"));
                sc.window = p.getProperty(k + "window", "");
                sc.cursor = Boolean.parseBoolean(p.getProperty(k + "cursor", "true"));
                c.scenes.add(sc);
            }
            c.activeScene = intProp(p, "activeScene", 0);
            return c;
        }

        void save() {
            Properties p = new Properties();
            p.setProperty("captureMode", captureMode);
            p.setProperty("monitorIndex", String.valueOf(monitorIndex));
            if (region != null) p.setProperty("region", region[0] + "," + region[1] + "," + region[2] + "," + region[3]);
            p.setProperty("windowTitle", windowTitle);
            p.setProperty("captureMethod", captureMethod);
            p.setProperty("fps", String.valueOf(fps));
            p.setProperty("resolution", resolution);
            p.setProperty("quality", quality);
            p.setProperty("encoder", encoder);
            p.setProperty("x264Preset", x264Preset);
            p.setProperty("cursor", String.valueOf(cursor));
            if (systemAudio != null) p.setProperty("systemAudio", systemAudio);
            if (micAudio != null) p.setProperty("micAudio", micAudio);
            p.setProperty("systemGain", String.valueOf(systemGain));
            p.setProperty("micGain", String.valueOf(micGain));
            p.setProperty("audioOffsetMs", String.valueOf(audioOffsetMs));
            p.setProperty("noiseReduction", noiseReduction);
            p.setProperty("separateTracks", String.valueOf(separateTracks));
            p.setProperty("outDir", outDir);
            p.setProperty("container", container);
            p.setProperty("minimizeOnStart", String.valueOf(minimizeOnStart));
            p.setProperty("showLog", String.valueOf(showLog));
            p.setProperty("ffmpegPath", ffmpegPath);
            p.setProperty("systemMuted", String.valueOf(systemMuted));
            p.setProperty("micMuted", String.valueOf(micMuted));
            p.setProperty("previewOn", String.valueOf(previewOn));
            p.setProperty("metersOn", String.valueOf(metersOn));
            p.setProperty("metersWhileRecording", String.valueOf(metersWhileRecording));
            p.setProperty("previewWhileRecording", String.valueOf(previewWhileRecording));
            p.setProperty("scene.count", String.valueOf(scenes.size()));
            p.setProperty("activeScene", String.valueOf(activeScene));
            for (int i = 0; i < scenes.size(); i++) {
                Scene sc = scenes.get(i);
                String k = "scene." + i + ".";
                p.setProperty(k + "name", sc.name);
                p.setProperty(k + "mode", sc.mode);
                p.setProperty(k + "monitor", String.valueOf(sc.monitor));
                if (sc.region != null) p.setProperty(k + "region", sc.region[0] + "," + sc.region[1] + "," + sc.region[2] + "," + sc.region[3]);
                p.setProperty(k + "window", sc.window);
                p.setProperty(k + "cursor", String.valueOf(sc.cursor));
            }
            try (Writer w = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                p.store(w, APP_NAME);
            } catch (IOException ignored) { }
        }

        static int[] parseRegion(String s) {
            if (s == null) return null;
            try {
                String[] a = s.split(",");
                if (a.length == 4) return new int[]{Integer.parseInt(a[0].trim()), Integer.parseInt(a[1].trim()),
                        Integer.parseInt(a[2].trim()), Integer.parseInt(a[3].trim())};
            } catch (NumberFormatException ignored) { }
            return null;
        }

        static int intProp(Properties p, String k, int def) {
            try { return Integer.parseInt(p.getProperty(k, String.valueOf(def)).trim()); }
            catch (NumberFormatException e) { return def; }
        }
    }

    // ======================================================================
    // Device / capability discovery
    // ======================================================================

    record Device(String label, String value) {}

    /** phys = physical pixels (what FFmpeg captures); logical = Java user-space (what Swing uses). */
    record Monitor(String name, Rectangle phys, Rectangle logical, double scale,
                   boolean primary, boolean virtual, String avf) {}

    static class AVDevices {
        final List<Device> audio = new ArrayList<>();
        final List<Device> macScreens = new ArrayList<>();
        String defaultMic = "", defaultSystem = "";
    }

    static class Caps {
        String version = "?";
        boolean ddagrab;
        final List<String> encoders = new ArrayList<>();   // working encoders, best first, x264 last
    }

    static AVDevices discoverAv(String ff) {
        AVDevices a = new AVDevices();
        if (WIN) {
            Result r = run(List.of(ff, "-hide_banner", "-list_devices", "true", "-f", "dshow", "-i", "dummy"), 20, null);
            Pattern q = Pattern.compile("\"([^\"]+)\"\\s*(?:\\(([^)]*)\\))?");
            String mode = "";
            for (String line : r.out().split("\\R")) {
                if (line.contains("DirectShow audio devices")) { mode = "a"; continue; }
                if (line.contains("DirectShow video devices")) { mode = "v"; continue; }
                if (line.contains("Alternative name")) continue;
                Matcher m = q.matcher(line);
                if (!m.find()) continue;
                String name = m.group(1), kind = m.group(2) == null ? "" : m.group(2);
                if ((kind.contains("audio") || (kind.isEmpty() && mode.equals("a")))
                        && a.audio.stream().noneMatch(d -> d.value().equals(name))) {
                    a.audio.add(new Device(name, name));
                }
            }
        } else if (LINUX) {
            Map<String, String> c = Map.of("LC_ALL", "C");
            if (findExe("pactl") != null) {
                String name = null;
                for (String line : run(List.of("pactl", "list", "sources"), 10, c).out().split("\\R")) {
                    String s = line.trim();
                    if (s.startsWith("Name:")) name = s.substring(5).trim();
                    else if (s.startsWith("Description:") && name != null) {
                        a.audio.add(new Device(s.substring(12).trim(), name));
                        name = null;
                    }
                }
                String sink = "", source = "";
                for (String line : run(List.of("pactl", "info"), 10, c).out().split("\\R")) {
                    if (line.startsWith("Default Sink:")) sink = line.substring(13).trim();
                    if (line.startsWith("Default Source:")) source = line.substring(15).trim();
                }
                String sysWanted = sink + ".monitor", micWanted = source;
                for (Device d : a.audio) {
                    if (d.value().equals(sysWanted)) a.defaultSystem = d.value();
                    if (d.value().equals(micWanted) && !d.value().endsWith(".monitor")) a.defaultMic = d.value();
                }
            }
            if (a.audio.isEmpty()) a.audio.add(new Device("Default (PulseAudio)", "default"));
        } else {
            Result r = run(List.of(ff, "-hide_banner", "-f", "avfoundation", "-list_devices", "true", "-i", ""), 20, null);
            String section = "";
            Pattern q = Pattern.compile("\\[(\\d+)]\\s+(.+)$");
            for (String line : r.out().split("\\R")) {
                if (line.contains("AVFoundation video devices")) section = "v";
                else if (line.contains("AVFoundation audio devices")) section = "a";
                else {
                    Matcher m = q.matcher(line);
                    if (!m.find()) continue;
                    if (section.equals("a")) a.audio.add(new Device(m.group(2).trim(), m.group(1)));
                    else if (section.equals("v") && m.group(2).startsWith("Capture screen"))
                        a.macScreens.add(new Device(m.group(2).trim(), m.group(1)));
                }
            }
        }
        if (a.defaultMic.isEmpty()) a.defaultMic = pickDevice(a.audio, false);
        if (a.defaultSystem.isEmpty()) a.defaultSystem = pickDevice(a.audio, true);
        return a;
    }

    static String pickDevice(List<Device> devs, boolean loopback) {
        for (Device d : devs) if (LOOPBACK.matcher(d.label()).find() == loopback) return d.value();
        return "";
    }

    static Caps probeCaps(String ff) {
        Caps caps = new Caps();
        Matcher m = Pattern.compile("version\\s+(\\S+)").matcher(run(List.of(ff, "-version"), 10, null).out());
        if (m.find()) caps.version = m.group(1);
        caps.ddagrab = Pattern.compile("\\bddagrab\\b").matcher(run(List.of(ff, "-hide_banner", "-filters"), 15, null).out()).find();
        Set<String> listed = new HashSet<>();
        Matcher e = Pattern.compile("^\\s*V\\S{5}\\s+(\\S+)", Pattern.MULTILINE)
                .matcher(run(List.of(ff, "-hide_banner", "-encoders"), 15, null).out());
        while (e.find()) listed.add(e.group(1));
        for (String enc : gpuEncoderOrder()) if (listed.contains(enc) && probeEncoder(ff, enc)) caps.encoders.add(enc);
        if (listed.contains("libx264")) caps.encoders.add("libx264");
        return caps;
    }

    /** Test-encodes a few frames with exactly the options used for real recordings. */
    static boolean probeEncoder(String ff, String enc) {
        List<String> cmd = new ArrayList<>(List.of(ff, "-hide_banner", "-v", "error"));
        String vf = "format=nv12";
        if (enc.equals("h264_vaapi")) {
            cmd.addAll(List.of("-vaapi_device", VAAPI_DEVICE));
            vf = "format=nv12,hwupload";
        }
        cmd.addAll(List.of("-f", "lavfi", "-i", "color=c=black:s=640x360:r=30:d=0.3", "-vf", vf));
        cmd.addAll(encoderArgs(enc, "High", "Balanced (veryfast)", 30));
        cmd.addAll(List.of("-frames:v", "5", "-f", "null", "-"));
        return run(cmd, 20, null).code() == 0;
    }

    static List<Monitor> listMonitors(List<Device> macScreens) {
        List<Monitor> mons = new ArrayList<>();
        try {
            GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            GraphicsDevice def = ge.getDefaultScreenDevice();
            for (GraphicsDevice d : ge.getScreenDevices()) {
                GraphicsConfiguration gc = d.getDefaultConfiguration();
                Rectangle b = gc.getBounds();
                double s = MAC ? 1.0 : gc.getDefaultTransform().getScaleX();   // macOS: crop by fractions, stay in points
                Rectangle phys = new Rectangle((int) Math.round(b.x * s), (int) Math.round(b.y * s),
                        (int) Math.round(b.width * s), (int) Math.round(b.height * s));
                mons.add(new Monitor("", phys, new Rectangle(b), s, d.equals(def), false, ""));
            }
        } catch (HeadlessException ignored) { }
        mons.sort(Comparator.comparing(m -> !m.primary()));           // primary first (stable)
        for (int i = 0; i < mons.size(); i++) {
            Monitor m = mons.get(i);
            String avf = MAC ? (i < macScreens.size() ? macScreens.get(i).value() : "1") : "";
            mons.set(i, new Monitor("Display " + (i + 1) + (m.primary() ? " (primary)" : ""),
                    m.phys(), m.logical(), m.scale(), m.primary(), false, avf));
        }
        if (mons.size() > 1 && !MAC) {
            Rectangle u = new Rectangle(mons.get(0).phys()), ul = new Rectangle(mons.get(0).logical());
            for (Monitor m : mons) { u = u.union(m.phys()); ul = ul.union(m.logical()); }
            mons.add(new Monitor("All displays", u, ul, 1.0, false, true, ""));
        }
        return mons;
    }

    static List<Monitor> realMonitors(List<Monitor> mons) {
        return mons.stream().filter(m -> !m.virtual()).toList();
    }

    static int containingMonitor(Rectangle r, List<Monitor> real) {
        for (int i = 0; i < real.size(); i++) if (real.get(i).phys().contains(r)) return i;
        return -1;
    }

    /** Converts a rectangle in Swing user-space to physical pixels using the monitor under its corner. */
    static Rectangle toPhysical(Rectangle logical, List<Monitor> mons) {
        double s = 1.0;
        for (Monitor m : realMonitors(mons)) {
            if (m.logical().contains(logical.x, logical.y)) { s = m.scale(); break; }
        }
        return new Rectangle((int) Math.round(logical.x * s), (int) Math.round(logical.y * s),
                (int) Math.round(logical.width * s), (int) Math.round(logical.height * s));
    }

    // ======================================================================
    // FFmpeg command construction
    // ======================================================================

    static List<String> encoderArgs(String enc, String quality, String x264Preset, int fps) {
        String q = String.valueOf(QUALITY.getOrDefault(quality, 22));
        String gop = String.valueOf(Math.max(1, fps) * 2);
        return switch (enc) {
            case "h264_nvenc" -> List.of("-c:v", enc, "-preset", "p4", "-rc", "vbr", "-cq", q, "-b:v", "0", "-g", gop);
            case "h264_qsv" -> List.of("-c:v", enc, "-preset", "veryfast", "-global_quality", q, "-g", gop);
            case "h264_amf" -> List.of("-c:v", enc, "-quality", "balanced", "-rc", "cqp",
                    "-qp_i", q, "-qp_p", q, "-qp_b", q, "-g", gop);
            case "h264_vaapi" -> List.of("-c:v", enc, "-qp", q, "-g", gop);
            case "h264_videotoolbox" -> List.of("-c:v", enc, "-b:v",
                    Math.round(VT_MBPS.getOrDefault(quality, 15) * (double) fps / 30) + "M", "-g", gop);
            default -> List.of("-c:v", "libx264", "-preset", X264_PRESETS.getOrDefault(x264Preset, "veryfast"),
                    "-crf", q, "-g", gop, "-pix_fmt", "yuv420p");
        };
    }

    record VideoInput(List<String> args, List<String> pre, boolean dxgi) {}

    static VideoInput videoInput(Config c, List<Monitor> mons, boolean useDxgi) {
        String fps = String.valueOf(c.fps), cur = c.cursor ? "1" : "0";
        List<Monitor> real = realMonitors(mons);
        Monitor mon = mons.get(Math.max(0, Math.min(c.monitorIndex, mons.size() - 1)));
        Rectangle region = (c.captureMode.equals("region") && c.region != null)
                ? new Rectangle(c.region[0], c.region[1], c.region[2], c.region[3]) : null;

        if (WIN) {
            if (c.captureMode.equals("window") && notEmpty(c.windowTitle)) {
                return new VideoInput(List.of("-f", "gdigrab", "-framerate", fps, "-draw_mouse", cur,
                        "-i", "title=" + c.windowTitle), List.of(), false);
            }
            Rectangle rect = region != null ? region : mon.phys();
            if (useDxgi) {                                    // GPU Desktop Duplication: far cheaper than GDI
                int idx = containingMonitor(rect, real);
                if (idx >= 0) {
                    Rectangle mb = real.get(idx).phys();
                    String opts = "output_idx=" + idx + ":framerate=" + fps + ":draw_mouse=" + cur;
                    if (region != null) {
                        opts += ":offset_x=" + (rect.x - mb.x) + ":offset_y=" + (rect.y - mb.y)
                                + ":video_size=" + rect.width + "x" + rect.height;
                    }
                    return new VideoInput(List.of("-f", "lavfi", "-i", "ddagrab=" + opts + ",hwdownload,format=bgra"),
                            List.of(), true);
                }
            }
            return new VideoInput(List.of("-f", "gdigrab", "-framerate", fps, "-draw_mouse", cur,
                    "-offset_x", String.valueOf(rect.x), "-offset_y", String.valueOf(rect.y),
                    "-video_size", rect.width + "x" + rect.height, "-i", "desktop"), List.of(), false);
        }

        if (MAC) {
            Monitor m = mon;
            List<String> pre = new ArrayList<>();
            if (region != null) {
                m = real.get(0);
                for (Monitor x : real) if (x.logical().contains(region.x, region.y)) { m = x; break; }
                Rectangle lb = m.logical();
                pre.add(String.format("crop=w=trunc(iw*%d/%d/2)*2:h=trunc(ih*%d/%d/2)*2:x=trunc(iw*%d/%d):y=trunc(ih*%d/%d)",
                        region.width, lb.width, region.height, lb.height,
                        region.x - lb.x, lb.width, region.y - lb.y, lb.height));
            }
            return new VideoInput(List.of("-f", "avfoundation", "-framerate", fps, "-capture_cursor", cur,
                    "-i", notEmpty(m.avf()) ? m.avf() : "1"), pre, false);
        }

        String disp = Optional.ofNullable(System.getenv("DISPLAY")).orElse(":0");
        Rectangle r = region != null ? region : mon.phys();
        return new VideoInput(List.of("-f", "x11grab", "-framerate", fps, "-draw_mouse", cur,
                "-video_size", r.width + "x" + r.height, "-i", disp + "+" + r.x + "," + r.y), List.of(), false);
    }

    static String videoChain(Config c, String enc, List<String> pre) {
        List<String> ch = new ArrayList<>(pre);
        ch.add("fps=" + c.fps);                                                   // constant frame rate
        int h = RESOLUTIONS.getOrDefault(c.resolution, 0);
        if (h > 0) ch.add("scale=-2:'trunc(min(" + h + ",ih)/2)*2':flags=bilinear");   // never upscale
        else ch.add("crop=trunc(iw/2)*2:trunc(ih/2)*2");                          // encoders need even sizes
        switch (enc) {
            case "h264_vaapi" -> { ch.add("format=nv12"); ch.add("hwupload"); }
            case "libx264" -> ch.add("format=yuv420p");
            default -> ch.add("format=nv12");
        }
        return String.join(",", ch);
    }

    static String volumeArg(int gain, boolean muted) {
        return String.format(Locale.ROOT, "%.3f", muted ? 0.0 : gain / 100.0);
    }

    /** The volume filter is always present and named (volume@sys / volume@mic) so the mixer can change it live.
     *  When denoise != "Off", an FFT-based noise reducer (afftdn) runs first, before gain/format/offset, so it
     *  works on the original signal rather than on whatever the fader/offset already reshaped. A highpass first
     *  strips sub-100Hz rumble (hum, desk vibration) that would otherwise eat into the FFT denoiser's noise
     *  floor estimate; "Strong" chains two afftdn passes, which reduces noise much further than pushing a single
     *  pass's nr as high as it goes (a single very-high-nr pass starts eating the voice instead of the noise). */
    static String denoiseStage(String level) {
        return switch (level) {
            case "Light"   -> "highpass=f=80,afftdn=nr=20:nf=-40:tn=1,";
            case "Medium"  -> "highpass=f=100,afftdn=nr=35:nf=-32:tn=1,";
            case "Strong"  -> "highpass=f=120,afftdn=nr=45:nf=-28:tn=1,afftdn=nr=35:nf=-32:tn=1,";
            case "Maximum" -> "highpass=f=150,afftdn=nr=60:nf=-20:tn=1,afftdn=nr=50:nf=-25:tn=1,afftdn=nr=40:nf=-30:tn=1,";
            default        -> "";
        };
    }

    static String audioChain(String id, int gain, boolean muted, int offsetMs, String denoise) {
        StringBuilder sb = new StringBuilder("aresample=async=1000");             // keeps separate clocks in sync
        sb.append(',').append(denoiseStage(denoise));
        sb.append("volume@").append(id).append('=').append(volumeArg(gain, muted));
        sb.append(",aformat=sample_fmts=fltp:sample_rates=48000:channel_layouts=stereo");
        if (offsetMs > 0) {                                                        // delay audio (sample-accurate, amix-safe)
            sb.append(",adelay=").append(offsetMs).append('|').append(offsetMs);
        } else if (offsetMs < 0) {                                                 // play audio earlier: drop its first samples
            sb.append(",atrim=start=").append(String.format(Locale.ROOT, "%.3f", -offsetMs / 1000.0))
              .append(",asetpts=PTS-STARTPTS");
        }
        return sb.toString();
    }

    /** Bounded demuxer queue for raw video frames (a fixed 512 could eat gigabytes at 4K). */
    static int videoQueue(Config c, List<Monitor> mons) {
        Rectangle r;
        if (c.captureMode.equals("region") && c.region != null) r = new Rectangle(c.region[2], c.region[3]);
        else if (c.captureMode.equals("window")) r = new Rectangle(1920, 1080);
        else r = mons.get(Math.max(0, Math.min(c.monitorIndex, mons.size() - 1))).phys();
        double bytes = (double) r.width * r.height * 4 * (MAC ? 4 : 1);          // macOS captures at 2x
        return (int) Math.max(8, Math.min(64, 200e6 / bytes));
    }

    static List<String> audioInput(String dev) {
        if (WIN) return List.of("-thread_queue_size", "1024", "-rtbufsize", "256M", "-f", "dshow", "-i", "audio=" + dev);
        if (MAC) return List.of("-thread_queue_size", "1024", "-f", "avfoundation", "-i", ":" + dev);
        return List.of("-thread_queue_size", "1024", "-f", "pulse", "-i", dev);
    }

    record Built(List<String> cmd, boolean dxgi) {}

    static Built buildCommand(String ff, Config c, List<Monitor> mons, String enc, boolean useDxgi, Path out) {
        VideoInput vin = videoInput(c, mons, useDxgi);
        List<String> cmd = new ArrayList<>(List.of(ff, "-hide_banner", "-y", "-loglevel", "warning",
                "-nostats", "-progress", "pipe:1"));
        if (enc.equals("h264_vaapi")) cmd.addAll(List.of("-vaapi_device", VAAPI_DEVICE));

        record Src(String dev, int gain, boolean muted, String title, String id) {}
        List<Src> srcs = new ArrayList<>();
        if (notEmpty(c.systemAudio)) srcs.add(new Src(c.systemAudio, c.systemGain, c.systemMuted, "System audio", "sys"));
        if (notEmpty(c.micAudio)) srcs.add(new Src(c.micAudio, c.micGain, c.micMuted, "Microphone", "mic"));

        // Audio devices are opened FIRST and the screen grabber LAST. Grabbers are pull-based and stamp
        // frames when they are read; opened first, their frames go stale while slower audio devices
        // (DirectShow / PulseAudio) finish opening, which leaves a hole in the video.
        for (Src s : srcs) cmd.addAll(audioInput(s.dev()));
        cmd.addAll(List.of("-thread_queue_size", String.valueOf(videoQueue(c, mons))));
        cmd.addAll(vin.args());
        int v = srcs.size();                                     // index of the video input

        List<String> graph = new ArrayList<>();
        graph.add("[" + v + ":v]" + videoChain(c, enc, vin.pre()) + "[vout]");
        List<String> labels = new ArrayList<>();
        for (int i = 0; i < srcs.size(); i++) {
            Src sr = srcs.get(i);
            graph.add("[" + i + ":a]" + audioChain(sr.id(), sr.gain(), sr.muted(), c.audioOffsetMs,
                    c.noiseReduction) + "[a" + (i + 1) + "]");
            labels.add("[a" + (i + 1) + "]");
        }
        List<String> maps = new ArrayList<>(List.of("-map", "[vout]"));
        if (labels.size() == 2 && !c.separateTracks) {
            graph.add(labels.get(0) + labels.get(1)
                    + "amix=inputs=2:duration=longest:dropout_transition=0,volume=2,alimiter=limit=0.95[amix]");
            maps.addAll(List.of("-map", "[amix]"));
        } else {
            for (int i = 0; i < labels.size(); i++) {
                maps.addAll(List.of("-map", labels.get(i)));
                if (labels.size() == 2) {                       // MKV reads 'title', MP4 reads 'handler_name'
                    maps.addAll(List.of("-metadata:s:a:" + i, "title=" + srcs.get(i).title()));
                    maps.addAll(List.of("-metadata:s:a:" + i, "handler_name=" + srcs.get(i).title()));
                }
            }
        }
        cmd.addAll(List.of("-filter_complex", String.join(";", graph)));
        cmd.addAll(maps);
        cmd.addAll(encoderArgs(enc, c.quality, c.x264Preset, c.fps));
        if (!labels.isEmpty()) cmd.addAll(List.of("-c:a", "aac", "-b:a", "192k"));
        cmd.addAll(List.of("-max_muxing_queue_size", "4096", out.toString()));
        return new Built(cmd, vin.dxgi());
    }

    // ======================================================================
    // FFmpeg child process wrapper
    // ======================================================================

    static class FFProc {
        interface Listener {
            void log(String line);
            void stats(int tag, Map<String, String> stats);
            void exited(int tag, int rc, List<String> tail);
        }

        static final ScheduledExecutorService KILLER = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ff-killer");
            t.setDaemon(true);
            return t;
        });

        final Process p;
        final int tag;
        final Deque<String> tail = new ConcurrentLinkedDeque<>();

        FFProc(List<String> cmd, int tag, Listener l) throws IOException {
            this.tag = tag;
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (WIN) pb.environment().put("__COMPAT_LAYER", "HighDpiAware");   // gdigrab needs real pixels
            Process proc = pb.start();
            this.p = proc;

            Thread tOut = daemon("ff-progress", () -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    Map<String, String> s = new HashMap<>();
                    String line;
                    while ((line = r.readLine()) != null) {
                        int i = line.indexOf('=');
                        if (i < 0) continue;
                        s.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
                        if (line.startsWith("progress=")) l.stats(tag, new HashMap<>(s));
                    }
                } catch (IOException ignored) { }
            });
            Thread tErr = daemon("ff-log", () -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        if (line.isBlank() || line.startsWith("Enter command:") || line.startsWith("Command reply for stream")) continue;
                        tail.addLast(line);
                        while (tail.size() > 40) tail.pollFirst();
                        l.log(line);
                    }
                } catch (IOException ignored) { }
            });
            daemon("ff-wait", () -> {
                int rc = -1;
                try {
                    rc = proc.waitFor();
                    tOut.join(2000);
                    tErr.join(2000);
                } catch (InterruptedException ignored) { }
                l.exited(tag, rc, new ArrayList<>(tail));
            });
        }

        static Thread daemon(String name, Runnable r) {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            t.start();
            return t;
        }

        /** Changes a filter option of the running capture (e.g. volume@mic) through FFmpeg's stdin command channel. */
        synchronized void command(String target, String cmd, String arg) {
            try {
                OutputStream os = p.getOutputStream();
                os.write(("c" + target + " -1 " + cmd + " " + arg + "\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (IOException ignored) { }
        }

        /** Graceful stop: 'q' lets FFmpeg flush and finalize the file. Force-kill after 15 s. */
        void quit() {
            try {
                OutputStream os = p.getOutputStream();
                os.write("q\n".getBytes(StandardCharsets.UTF_8));
                os.flush();
            } catch (IOException ignored) { }
            KILLER.schedule(() -> { if (p.isAlive()) p.destroyForcibly(); }, 15, TimeUnit.SECONDS);
        }
    }

    // ======================================================================
    // Region selection overlay (dimmed screenshot + drag rectangle)
    // ======================================================================

    @SuppressWarnings("serial")
    static class RegionSelector extends JPanel {
        final JWindow win = new JWindow();
        final BufferedImage shot;
        final Rectangle vb;
        final List<Monitor> mons;
        final Consumer<Rectangle> done;
        Point p0;
        Rectangle sel;

        RegionSelector(Rectangle vb, List<Monitor> mons, Consumer<Rectangle> done) throws AWTException {
            this.vb = vb;
            this.mons = mons;
            this.done = done;
            this.shot = new Robot().createScreenCapture(vb);
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            setFocusable(true);

            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isRightMouseButton(e)) { finish(null); return; }
                    p0 = e.getPoint();
                    sel = null;
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (p0 == null) return;
                    sel = norm(p0, e.getPoint());
                    repaint();
                }
                @Override public void mouseReleased(MouseEvent e) {
                    if (p0 == null) return;
                    Rectangle r = norm(p0, e.getPoint());
                    p0 = null;
                    finish(r);
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "cancel");
            getActionMap().put("cancel", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { finish(null); }
            });

            win.setAlwaysOnTop(true);
            win.setBounds(vb);
            win.setContentPane(this);
            win.setVisible(true);
            win.toFront();
            requestFocusInWindow();
        }

        static Rectangle norm(Point a, Point b) {
            return new Rectangle(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.abs(a.x - b.x), Math.abs(a.y - b.y));
        }

        void finish(Rectangle r) {
            win.dispose();
            if (r == null) { done.accept(null); return; }
            Rectangle phys = toPhysical(new Rectangle(r.x + vb.x, r.y + vb.y, r.width, r.height), mons);
            phys.width -= phys.width % 2;                       // encoders need even dimensions
            phys.height -= phys.height % 2;
            done.accept(phys.width >= 32 && phys.height >= 32 ? phys : null);
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.drawImage(shot, 0, 0, getWidth(), getHeight(), null);
            g.setColor(new Color(0, 0, 0, 140));
            g.fillRect(0, 0, getWidth(), getHeight());
            if (sel != null && sel.width > 0 && sel.height > 0) {
                Shape old = g.getClip();
                g.setClip(sel);
                g.drawImage(shot, 0, 0, getWidth(), getHeight(), null);   // un-dimmed inside the selection
                g.setClip(old);
                g.setColor(new Color(0xFF3B30));
                g.setStroke(new BasicStroke(2f));
                g.drawRect(sel.x, sel.y, sel.width, sel.height);
                Rectangle ph = toPhysical(new Rectangle(sel.x + vb.x, sel.y + vb.y, sel.width, sel.height), mons);
                String t = ph.width + " x " + ph.height;
                g.setFont(g.getFont().deriveFont(Font.BOLD, 14f));
                int ty = sel.y > 24 ? sel.y - 8 : sel.y + sel.height + 18;
                g.setColor(Color.BLACK);
                g.drawString(t, sel.x + 1, ty + 1);
                g.setColor(Color.WHITE);
                g.drawString(t, sel.x, ty);
            }
            g.setFont(g.getFont().deriveFont(Font.BOLD, 18f));
            g.setColor(Color.WHITE);
            String hint = "Drag to choose the area to record   -   Esc or right-click to cancel";
            g.drawString(hint, Math.max(20, (getWidth() - g.getFontMetrics().stringWidth(hint)) / 2), 48);
            g.dispose();
        }
    }

    // ======================================================================
    // Layout helper
    // ======================================================================

    @SuppressWarnings("serial")
    static class Form extends JPanel {
        int row;

        Form(String title) {
            super(new GridBagLayout());
            if (title != null) setBorder(BorderFactory.createTitledBorder(title));
        }

        void row(String label, JComponent c) {
            GridBagConstraints g = new GridBagConstraints();
            g.gridy = row++;
            g.insets = new Insets(3, 6, 3, 6);
            g.anchor = GridBagConstraints.WEST;
            g.weightx = 1;
            g.fill = GridBagConstraints.HORIZONTAL;
            if (label != null) {
                g.gridx = 0;
                g.weightx = 0;
                add(new JLabel(label), g);
                g.gridx = 1;
                g.weightx = 1;
            } else {
                g.gridx = 0;
                g.gridwidth = 2;
            }
            add(c, g);
        }
    }

    // ======================================================================
    // OBS-style dark theme, drawn icons and custom components
    // ======================================================================

    static final Color C_BG = new Color(0x11151c);        // window
    static final Color C_PANEL = new Color(0x1b212b);     // dock bodies
    static final Color C_HEADER = new Color(0x232b38);    // dock title bars / status bar
    static final Color C_LINE = new Color(0x0a0d12);      // separators
    static final Color C_FIELD = new Color(0x151a22);     // lists, text fields
    static final Color C_TEXT = new Color(0xe9edf2);
    static final Color C_MUTED = new Color(0x8793a3);
    static final Color C_ACCENT = new Color(0x2bb789);
    static final Color C_REC = new Color(0xef5b5b);

    static void installTheme() {
        UIManager.put("control", C_PANEL);
        UIManager.put("info", C_HEADER);
        UIManager.put("nimbusBase", new Color(0x2c2f34));
        UIManager.put("nimbusAlertYellow", new Color(0xf8bb00));
        UIManager.put("nimbusDisabledText", new Color(0x7d8086));
        UIManager.put("nimbusFocus", C_ACCENT);
        UIManager.put("nimbusGreen", new Color(0x4caf50));
        UIManager.put("nimbusInfoBlue", C_ACCENT);
        UIManager.put("nimbusLightBackground", C_FIELD);
        UIManager.put("nimbusOrange", new Color(0xbf6204));
        UIManager.put("nimbusRed", C_REC);
        UIManager.put("nimbusSelectedText", Color.WHITE);
        UIManager.put("nimbusSelectionBackground", C_ACCENT);
        UIManager.put("text", C_TEXT);
        UIManager.put("MenuBar[Enabled].backgroundPainter",
                (javax.swing.Painter<JComponent>) (g, c, w, h) -> { g.setColor(C_HEADER); g.fillRect(0, 0, w, h); });
        UIManager.put("MenuBar:Menu[Selected].backgroundPainter",
                (javax.swing.Painter<JComponent>) (g, c, w, h) -> { g.setColor(C_ACCENT); g.fillRect(0, 0, w, h); });
        UIManager.put("MenuBar:Menu[Enabled].textForeground", C_TEXT);
        UIManager.put("MenuBar:Menu[Selected].textForeground", Color.WHITE);
        try {
            for (UIManager.LookAndFeelInfo i : UIManager.getInstalledLookAndFeels()) {
                if (i.getName().equals("Nimbus")) { UIManager.setLookAndFeel(i.getClassName()); return; }
            }
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) { }
    }

    interface Painter { void paint(Graphics2D g); }

    /** 16x16 vector icon drawn in the current text colour (greyed when the component is disabled). */
    static Icon mkIcon(Painter p) {
        return new Icon() {
            @Override public void paintIcon(Component c, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                g.translate(x, y);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(c != null && !c.isEnabled() ? new Color(0x666a70) : C_TEXT);
                p.paint(g);
                g.dispose();
            }
            @Override public int getIconWidth() { return 16; }
            @Override public int getIconHeight() { return 16; }
        };
    }

    static Icon plusIcon() { return mkIcon(g -> { g.drawLine(8, 3, 8, 13); g.drawLine(3, 8, 13, 8); }); }
    static Icon minusIcon() { return mkIcon(g -> g.drawLine(3, 8, 13, 8)); }
    static Icon gearIcon() {
        return mkIcon(g -> {
            g.drawOval(5, 5, 6, 6);
            for (int k = 0; k < 8; k++) {
                double a = Math.PI * k / 4;
                g.drawLine((int) Math.round(8 + 5 * Math.cos(a)), (int) Math.round(8 + 5 * Math.sin(a)),
                        (int) Math.round(8 + 7.2 * Math.cos(a)), (int) Math.round(8 + 7.2 * Math.sin(a)));
            }
        });
    }
    static Icon monitorIcon() {
        return mkIcon(g -> { g.drawRoundRect(1, 2, 13, 9, 2, 2); g.drawLine(8, 11, 8, 14); g.drawLine(5, 14, 11, 14); });
    }
    static Icon windowIcon() {
        return mkIcon(g -> { g.drawRoundRect(1, 2, 13, 11, 2, 2); g.drawLine(1, 5, 14, 5); });
    }
    static Icon regionIcon() {
        return mkIcon(g -> {
            g.drawLine(2, 5, 2, 2); g.drawLine(2, 2, 5, 2); g.drawLine(11, 2, 14, 2); g.drawLine(14, 2, 14, 5);
            g.drawLine(14, 11, 14, 14); g.drawLine(14, 14, 11, 14); g.drawLine(5, 14, 2, 14); g.drawLine(2, 14, 2, 11);
        });
    }
    static Icon micIcon() {
        return mkIcon(g -> { g.drawRoundRect(6, 1, 4, 8, 4, 4); g.drawArc(3, 4, 10, 9, 180, 180); g.drawLine(8, 13, 8, 15); g.drawLine(5, 15, 11, 15); });
    }
    static Icon speakerIcon(boolean muted) {
        return mkIcon(g -> {
            g.fillPolygon(new int[]{2, 5, 9, 9, 5, 2}, new int[]{6, 6, 3, 13, 10, 10}, 6);
            if (muted) { g.setColor(C_REC); g.drawLine(11, 6, 15, 10); g.drawLine(15, 6, 11, 10); }
            else { g.drawArc(8, 5, 5, 6, -60, 120); g.drawArc(8, 3, 8, 10, -60, 120); }
        });
    }
    static Icon dotIcon(Color c) {
        return new Icon() {
            @Override public void paintIcon(Component comp, Graphics g0, int x, int y) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(c);
                g.fillOval(x, y, 10, 10);
                g.dispose();
            }
            @Override public int getIconWidth() { return 10; }
            @Override public int getIconHeight() { return 10; }
        };
    }

    // ----------------------------------------------------------------------
    // Icon set: PNG files from  GSource/icons/  (relative path), with built-in fallbacks
    // ----------------------------------------------------------------------

    /** Extra built-in glyphs, used only when the matching PNG is missing (or for shapes the set does not have). */
    static Icon pauseIcon() { return mkIcon(g -> { g.fillRect(4, 3, 3, 10); g.fillRect(9, 3, 3, 10); }); }
    static Icon playIcon() { return mkIcon(g -> g.fillPolygon(new int[]{5, 5, 13}, new int[]{3, 13, 8}, 3)); }
    static Icon recordIcon() { return mkIcon(g -> { g.drawOval(2, 2, 12, 12); g.fillOval(5, 5, 6, 6); }); }
    static Icon stopIcon() { return mkIcon(g -> g.drawRoundRect(3, 3, 10, 10, 3, 3)); }
    static Icon folderIcon() {
        return mkIcon(g -> { g.drawLine(2, 4, 6, 4); g.drawLine(6, 4, 7, 6); g.drawLine(7, 6, 14, 6); g.drawLine(14, 6, 14, 13);
            g.drawLine(14, 13, 2, 13); g.drawLine(2, 13, 2, 4); });
    }
    static Icon exitIcon() {
        return mkIcon(g -> { g.drawLine(6, 2, 3, 2); g.drawLine(3, 2, 3, 14); g.drawLine(3, 14, 6, 14);
            g.drawLine(7, 8, 14, 8); g.drawLine(11, 5, 14, 8); g.drawLine(11, 11, 14, 8); });
    }
    static Icon refreshIcon() { return mkIcon(g -> { g.drawArc(3, 3, 10, 10, 40, 280); g.drawLine(13, 3, 13, 7); g.drawLine(13, 7, 9, 7); }); }
    static Icon editIcon() {
        return mkIcon(g -> { g.drawLine(3, 13, 4, 9); g.drawLine(4, 9, 11, 2); g.drawLine(11, 2, 14, 5); g.drawLine(14, 5, 7, 12); g.drawLine(7, 12, 3, 13); });
    }
    static Icon infoIcon() { return mkIcon(g -> { g.drawOval(2, 2, 12, 12); g.drawLine(8, 7, 8, 11); g.fillOval(7, 4, 2, 2); }); }
    static Icon terminalIcon() {
        return mkIcon(g -> { g.drawRoundRect(1, 2, 14, 12, 2, 2); g.drawLine(4, 6, 7, 8); g.drawLine(7, 8, 4, 10); g.drawLine(9, 10, 12, 10); });
    }
    static Icon saveIcon() { return mkIcon(g -> { g.drawRoundRect(2, 2, 12, 12, 2, 2); g.drawRect(5, 2, 6, 4); g.drawRect(5, 9, 6, 5); }); }
    static Icon videoIcon() {
        return mkIcon(g -> { g.drawRoundRect(1, 3, 10, 10, 2, 2); g.drawLine(11, 7, 15, 4); g.drawLine(15, 4, 15, 12); g.drawLine(15, 12, 11, 9); });
    }

    /** A PNG scaled into a square box; drawn dimmed when the component that shows it is disabled. */
    static class PngIcon implements Icon {
        final BufferedImage on, off;
        final int box;

        PngIcon(BufferedImage img, int box) {
            this.on = img;
            this.box = box;
            BufferedImage d = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = d.createGraphics();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
            g.drawImage(img, 0, 0, null);
            g.dispose();
            this.off = d;
        }

        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            g.drawImage(c != null && !c.isEnabled() ? off : on, x, y, null);
        }
        @Override public int getIconWidth() { return box; }
        @Override public int getIconHeight() { return box; }
    }

    static class Ic {
        static final String REL = "GSource/icons";
        static Path dir;
        static boolean resolved;
        static int found;
        static final Map<String, BufferedImage> RAW = new HashMap<>();      // name -> decoded image (null = not in the folder)
        static final Map<String, Icon> CACHE = new HashMap<>();
        static final Set<String> MISSING = new TreeSet<>();

        /** Icon folder: -Dgsource.icons / $GSOURCE_ICONS, else GSource/icons relative to the working directory,
         *  else relative to the folder the program was started from. */
        static synchronized Path dir() {
            if (resolved) return dir;
            resolved = true;
            List<Path> cands = new ArrayList<>();
            try {
                String o = System.getProperty("gsource.icons"), e = System.getenv("GSOURCE_ICONS");
                if (notEmpty(o)) cands.add(Paths.get(o));
                if (notEmpty(e)) cands.add(Paths.get(e));
                cands.add(Paths.get(REL).toAbsolutePath());                                        // relative to the working directory
                ProcessHandle.current().info().arguments().ifPresent(args -> {                     // java CAPS.java from elsewhere
                    for (String a : args) {
                        if (a.endsWith(".java") || a.endsWith(".jar")) {
                            Path parent = Paths.get(a).toAbsolutePath().getParent();
                            if (parent != null) cands.add(parent.resolve(REL));
                        }
                    }
                });
                java.security.CodeSource cs = CAPS.class.getProtectionDomain().getCodeSource();
                if (cs != null && cs.getLocation() != null) {
                    Path base = Paths.get(cs.getLocation().toURI());
                    if (Files.isRegularFile(base)) base = base.getParent();
                    if (base != null) cands.add(base.resolve(REL));
                }
            } catch (Exception ignored) { }
            for (Path p : cands) {
                if (Files.isDirectory(p)) { dir = p.normalize(); break; }
            }
            return dir;
        }

        static String summary() {
            Path d = dir();
            if (d == null) return "Icon folder " + REL + " not found (looked in the working directory and next to the program) - using built-in icons.";
            BufferedImage a = appIcon();
            String appLine = a == null ? "App icon: CAPS.png not found in that folder - using the built-in logo."
                    : "App icon: " + appFile + " (" + a.getWidth() + "x" + a.getHeight() + ")" + (a.getWidth() != 32 || a.getHeight() != 32 ? " - 32x32 recommended" : "") + ".";
            return "Icon folder: " + d + "  (" + found + " of " + RAW.size() + " icons in use loaded"
                    + (MISSING.isEmpty() ? "" : "; built-in fallback for " + String.join(", ", MISSING)) + ")\n" + appLine;
        }

        /** Light glyphs are used as they are. Dark glyphs would vanish on the dark UI, so they are recoloured light. */
        static BufferedImage prepare(BufferedImage src) {
            int w = src.getWidth(), h = src.getHeight();
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.drawImage(src, 0, 0, null);
            g.dispose();
            double sum = 0, wsum = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int p = out.getRGB(x, y), a = p >>> 24;
                    if (a == 0) continue;
                    sum += (0.299 * ((p >> 16) & 255) + 0.587 * ((p >> 8) & 255) + 0.114 * (p & 255)) * a;
                    wsum += a;
                }
            }
            if (wsum > 0 && sum / wsum < 110) {
                int rgb = C_TEXT.getRGB() & 0xFFFFFF;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) out.setRGB(x, y, (out.getRGB(x, y) & 0xFF000000) | rgb);
                }
            }
            return out;
        }

        static BufferedImage resample(BufferedImage src, int w, int h, Object hint) {
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, hint);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            return out;
        }

        /** Fit into a box x box canvas keeping the aspect ratio; halve step by step for a clean downscale. */
        static BufferedImage scale(BufferedImage src, int box) {
            double s = Math.min((double) box / src.getWidth(), (double) box / src.getHeight());
            int tw = Math.max(1, (int) Math.round(src.getWidth() * s)), th = Math.max(1, (int) Math.round(src.getHeight() * s));
            BufferedImage cur = src;
            while (cur.getWidth() / 2 >= tw && cur.getHeight() / 2 >= th) {
                cur = resample(cur, cur.getWidth() / 2, cur.getHeight() / 2, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            }
            cur = resample(cur, tw, th, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            BufferedImage canvas = new BufferedImage(box, box, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            g.drawImage(cur, (box - tw) / 2, (box - th) / 2, null);
            g.dispose();
            return canvas;
        }

        static BufferedImage app;
        static boolean appLoaded;
        static String appFile;                                    // which file name matched, for the Settings summary

        /** GSource/icons/CAPS.png, untouched (a logo must keep its own colours), or null. Filesystems on Linux/macOS
         *  are case-sensitive, so both the documented "CAPS.png" and a lowercase "caps.png" are accepted. */
        static synchronized BufferedImage appIcon() {
            if (appLoaded) return app;
            appLoaded = true;
            Path d = dir();
            if (d != null) {
                for (String name : new String[]{"CAPS.png", "caps.png"}) {
                    Path f = d.resolve(name);
                    if (!Files.isRegularFile(f)) continue;
                    try {
                        app = javax.imageio.ImageIO.read(f.toFile());
                        if (app != null) { appFile = name; break; }
                    } catch (IOException | RuntimeException ignored) { }
                }
            }
            return app;
        }

        static synchronized BufferedImage raw(String name) {
            if (RAW.containsKey(name)) return RAW.get(name);
            BufferedImage img = null;
            Path d = dir();
            if (d != null) {
                Path f = d.resolve(name + ".png");
                if (Files.isRegularFile(f)) {
                    try {
                        BufferedImage r = javax.imageio.ImageIO.read(f.toFile());
                        if (r != null) img = prepare(r);
                    } catch (IOException | RuntimeException ignored) { }
                }
            }
            if (img == null) MISSING.add(name + ".png"); else found++;
            RAW.put(name, img);
            return img;
        }

        /** name.png from the icon folder at box x box pixels, or the fallback when the file is not there. */
        static synchronized Icon png(String name, int box, Icon fallback) {
            String key = name + "@" + box;
            if (CACHE.containsKey(key)) return CACHE.get(key);
            BufferedImage r = raw(name);
            Icon ic = r == null ? fallback : new PngIcon(scale(r, box), box);
            CACHE.put(key, ic);
            return ic;
        }

        // ---- what the UI asks for (file name -> where it is used)
        static Icon display() { return png("computer", 16, monitorIcon()); }
        static Icon region() { return png("selectAll", 16, regionIcon()); }
        static Icon window() { return png("app", 16, windowIcon()); }
        static Icon desktopAudio() { return png("wave", 16, speakerIcon(false)); }
        static Icon mic() { return micIcon(); }                     // the set has no microphone glyph
        static Icon add() { return plusIcon(); }                    // ...and no plus glyph
        static Icon gear() { return png("settings", 16, gearIcon()); }
        static Icon trash() { return png("delete", 16, minusIcon()); }
        static Icon record() { return png("radioButtonChecked", 16, recordIcon()); }
        static Icon stop() { return png("checkBoxOutlineBlank", 16, stopIcon()); }
        static Icon pause() { return pauseIcon(); }                 // no pause / play glyph in the set either
        static Icon play() { return playIcon(); }
        static Icon folder() { return png("folderopen", 16, folderIcon()); }
        static Icon exit() { return png("logout", 16, exitIcon()); }
        static Icon refresh() { return png("refresh", 16, refreshIcon()); }
        static Icon edit() { return png("editdocument", 16, editIcon()); }
        static Icon info() { return png("info", 16, infoIcon()); }
        static Icon log() { return png("terminal", 16, terminalIcon()); }
        static Icon openFile() { return png("fileopen", 16, folderIcon()); }
        static Icon save() { return png("fileSave", 20, saveIcon()); }
        static Icon video() { return png("videoFile", 20, videoIcon()); }
        static Icon audio() { return png("wave", 20, speakerIcon(false)); }
        static Icon general() { return png("settings", 20, gearIcon()); }
        static Icon advanced() { return png("automation", 20, gearIcon()); }

        /** Big icon for message boxes, or null to keep the look-and-feel default. */
        static Icon dialog(int type) {
            String n = switch (type) {
                case JOptionPane.ERROR_MESSAGE -> "error";
                case JOptionPane.WARNING_MESSAGE -> "warn";
                case JOptionPane.QUESTION_MESSAGE -> "qusetion";      // sic: that is the file name in the set
                case JOptionPane.INFORMATION_MESSAGE -> "info";
                default -> null;
            };
            return n == null ? null : png(n, 32, null);
        }
    }

    static JButton iconButton(Icon icon, String tip) {
        JButton b = new JButton(icon);
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.setMargin(new Insets(2, 5, 2, 5));
        return b;
    }

    static void styleList(JList<?> l) {
        l.setBackground(C_FIELD);
        l.setForeground(C_TEXT);
        l.setFixedCellHeight(24);
    }

    /** An OBS-style dock: title bar, body, optional icon toolbar underneath. */
    static JPanel dock(String title, JComponent body, JComponent tools) {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createLineBorder(C_LINE));
        JLabel h = new JLabel(title);
        h.setOpaque(true);
        h.setBackground(C_HEADER);
        h.setForeground(C_TEXT);
        h.setBorder(new EmptyBorder(4, 8, 4, 8));
        p.add(h, BorderLayout.NORTH);
        p.add(body, BorderLayout.CENTER);
        if (tools != null) p.add(tools, BorderLayout.SOUTH);
        return p;
    }

    static JPanel toolbar(JComponent... items) {
        JPanel t = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 3));
        t.setBackground(C_PANEL);
        for (JComponent c : items) t.add(c);
        return t;
    }

    static String dbText(int gainPct) {
        return gainPct <= 0 ? "-inf dB" : String.format(Locale.ROOT, "%.1f dB", 20 * Math.log10(gainPct / 100.0));
    }

    /** Horizontal level meter with OBS colours: green up to -20 dB, yellow up to -9 dB, red above. */
    @SuppressWarnings("serial")
    static class LevelMeter extends JComponent {
        static final double MIN_DB = -60;
        double shownRms = -90, shownPeak = -90;
        long holdUntil;

        LevelMeter() {
            setPreferredSize(new Dimension(120, 28));
            setMinimumSize(new Dimension(60, 28));
        }

        void update(double rmsDb, double peakDb) {
            rmsDb = Math.max(-90, rmsDb);
            peakDb = Math.max(-90, peakDb);
            shownRms = rmsDb > shownRms ? rmsDb : Math.max(rmsDb, shownRms - 1.5);         // instant attack, ~35 dB/s release
            long now = System.nanoTime();
            if (peakDb >= shownPeak) { shownPeak = peakDb; holdUntil = now + 700_000_000L; }
            else if (now > holdUntil) shownPeak = Math.max(peakDb, shownPeak - 0.8);
            repaint();
        }

        static int xOf(double db, int w) {
            return (int) Math.round((Math.max(MIN_DB, Math.min(0, db)) - MIN_DB) / -MIN_DB * w);
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            int w = getWidth(), barH = 12;
            int[] xs = {0, xOf(-20, w), xOf(-9, w), w};
            Color[] dim = {new Color(0x1d3d1d), new Color(0x47431a), new Color(0x4a1b1b)};
            Color[] hot = {new Color(0x4cbb4c), new Color(0xe3d24a), new Color(0xe24a4a)};
            for (int i = 0; i < 3; i++) { g.setColor(dim[i]); g.fillRect(xs[i], 0, xs[i + 1] - xs[i], barH); }
            int level = xOf(shownRms, w);
            for (int i = 0; i < 3; i++) {
                int b = Math.min(xs[i + 1], level);
                if (b > xs[i]) { g.setColor(hot[i]); g.fillRect(xs[i], 0, b - xs[i], barH); }
            }
            if (shownPeak > MIN_DB) {
                int px = Math.max(0, Math.min(w - 2, xOf(shownPeak, w) - 1));
                g.setColor(shownPeak > -9 ? hot[2] : shownPeak > -20 ? hot[1] : hot[0]);
                g.fillRect(px, 0, 2, barH);
            }
            g.setFont(getFont().deriveFont(9f));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(C_MUTED);
            for (int db = -60; db <= 0; db += 10) {
                int tx = xOf(db, w);
                g.drawLine(Math.min(w - 1, tx), barH, Math.min(w - 1, tx), barH + 3);
                String s = String.valueOf(db);
                int sw = fm.stringWidth(s);
                g.drawString(s, Math.max(0, Math.min(w - sw, tx - sw / 2)), barH + 13);
            }
            g.dispose();
        }
    }

    /** One mixer strip: name + dB readout, level meter, fader and mute button. */
    @SuppressWarnings("serial")
    static class MixerRow extends JPanel {
        final JLabel dbLbl = new JLabel();
        final LevelMeter meter = new LevelMeter();
        final JSlider slider = new JSlider(0, 200, 100);
        final JToggleButton mute = new JToggleButton(speakerIcon(false));

        MixerRow(String name, int gain, boolean muted, Consumer<Integer> onGain, Consumer<Boolean> onMute) {
            super(new BorderLayout(0, 1));
            setOpaque(false);
            setBorder(new EmptyBorder(3, 8, 3, 8));
            JLabel nameLbl = new JLabel(name);
            nameLbl.setForeground(C_TEXT);
            dbLbl.setForeground(C_MUTED);
            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            top.add(nameLbl, BorderLayout.WEST);
            top.add(dbLbl, BorderLayout.EAST);
            slider.setOpaque(false);
            slider.setValue(gain);
            mute.setSelected(muted);
            mute.setIcon(speakerIcon(muted));
            mute.setFocusable(false);
            mute.setMargin(new Insets(1, 4, 1, 4));
            mute.setToolTipText("Mute / unmute (works while recording)");
            JPanel bottom = new JPanel(new BorderLayout(6, 0));
            bottom.setOpaque(false);
            bottom.add(slider, BorderLayout.CENTER);
            bottom.add(mute, BorderLayout.EAST);
            add(top, BorderLayout.NORTH);
            add(meter, BorderLayout.CENTER);
            add(bottom, BorderLayout.SOUTH);
            dbLbl.setText(dbText(gain));
            slider.addChangeListener(e -> { dbLbl.setText(dbText(slider.getValue())); onGain.accept(slider.getValue()); });
            mute.addActionListener(e -> { mute.setIcon(speakerIcon(mute.isSelected())); onMute.accept(mute.isSelected()); });
        }
    }

    /** The big black canvas in the middle: a scaled live view of what will be recorded. */
    @SuppressWarnings("serial")
    static class PreviewPanel extends JPanel {
        volatile BufferedImage img;
        String note = "";
        boolean enabled = true, recording;

        PreviewPanel() {
            setBackground(Color.BLACK);
            setPreferredSize(new Dimension(640, 300));
        }

        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            BufferedImage im = img;
            String msg = !enabled ? "Preview disabled" : im == null ? note : null;
            if (im != null && enabled) {
                double s = Math.min(getWidth() / (double) im.getWidth(), getHeight() / (double) im.getHeight());
                int w = (int) Math.round(im.getWidth() * s), h = (int) Math.round(im.getHeight() * s);
                int x = (getWidth() - w) / 2, y = (getHeight() - h) / 2;
                g.drawImage(im, x, y, w, h, null);
                g.setColor(new Color(0x3a3d42));
                g.drawRect(x, y, w - 1, h - 1);
            }
            if (msg != null && !msg.isEmpty()) {
                g.setColor(C_MUTED);
                g.setFont(getFont().deriveFont(Font.PLAIN, 15f));
                FontMetrics fm = g.getFontMetrics();
                g.drawString(msg, (getWidth() - fm.stringWidth(msg)) / 2, getHeight() / 2);
            }
            if (recording) {
                g.setColor(C_REC);
                g.fillOval(getWidth() - 62, 10, 10, 10);
                g.setFont(getFont().deriveFont(Font.BOLD, 12f));
                g.drawString("REC", getWidth() - 46, 20);
            }
            g.dispose();
        }
    }

    /**
     * Background audio level probe: a tiny FFmpeg process that reads one device and prints RMS/peak levels.
     * It always yields to the recording (the app stops meters before opening devices for a recording).
     */
    static class Meter {
        volatile double rms = -90, peak = -90;
        volatile String dev = "";
        private Process p;

        static List<String> input(String dev) {
            if (WIN) return List.of("-f", "dshow", "-i", "audio=" + dev);
            if (MAC) return List.of("-f", "avfoundation", "-i", ":" + dev);
            return List.of("-f", "pulse", "-i", dev);
        }

        synchronized void start(String ff, String device) {
            stop();
            dev = device;
            if (!notEmpty(device)) return;
            List<String> cmd = new ArrayList<>(List.of(ff, "-hide_banner", "-loglevel", "error", "-nostats", "-nostdin"));
            cmd.addAll(input(device));
            cmd.addAll(List.of("-vn", "-af",
                    "astats=metadata=1:reset=1:measure_perchannel=none:measure_overall=RMS_level+Peak_level,"
                            + "ametadata=mode=print:file=-:direct=1", "-f", "null", "-"));      // direct=1: no output buffering
            try {
                p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            } catch (IOException e) {
                p = null;
                return;
            }
            Process proc = p;
            FFProc.daemon("meter", () -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        int i = line.indexOf('=');
                        if (i < 0) continue;
                        double v;
                        try { v = line.contains("inf") ? -90 : Double.parseDouble(line.substring(i + 1).trim()); }
                        catch (NumberFormatException e) { continue; }
                        if (line.contains("RMS_level")) rms = v;
                        else if (line.contains("Peak_level")) peak = v;
                    }
                } catch (IOException ignored) { }
                if (p == proc) { rms = peak = -90; }
            });
        }

        synchronized void stop() {
            dev = "";
            rms = peak = -90;
            if (p != null) {
                p.destroy();
                try { p.waitFor(600, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
                if (p.isAlive()) p.destroyForcibly();
                p = null;
            }
        }

        synchronized ProcessHandle handle() { return p == null ? null : p.toHandle(); }
    }

    // ======================================================================
    // The application
    // ======================================================================

    static class App implements FFProc.Listener {
        enum State { IDLE, RECORDING, PAUSED, BUSY, FINALIZING }
        record SourceItem(String kind, String text) {}

        final Config cfg = Config.load();
        String ffmpeg;
        Caps caps = new Caps();
        AVDevices av = new AVDevices();
        List<Monitor> mons = new ArrayList<>();
        boolean ready, filling, quitRequested;

        // ---- session state (EDT only, except proc which the shutdown hook reads)
        State state = State.IDLE;
        volatile FFProc proc;
        String pending;                          // "pause" | "stop"
        final List<Path> segments = new ArrayList<>();
        int segIndex;
        Path tmpDir, finalPath;
        double elapsedDone;
        long segStartNs, sizeDone, curSize;
        String enc = "libx264", lastFps = "", lastSpeed = "";
        boolean useDxgi, dxgiActive, segLive;    // segLive: FFmpeg produced its first frame (the clock runs from here)
        long dropDone, dropCur, framesDone, framesCur, lastSizeSample, lastSizeNs, lastPushNs, lastCpuNs;
        double kbps, cpuPct = -1;
        boolean blink;
        final Map<Long, Long> cpuLast = new HashMap<>();
        final Meter sysMeter = new Meter(), micMeter = new Meter();

        // ---- main window
        final JFrame frame = new JFrame(APP_NAME);
        PreviewPanel preview;
        final DefaultListModel<String> sceneModel = new DefaultListModel<>();
        JList<String> sceneList;
        final DefaultListModel<SourceItem> sourceModel = new DefaultListModel<>();
        JList<SourceItem> sourceList;
        JButton sceneAddBtn, sceneRemoveBtn, srcAddBtn, srcPropsBtn;
        MixerRow sysRow, micRow;
        JButton recBtn, pauseBtn, settingsBtn, folderBtn, exitBtn;
        JLabel statusLbl, recLbl, cpuLbl, fpsLbl, kbpsLbl, dropLbl;
        JCheckBoxMenuItem previewItem, metersItem;
        JDialog settingsDlg, sourceDlg, logDlg;
        JTextArea logArea;
        JList<String> cats;
        JPanel cards;
        TrayIcon tray;
        MenuItem trayToggle, trayPause;
        Timer clockTick, meterTick, previewTick;
        long lastCpuSampleNs;

        // ---- settings / source widgets (they live inside the dialogs)
        final List<JComponent> lockable = new ArrayList<>();   // disabled while a recording is running
        JRadioButton rbMonitor, rbRegion, rbWindow;
        JComboBox<String> monitorCb, windowCb, sysCb, micCb, encCb, qualityCb, fpsCb, resCb, presetCb, methodCb, formatCb;
        JButton regionBtn, refreshWinBtn, refreshDevBtn, browseBtn, ffBtn;
        JLabel regionLbl, ffLbl, baseResLbl;
        JTextArea iconLbl;
        JSpinner offsetSp;
        JComboBox<String> noiseReductionCb;
        JCheckBox separateCb, cursorCb, minimizeCb, previewCb, metersCb, metersRecCb, previewRecCb;
        JComboBox<String> quickQualityCb, quickResCb, quickFpsCb;
        JTextField dirField;

        // ---- preview worker
        final ExecutorService previewExec = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "preview");
            t.setDaemon(true);
            return t;
        });
        final java.util.concurrent.atomic.AtomicBoolean previewBusy = new java.util.concurrent.atomic.AtomicBoolean();
        Robot robot;

        App() {
            ffmpeg = findFfmpeg(cfg.ffmpegPath);
            ensureScenes();
            buildWidgets();
            buildSettingsDialog();
            buildSourceDialog();
            buildLogDialog();
            buildMainWindow();
            setupTray();
            clockTick = new Timer(250, e -> updateClock());
            meterTick = new Timer(40, e -> updateMeters());
            previewTick = new Timer(250, e -> previewStep());
            clockTick.start();
            meterTick.start();
            previewTick.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                FFProc p = proc;
                if (p != null && p.p.isAlive()) p.p.destroy();
                sysMeter.stop();
                micMeter.stop();
            }));
            syncWidgets();
            refreshScenes();
            refreshSources();
            applyStates();
            log(Ic.summary());
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            if (LINUX && "wayland".equalsIgnoreCase(System.getenv("XDG_SESSION_TYPE"))) {
                log("WARNING: Wayland session detected. x11grab only sees XWayland windows - log in with an X11/Xorg session for full-screen capture.");
            }
            discover(true);
        }

        // ================================================================ widget creation

        JComboBox<String> combo(String... items) {
            JComboBox<String> c = new JComboBox<>(items);
            c.setPrototypeDisplayValue("xxxxxxxxxxxxxxxxxxxxxxxxxxxxxx");
            return c;
        }

        <T extends JComponent> T lock(T c) { lockable.add(c); return c; }

        static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
        static String nz(String s) { return s == null ? "" : s; }

        void buildWidgets() {
            rbMonitor = lock(new JRadioButton("Display Capture"));
            rbRegion = lock(new JRadioButton("Region Capture"));
            rbWindow = lock(new JRadioButton("Window Capture"));
            ButtonGroup bg = new ButtonGroup();
            for (JRadioButton rb : new JRadioButton[]{rbMonitor, rbRegion, rbWindow}) {
                if (rb == rbWindow && !WIN) continue;
                bg.add(rb);
                rb.addActionListener(e -> applyStates());
            }
            monitorCb = lock(combo());
            regionBtn = lock(new JButton("Select Region...", Ic.region()));
            regionBtn.addActionListener(e -> selectRegion());
            regionLbl = new JLabel();
            if (WIN) {
                windowCb = lock(combo());
                refreshWinBtn = lock(new JButton("Refresh", Ic.refresh()));
                refreshWinBtn.addActionListener(e -> refreshWindows());
            }
            cursorCb = lock(new JCheckBox("Capture Cursor"));

            sysCb = lock(combo());
            micCb = lock(combo());
            offsetSp = lock(new JSpinner(new SpinnerNumberModel(0, -1000, 2000, 10)));
            offsetSp.setToolTipText("Fixes lip-sync. If the sound lags behind the picture use a negative value (e.g. -150); "
                    + "if it arrives too early use a positive one.");
            noiseReductionCb = lock(combo("Off", "Light", "Medium", "Strong", "Maximum"));
            noiseReductionCb.setToolTipText("Runs the audio through an FFT-based noise reducer (ffmpeg's afftdn) "
                    + "that estimates the steady background noise floor and subtracts it, plus a high-pass filter "
                    + "to strip low rumble/hum first. Higher settings chain more denoise passes and remove more "
                    + "noise, but can start to thin out speech too. Helps with hiss, fan or AC hum; won't fix "
                    + "loud, non-steady noise. Applies to both the microphone and system-audio tracks.");
            separateCb = lock(new JCheckBox("Record system audio and microphone as separate tracks"));
            refreshDevBtn = lock(new JButton("Refresh Devices", Ic.refresh()));
            refreshDevBtn.addActionListener(e -> discover(false));

            encCb = lock(combo("Auto (best available)"));
            encCb.addActionListener(e -> { if (!filling) applyStates(); });
            qualityCb = lock(combo(QUALITY.keySet().toArray(new String[0])));
            fpsCb = lock(combo(FPS_CHOICES));
            resCb = lock(combo(RESOLUTIONS.keySet().toArray(new String[0])));
            presetCb = lock(combo(X264_PRESETS.keySet().toArray(new String[0])));
            if (WIN) methodCb = lock(combo("Auto (DXGI, falls back to GDI)", "GDI only"));

            dirField = lock(new JTextField(26));
            browseBtn = lock(new JButton("Browse...", Ic.folder()));
            browseBtn.addActionListener(e -> browseDir());
            formatCb = lock(combo("MKV (crash-safe)", "MP4 (remuxed on stop)"));

            minimizeCb = lock(new JCheckBox("Hide the window while recording (use the tray icon, F9 or F10)"));
            previewCb = new JCheckBox("Show live preview");
            metersCb = new JCheckBox("Show audio level meters");
            metersRecCb = new JCheckBox("Keep meters running while recording");
            previewRecCb = new JCheckBox("Keep live preview running while recording (uses more CPU, can cause dropped frames)");
            ffLbl = new JLabel();
            ffBtn = new JButton("Locate FFmpeg...", Ic.openFile());
            ffBtn.addActionListener(e -> locateFfmpeg());
            baseResLbl = new JLabel();
        }

        // ================================================================ dialogs

        static JPanel top(JComponent c) {
            JPanel p = new JPanel(new BorderLayout());
            p.setBorder(new EmptyBorder(10, 12, 10, 12));
            p.add(c, BorderLayout.NORTH);
            return p;
        }

        static JPanel flow(JComponent... cs) {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            p.setOpaque(false);
            for (JComponent c : cs) p.add(c);
            return p;
        }

        static JPanel fill(JComponent center, JComponent east) {
            JPanel p = new JPanel(new BorderLayout(6, 0));
            p.setOpaque(false);
            p.add(center, BorderLayout.CENTER);
            p.add(east, BorderLayout.EAST);
            return p;
        }

        void buildSettingsDialog() {
            settingsDlg = new JDialog(frame, "Settings", true);
            settingsDlg.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            settingsDlg.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) { cancelSettings(); }
            });
            cards = new JPanel(new CardLayout());

            Form general = new Form(null);
            general.row(null, minimizeCb);
            general.row(null, previewCb);
            general.row(null, metersCb);
            general.row(null, metersRecCb);
            general.row(null, previewRecCb);
            cards.add(top(general), "General");

            Form output = new Form(null);
            output.row("Recording Path", fill(dirField, browseBtn));
            output.row("Recording Format", formatCb);
            output.row("Encoder", encCb);
            output.row("Quality", qualityCb);
            output.row("CPU Usage Preset (x264)", presetCb);
            output.row(null, separateCb);
            cards.add(top(output), "Output");

            Form audio = new Form(null);
            audio.row("Desktop Audio", sysCb);
            audio.row("Mic/Aux Audio", micCb);
            audio.row("Audio Sync Offset (ms)", offsetSp);
            audio.row("Noise Reduction (FFT)", noiseReductionCb);
            audio.row(null, flow(refreshDevBtn));
            String hint = WIN ? "Desktop audio needs a loopback device: enable 'Stereo Mix' (Sound settings > Recording) or install VB-Cable / virtual-audio-capturer."
                    : MAC ? "Desktop audio needs a virtual device such as BlackHole - pick it as 'Desktop Audio'."
                    : "Desktop audio = the 'Monitor of ...' source of your output device.";
            JLabel hl = new JLabel("<html><body style='width:420px'><small>" + hint + "</small></body></html>");
            hl.setForeground(C_MUTED);
            audio.row(null, hl);
            cards.add(top(audio), "Audio");

            Form video = new Form(null);
            video.row("Base (Canvas) Resolution", baseResLbl);
            video.row("Output (Scaled) Resolution", resCb);
            video.row("Common FPS Values", fpsCb);
            if (WIN) video.row("Capture Method", methodCb);
            cards.add(top(video), "Video");

            Form adv = new Form(null);
            adv.row("FFmpeg", fill(ffLbl, ffBtn));
            iconLbl = new JTextArea(3, 30);                     // wraps long paths instead of clipping them
            iconLbl.setEditable(false);
            iconLbl.setLineWrap(true);
            iconLbl.setWrapStyleWord(true);
            iconLbl.setOpaque(false);
            iconLbl.setFont(new JLabel().getFont());
            iconLbl.setForeground(C_MUTED);
            adv.row("Icon Set", iconLbl);
            JButton logBtn = new JButton("Show Log...", Ic.log());
            logBtn.addActionListener(e -> showLog());
            adv.row(null, flow(logBtn));
            cards.add(top(adv), "Advanced");

            cats = new JList<>(new String[]{"General", "Output", "Audio", "Video", "Advanced"});
            styleList(cats);
            cats.setFixedCellHeight(32);
            cats.setCellRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                    JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, sel, foc);
                    c.setIcon(switch (String.valueOf(v)) {
                        case "General" -> Ic.general();
                        case "Output" -> Ic.save();
                        case "Audio" -> Ic.audio();
                        case "Video" -> Ic.video();
                        default -> Ic.advanced();
                    });
                    c.setIconTextGap(10);
                    return c;
                }
            });
            cats.setSelectedIndex(0);
            cats.setBorder(new EmptyBorder(4, 4, 4, 4));
            cats.addListSelectionListener(e -> {
                if (cats.getSelectedValue() != null) ((CardLayout) cards.getLayout()).show(cards, cats.getSelectedValue());
            });
            JScrollPane catScroll = new JScrollPane(cats);
            catScroll.setPreferredSize(new Dimension(150, 300));
            catScroll.setBorder(BorderFactory.createLineBorder(C_LINE));

            JButton ok = new JButton("OK"), cancel = new JButton("Cancel"), apply = new JButton("Apply");
            ok.addActionListener(e -> { applySettings(); settingsDlg.setVisible(false); });
            cancel.addActionListener(e -> cancelSettings());
            apply.addActionListener(e -> applySettings());
            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
            btns.add(ok);
            btns.add(cancel);
            btns.add(apply);

            JPanel content = new JPanel(new BorderLayout(8, 0));
            content.setBorder(new EmptyBorder(10, 10, 0, 10));
            content.add(catScroll, BorderLayout.WEST);
            content.add(cards, BorderLayout.CENTER);
            settingsDlg.setLayout(new BorderLayout());
            settingsDlg.add(content, BorderLayout.CENTER);
            settingsDlg.add(btns, BorderLayout.SOUTH);
            settingsDlg.setSize(760, 420);
            settingsDlg.setMinimumSize(new Dimension(640, 380));
        }

        void openSettings(String page) {
            iconLbl.setText(Ic.summary());
            cats.setSelectedValue(page, true);
            applyStates();
            settingsDlg.setLocationRelativeTo(frame);
            settingsDlg.setVisible(true);
        }

        void applySettings() {
            collectSettings();
            cfg.save();
            refreshSources();
            updateBaseRes();
            preview.enabled = cfg.previewOn;
            previewItem.setSelected(cfg.previewOn);
            metersItem.setSelected(cfg.metersOn);
            applyStates();
        }

        void cancelSettings() {
            syncWidgets();
            settingsDlg.setVisible(false);
        }

        void buildSourceDialog() {
            sourceDlg = new JDialog(frame, "Properties", true);
            sourceDlg.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            sourceDlg.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) { cancelSource(); }
            });
            Form f = new Form(null);
            JPanel modes = flow(rbMonitor, rbRegion);
            if (WIN) modes.add(rbWindow);
            f.row("Type", modes);
            f.row("Display", monitorCb);
            f.row("Region", flow(regionBtn, regionLbl));
            if (WIN) f.row("Window", fill(windowCb, refreshWinBtn));
            f.row(null, cursorCb);
            JButton ok = new JButton("OK"), cancel = new JButton("Cancel");
            ok.addActionListener(e -> {
                collectSource();
                cfg.save();
                refreshSources();
                updateBaseRes();
                sourceDlg.setVisible(false);
            });
            cancel.addActionListener(e -> cancelSource());
            JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
            btns.add(ok);
            btns.add(cancel);
            sourceDlg.setLayout(new BorderLayout());
            sourceDlg.add(top(f), BorderLayout.CENTER);
            sourceDlg.add(btns, BorderLayout.SOUTH);
            sourceDlg.setSize(560, 290);
            sourceDlg.setResizable(false);
        }

        void cancelSource() {
            syncWidgets();
            sourceDlg.setVisible(false);
        }

        void openSourceDialog(String mode) {
            if (mode != null) {
                switch (mode) {
                    case "region" -> rbRegion.setSelected(true);
                    case "window" -> { if (WIN) rbWindow.setSelected(true); }
                    default -> rbMonitor.setSelected(true);
                }
            }
            String label = switch (currentMode()) {
                case "region" -> "Region Capture";
                case "window" -> "Window Capture";
                default -> "Display Capture";
            };
            sourceDlg.setTitle("Properties for '" + label + "'");
            applyStates();
            sourceDlg.setLocationRelativeTo(frame);
            sourceDlg.setVisible(true);
        }

        void buildLogDialog() {
            logDlg = new JDialog(frame, "Log", false);
            logArea = new JTextArea(16, 90);
            logArea.setEditable(false);
            logArea.setBackground(C_FIELD);
            logArea.setForeground(C_TEXT);
            logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            logDlg.add(new JScrollPane(logArea));
            logDlg.pack();
        }

        void showLog() {
            logDlg.setLocationRelativeTo(frame);
            logDlg.setVisible(true);
        }

        // ================================================================ main window

        void buildMainWindow() {
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent e) { onClose(); }
            });
            applyAppIcon(false);

            // ---- menu bar
            JMenuBar mb = new JMenuBar() {                       // Nimbus paints the bar itself; take over for a dark bar
                @Override protected void paintComponent(Graphics g) {
                    g.setColor(C_HEADER);
                    g.fillRect(0, 0, getWidth(), getHeight());
                }
            };
            mb.setOpaque(false);
            mb.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, C_LINE));
            int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

            JMenu file = new JMenu("File");
            JMenuItem miFolder = new JMenuItem("Show Recordings");
            miFolder.addActionListener(e -> openFolder());
            JMenuItem miSettings = new JMenuItem("Settings");
            miSettings.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_COMMA, mask));
            miSettings.addActionListener(e -> openSettings("General"));
            JMenuItem miExit = new JMenuItem("Exit");
            miExit.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, mask));
            miExit.addActionListener(e -> onClose());
            miFolder.setIcon(Ic.folder());
            miSettings.setIcon(Ic.gear());
            miExit.setIcon(Ic.exit());
            file.add(miFolder);
            file.add(miSettings);
            file.addSeparator();
            file.add(miExit);

            JMenu edit = new JMenu("Edit");
            JMenuItem miAdd = new JMenuItem("Add Scene...");
            miAdd.addActionListener(e -> addScene());
            JMenuItem miRename = new JMenuItem("Rename Scene...");
            miRename.addActionListener(e -> renameScene());
            JMenuItem miRemove = new JMenuItem("Remove Scene");
            miRemove.addActionListener(e -> removeScene());
            miAdd.setIcon(Ic.add());
            miRename.setIcon(Ic.edit());
            miRemove.setIcon(Ic.trash());
            edit.add(miAdd);
            edit.add(miRename);
            edit.add(miRemove);

            JMenu view = new JMenu("View");
            previewItem = new JCheckBoxMenuItem("Preview", cfg.previewOn);
            previewItem.addActionListener(e -> { cfg.previewOn = previewItem.isSelected(); previewCb.setSelected(cfg.previewOn); preview.enabled = cfg.previewOn; preview.repaint(); cfg.save(); });
            metersItem = new JCheckBoxMenuItem("Audio Meters", cfg.metersOn);
            metersItem.addActionListener(e -> { cfg.metersOn = metersItem.isSelected(); metersCb.setSelected(cfg.metersOn); syncMeters(); cfg.save(); });
            JMenuItem miLog = new JMenuItem("Log");
            miLog.addActionListener(e -> showLog());
            miLog.setIcon(Ic.log());
            view.add(previewItem);
            view.add(metersItem);
            view.addSeparator();
            view.add(miLog);

            JMenu tools = new JMenu("Tools");
            JMenuItem miRefresh = new JMenuItem("Refresh Devices");
            miRefresh.addActionListener(e -> discover(false));
            JMenuItem miLocate = new JMenuItem("Locate FFmpeg...");
            miLocate.addActionListener(e -> locateFfmpeg());
            miRefresh.setIcon(Ic.refresh());
            miLocate.setIcon(Ic.openFile());
            tools.add(miRefresh);
            tools.add(miLocate);

            JMenu help = new JMenu("Help");
            JMenuItem miKeys = new JMenuItem("Keyboard Shortcuts");
            miKeys.addActionListener(e -> JOptionPane.showMessageDialog(frame,
                    "F9        Start / stop recording\nF10      Pause / resume\nCtrl+,    Settings\nCtrl+Q   Exit\n\n"
                            + "With the window hidden, use the tray icon menu.", "Keyboard Shortcuts", JOptionPane.PLAIN_MESSAGE));
            JMenuItem miAbout = new JMenuItem("About");
            miAbout.addActionListener(e -> JOptionPane.showMessageDialog(frame,
                    APP_NAME + "\n" + APP_TAGLINE + "\n\nA Java front end for FFmpeg\nFFmpeg: " + (ffmpeg == null ? "not found" : caps.version),
                    "About " + APP_NAME, JOptionPane.INFORMATION_MESSAGE, new ImageIcon(appImage(32, false))));
            miAbout.setIcon(Ic.info());
            help.add(miKeys);
            help.add(miAbout);
            for (JMenu m : new JMenu[]{file, edit, view, tools, help}) { m.setForeground(C_TEXT); mb.add(m); }
            frame.setJMenuBar(mb);

            // ---- quick settings strip: the handful of settings people change most, editable right here
            // with no dialog to open and no OK/Apply/Cancel - changes save and take effect immediately.
            quickQualityCb = lock(combo(QUALITY.keySet().toArray(new String[0])));
            quickResCb = lock(combo(RESOLUTIONS.keySet().toArray(new String[0])));
            quickFpsCb = lock(combo(FPS_CHOICES));
            quickQualityCb.addActionListener(e -> {
                if (filling) return;
                cfg.quality = (String) quickQualityCb.getSelectedItem();
                cfg.save();
                if (qualityCb != null) { filling = true; qualityCb.setSelectedItem(cfg.quality); filling = false; }
            });
            quickResCb.addActionListener(e -> {
                if (filling) return;
                cfg.resolution = (String) quickResCb.getSelectedItem();
                cfg.save();
                if (resCb != null) { filling = true; resCb.setSelectedItem(cfg.resolution); filling = false; }
                updateBaseRes();
            });
            quickFpsCb.addActionListener(e -> {
                if (filling) return;
                cfg.fps = Integer.parseInt((String) quickFpsCb.getSelectedItem());
                cfg.save();
                if (fpsCb != null) { filling = true; fpsCb.setSelectedItem(String.valueOf(cfg.fps)); filling = false; }
            });
            JPanel quickBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
            quickBar.setBackground(C_HEADER);
            quickBar.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, C_LINE), new EmptyBorder(3, 8, 3, 8)));
            for (Component c : new Component[]{new JLabel("Quality"), quickQualityCb, new JLabel("Resolution"), quickResCb, new JLabel("FPS"), quickFpsCb}) {
                if (c instanceof JLabel jl) jl.setForeground(C_MUTED);
                quickBar.add(c);
            }
            JButton moreSettingsBtn = new JButton("More settings...");
            moreSettingsBtn.setFocusable(false);
            moreSettingsBtn.addActionListener(e -> openSettings("Output"));
            quickBar.add(moreSettingsBtn);

            // ---- preview
            preview = new PreviewPanel();
            preview.enabled = cfg.previewOn;
            JPanel previewWrap = new JPanel(new BorderLayout());
            previewWrap.setBackground(C_BG);
            previewWrap.setBorder(new EmptyBorder(6, 6, 3, 6));
            previewWrap.add(preview);

            // ---- Scenes dock
            sceneList = new JList<>(sceneModel);
            styleList(sceneList);
            sceneList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            sceneList.addListSelectionListener(e -> {
                if (!e.getValueIsAdjusting() && !filling) switchScene(sceneList.getSelectedIndex());
            });
            sceneList.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2 && sceneList.isEnabled()) renameScene(); }
            });
            sceneAddBtn = iconButton(Ic.add(), "Add scene");
            sceneAddBtn.addActionListener(e -> addScene());
            sceneRemoveBtn = iconButton(Ic.trash(), "Remove scene");
            sceneRemoveBtn.addActionListener(e -> removeScene());
            JPanel scenes = dock("Setups", new JScrollPane(sceneList), toolbar(sceneAddBtn, sceneRemoveBtn));

            // ---- Sources dock
            sourceList = new JList<>(sourceModel);
            styleList(sourceList);
            sourceList.setCellRenderer(new DefaultListCellRenderer() {
                @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc) {
                    JLabel c = (JLabel) super.getListCellRendererComponent(l, v, i, sel, foc);
                    SourceItem it = (SourceItem) v;
                    c.setText(it.text());
                    c.setToolTipText(it.text());
                    c.setIcon(switch (it.kind()) {
                        case "region" -> Ic.region();
                        case "window" -> Ic.window();
                        case "desktop" -> Ic.desktopAudio();
                        case "mic" -> Ic.mic();
                        default -> Ic.display();
                    });
                    c.setIconTextGap(8);
                    return c;
                }
            });
            sourceList.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { if (e.getClickCount() == 2) sourceProperties(); }
            });
            srcAddBtn = iconButton(Ic.add(), "Add source");
            JPopupMenu addMenu = new JPopupMenu();
            JMenuItem a1 = new JMenuItem("Display Capture", Ic.display());
            a1.addActionListener(e -> openSourceDialog("monitor"));
            JMenuItem a2 = new JMenuItem("Region Capture", Ic.region());
            a2.addActionListener(e -> openSourceDialog("region"));
            addMenu.add(a1);
            addMenu.add(a2);
            if (WIN) {
                JMenuItem a3 = new JMenuItem("Window Capture", Ic.window());
                a3.addActionListener(e -> openSourceDialog("window"));
                addMenu.add(a3);
            }
            srcAddBtn.addActionListener(e -> addMenu.show(srcAddBtn, 0, srcAddBtn.getHeight()));
            srcPropsBtn = iconButton(Ic.gear(), "Properties");
            srcPropsBtn.addActionListener(e -> sourceProperties());
            JScrollPane srcScroll = new JScrollPane(sourceList, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            JPanel sources = dock("Inputs", srcScroll, toolbar(srcAddBtn, srcPropsBtn));

            // ---- Audio Mixer dock
            sysRow = new MixerRow("Desktop Audio", cfg.systemGain, cfg.systemMuted,
                    v -> { cfg.systemGain = v; pushAudio(!sysRow.slider.getValueIsAdjusting()); },
                    m -> { cfg.systemMuted = m; pushAudio(true); });
            micRow = new MixerRow("Mic/Aux", cfg.micGain, cfg.micMuted,
                    v -> { cfg.micGain = v; pushAudio(!micRow.slider.getValueIsAdjusting()); },
                    m -> { cfg.micMuted = m; pushAudio(true); });
            JPanel mixerBody = new JPanel();
            mixerBody.setLayout(new BoxLayout(mixerBody, BoxLayout.Y_AXIS));
            mixerBody.setBackground(C_PANEL);
            mixerBody.add(sysRow);
            mixerBody.add(micRow);
            mixerBody.add(Box.createVerticalGlue());
            JPanel mixer = dock("Sound Levels", mixerBody, null);

            // ---- Controls dock
            recBtn = new JButton("Start Recording");
            recBtn.addActionListener(e -> toggleRecord());
            pauseBtn = new JButton("Pause Recording");
            pauseBtn.addActionListener(e -> togglePause());
            settingsBtn = new JButton("Settings");
            settingsBtn.addActionListener(e -> openSettings("General"));
            folderBtn = new JButton("Show Recordings");
            folderBtn.addActionListener(e -> openFolder());
            exitBtn = new JButton("Exit");
            exitBtn.addActionListener(e -> onClose());
            JPanel ctl = new JPanel(new GridLayout(5, 1, 0, 4));
            ctl.setBackground(C_PANEL);
            ctl.setBorder(new EmptyBorder(8, 8, 8, 8));
            recBtn.setIcon(Ic.record());
            pauseBtn.setIcon(Ic.pause());
            settingsBtn.setIcon(Ic.gear());
            folderBtn.setIcon(Ic.folder());
            exitBtn.setIcon(Ic.exit());
            for (JButton b : new JButton[]{recBtn, pauseBtn, settingsBtn, folderBtn, exitBtn}) { b.setFocusable(false); b.setIconTextGap(8); ctl.add(b); }
            JPanel controls = dock("Actions", ctl, null);

            // ---- dock row
            JPanel docks = new JPanel(new GridBagLayout());
            docks.setBackground(C_BG);
            docks.setBorder(new EmptyBorder(3, 6, 6, 6));
            JPanel[] ds = {scenes, sources, mixer, controls};
            double[] wx = {1.0, 1.25, 1.9, 0.9};
            for (int i = 0; i < ds.length; i++) {
                GridBagConstraints g = new GridBagConstraints();
                g.gridx = i;
                g.weightx = wx[i];
                g.weighty = 1;
                g.fill = GridBagConstraints.BOTH;
                g.insets = new Insets(0, i == 0 ? 0 : 4, 0, 0);
                ds[i].setPreferredSize(new Dimension(200, 214));
                docks.add(ds[i], g);
            }

            // ---- status bar
            statusLbl = new JLabel("Starting...");
            statusLbl.setForeground(C_TEXT);
            recLbl = new JLabel("REC: 00:00:00", dotIcon(Color.GRAY), SwingConstants.LEFT);
            cpuLbl = new JLabel("CPU: --");
            fpsLbl = new JLabel("-- fps");
            kbpsLbl = new JLabel("0 kb/s");
            dropLbl = new JLabel("Dropped frames: 0");
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0));
            right.setOpaque(false);
            for (JLabel l : new JLabel[]{dropLbl, kbpsLbl, fpsLbl, cpuLbl, recLbl}) { l.setForeground(C_TEXT); right.add(l); }
            JPanel status = new JPanel(new BorderLayout());
            status.setBackground(C_HEADER);
            status.setBorder(new EmptyBorder(4, 10, 4, 4));
            status.add(statusLbl, BorderLayout.CENTER);
            status.add(right, BorderLayout.EAST);

            JPanel south = new JPanel(new BorderLayout());
            south.setBackground(C_BG);
            south.add(docks, BorderLayout.CENTER);
            south.add(status, BorderLayout.SOUTH);

            JPanel root = new JPanel(new BorderLayout());
            root.setBackground(C_BG);
            root.add(quickBar, BorderLayout.NORTH);
            root.add(previewWrap, BorderLayout.CENTER);
            root.add(south, BorderLayout.SOUTH);
            frame.setContentPane(root);
            Dimension scr = Toolkit.getDefaultToolkit().getScreenSize();      // fit small screens too
            frame.setSize(Math.min(1140, scr.width - 40), Math.min(780, scr.height - 60));
            frame.setMinimumSize(new Dimension(Math.min(900, scr.width - 40), Math.min(560, scr.height - 60)));

            // keyboard shortcuts (the tray icon covers the "window hidden" case)
            JRootPane rp = frame.getRootPane();
            rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("F9"), "rec");
            rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("F10"), "pause");
            rp.getActionMap().put("rec", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { toggleRecord(); }
            });
            rp.getActionMap().put("pause", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { togglePause(); }
            });
        }

        // ================================================================ tray icon

        /** The finnLabs CAPS logo, drawn for the requested size (32x32 is the reference). The centre dot turns red while recording. */
        static BufferedImage logo(int size, boolean recording) {
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.scale(size / 32.0, size / 32.0);
            g.setPaint(new GradientPaint(0, 0, new Color(0x4d94e8), 0, 32, new Color(0x1a3d78)));
            g.fill(new java.awt.geom.RoundRectangle2D.Double(0.5, 0.5, 31, 31, 9, 9));            // tile
            g.setColor(new Color(255, 255, 255, 46));
            g.fill(new java.awt.geom.RoundRectangle2D.Double(7, 9.5, 18, 13, 2, 2));               // the canvas
            g.setColor(new Color(255, 255, 255, 240));
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double x0 = 6, x1 = 26, y0 = 8.5, y1 = 23.5, L = 4.5;                                   // capture-frame corners
            g.draw(new java.awt.geom.Line2D.Double(x0, y0 + L, x0, y0));
            g.draw(new java.awt.geom.Line2D.Double(x0, y0, x0 + L, y0));
            g.draw(new java.awt.geom.Line2D.Double(x1 - L, y0, x1, y0));
            g.draw(new java.awt.geom.Line2D.Double(x1, y0, x1, y0 + L));
            g.draw(new java.awt.geom.Line2D.Double(x1, y1 - L, x1, y1));
            g.draw(new java.awt.geom.Line2D.Double(x1, y1, x1 - L, y1));
            g.draw(new java.awt.geom.Line2D.Double(x0 + L, y1, x0, y1));
            g.draw(new java.awt.geom.Line2D.Double(x0, y1, x0, y1 - L));
            if (recording) {
                g.setColor(Color.WHITE);
                g.fill(new java.awt.geom.Ellipse2D.Double(16 - 5, 16 - 5, 10, 10));
                g.setColor(new Color(0xFF3B30));
                g.fill(new java.awt.geom.Ellipse2D.Double(16 - 3.8, 16 - 3.8, 7.6, 7.6));
            } else {
                g.setColor(Color.WHITE);
                g.fill(new java.awt.geom.Ellipse2D.Double(16 - 3.6, 16 - 3.6, 7.2, 7.2));
            }
            g.dispose();
            return img;
        }

        /** Application icon at the requested size: GSource/icons/caps.png if present, else the built-in logo. */
        static BufferedImage appImage(int size, boolean recording) {
            BufferedImage custom = Ic.appIcon();
            if (custom == null) return logo(size, recording);
            BufferedImage img = Ic.scale(custom, size);
            if (recording) {                                                   // red badge bottom-right on a custom icon
                Graphics2D g = img.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                double d = size * 0.38, x = size - d - size * 0.02;
                g.setColor(Color.WHITE);
                g.fill(new java.awt.geom.Ellipse2D.Double(x - size * 0.04, x - size * 0.04, d + size * 0.08, d + size * 0.08));
                g.setColor(new Color(0xFF3B30));
                g.fill(new java.awt.geom.Ellipse2D.Double(x, x, d, d));
                g.dispose();
            }
            return img;
        }

        static List<Image> appImages(boolean recording) {
            return List.of(appImage(16, recording), appImage(32, recording), appImage(48, recording), appImage(64, recording));
        }

        Boolean iconRec;                                                       // last state the icons were drawn for

        void applyAppIcon(boolean rec) {
            if (iconRec != null && iconRec == rec) return;
            iconRec = rec;
            frame.setIconImages(appImages(rec));
            if (tray != null) tray.setImage(appImage(32, rec));
            try {                                                              // dock / taskbar where the platform supports it
                if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
                    Taskbar.getTaskbar().setIconImage(appImage(128, rec));
                }
            } catch (UnsupportedOperationException | SecurityException ignored) { }
        }

        void setupTray() {
            try {
                if (!SystemTray.isSupported()) return;
                PopupMenu menu = new PopupMenu();
                trayToggle = new MenuItem("Start Recording");
                trayToggle.addActionListener(e -> toggleRecord());
                trayPause = new MenuItem("Pause Recording");
                trayPause.addActionListener(e -> togglePause());
                MenuItem show = new MenuItem("Show Window");
                show.addActionListener(e -> showWindow());
                MenuItem quit = new MenuItem("Exit");
                quit.addActionListener(e -> onClose());
                menu.add(trayToggle);
                menu.add(trayPause);
                menu.addSeparator();
                menu.add(show);
                menu.add(quit);
                tray = new TrayIcon(appImage(32, false), APP_NAME, menu);
                tray.setImageAutoSize(true);
                tray.addActionListener(e -> showWindow());
                SystemTray.getSystemTray().add(tray);
            } catch (Exception e) {
                tray = null;
            }
        }

        void showWindow() {
            frame.setVisible(true);
            frame.setState(Frame.NORMAL);
            frame.toFront();
        }

        void hideWindow() {
            if (tray != null) frame.setVisible(false);
            else frame.setState(Frame.ICONIFIED);
        }

        // ================================================================ settings <-> widgets

        String currentMode() { return rbRegion.isSelected() ? "region" : rbWindow.isSelected() ? "window" : "monitor"; }

        /** cfg -> widgets (used at start-up, after discovery, and when a dialog is cancelled). */
        void syncWidgets() {
            filling = true;
            try {
                switch (cfg.captureMode) {
                    case "region" -> rbRegion.setSelected(true);
                    case "window" -> { if (WIN) rbWindow.setSelected(true); else rbMonitor.setSelected(true); }
                    default -> rbMonitor.setSelected(true);
                }
                cursorCb.setSelected(cfg.cursor);
                fillMonitors();
                if (WIN && windowCb != null && notEmpty(cfg.windowTitle)) {
                    DefaultComboBoxModel<String> m = (DefaultComboBoxModel<String>) windowCb.getModel();
                    if (m.getIndexOf(cfg.windowTitle) < 0) m.addElement(cfg.windowTitle);
                    windowCb.setSelectedItem(cfg.windowTitle);
                }
                fillAudio(sysCb, cfg.systemAudio, av.defaultSystem);
                fillAudio(micCb, cfg.micAudio, av.defaultMic);
                fillEncoders();
                qualityCb.setSelectedItem(cfg.quality);
                fpsCb.setSelectedItem(String.valueOf(cfg.fps));
                resCb.setSelectedItem(cfg.resolution);
                if (quickQualityCb != null) quickQualityCb.setSelectedItem(cfg.quality);
                if (quickFpsCb != null) quickFpsCb.setSelectedItem(String.valueOf(cfg.fps));
                if (quickResCb != null) quickResCb.setSelectedItem(cfg.resolution);
                presetCb.setSelectedItem(cfg.x264Preset);
                if (methodCb != null) methodCb.setSelectedIndex(cfg.captureMethod.equals("gdi") ? 1 : 0);
                offsetSp.setValue(clamp(cfg.audioOffsetMs, -1000, 2000));
                noiseReductionCb.setSelectedItem(cfg.noiseReduction);
                separateCb.setSelected(cfg.separateTracks);
                dirField.setText(cfg.outDir);
                formatCb.setSelectedIndex(cfg.container.equals("mp4") ? 1 : 0);
                minimizeCb.setSelected(cfg.minimizeOnStart);
                previewCb.setSelected(cfg.previewOn);
                metersCb.setSelected(cfg.metersOn);
                metersRecCb.setSelected(cfg.metersWhileRecording);
                previewRecCb.setSelected(cfg.previewWhileRecording);
                updateRegionLabel();
                updateFfLabel();
                updateBaseRes();
            } finally {
                filling = false;
            }
        }

        /** Source widgets -> cfg -> active scene. */
        void collectSource() {
            cfg.captureMode = currentMode();
            cfg.monitorIndex = Math.max(0, monitorCb.getSelectedIndex());
            if (WIN && windowCb != null && windowCb.getSelectedItem() != null) cfg.windowTitle = (String) windowCb.getSelectedItem();
            cfg.cursor = cursorCb.isSelected();
            commitScene();
        }

        void collectSettings() {
            if (WIN && methodCb != null) cfg.captureMethod = methodCb.getSelectedIndex() == 1 ? "gdi" : "auto";
            cfg.fps = Integer.parseInt((String) fpsCb.getSelectedItem());
            cfg.resolution = (String) resCb.getSelectedItem();
            cfg.quality = (String) qualityCb.getSelectedItem();
            int ei = encCb.getSelectedIndex();
            cfg.encoder = (ei > 0 && ei - 1 < caps.encoders.size()) ? caps.encoders.get(ei - 1) : "auto";
            cfg.x264Preset = (String) presetCb.getSelectedItem();
            if (ready) {
                cfg.systemAudio = audioValue(sysCb);
                cfg.micAudio = audioValue(micCb);
            }
            cfg.audioOffsetMs = (Integer) offsetSp.getValue();
            cfg.noiseReduction = (String) noiseReductionCb.getSelectedItem();
            cfg.separateTracks = separateCb.isSelected();
            cfg.outDir = dirField.getText().trim();
            cfg.container = formatCb.getSelectedIndex() == 1 ? "mp4" : "mkv";
            cfg.minimizeOnStart = minimizeCb.isSelected();
            cfg.previewOn = previewCb.isSelected();
            cfg.metersOn = metersCb.isSelected();
            cfg.metersWhileRecording = metersRecCb.isSelected();
            cfg.previewWhileRecording = previewRecCb.isSelected();
        }

        void collect() {
            collectSource();
            collectSettings();
        }

        void applyStates() {
            if (recBtn == null) return;
            boolean idle = state == State.IDLE, on = idle && ready;
            for (JComponent c : lockable) c.setEnabled(on);
            String mode = currentMode();
            monitorCb.setEnabled(on && mode.equals("monitor"));
            regionBtn.setEnabled(on && mode.equals("region"));
            if (windowCb != null) {
                windowCb.setEnabled(on && mode.equals("window"));
                refreshWinBtn.setEnabled(on && mode.equals("window"));
            }
            presetCb.setEnabled(on && resolvedEnc().equals("libx264"));
            ffBtn.setEnabled(idle);
            sceneList.setEnabled(on);
            sceneAddBtn.setEnabled(on);
            sceneRemoveBtn.setEnabled(on && cfg.scenes.size() > 1);
            srcAddBtn.setEnabled(on);
            srcPropsBtn.setEnabled(on);
            boolean stoppable = state == State.RECORDING || state == State.PAUSED;
            recBtn.setText(stoppable || state == State.BUSY ? "Stop Recording" : "Start Recording");
            recBtn.setIcon(stoppable || state == State.BUSY ? Ic.stop() : Ic.record());
            recBtn.setEnabled(ready && (idle || stoppable));
            pauseBtn.setText(state == State.PAUSED ? "Resume Recording" : "Pause Recording");
            pauseBtn.setIcon(state == State.PAUSED ? Ic.play() : Ic.pause());
            pauseBtn.setEnabled(stoppable);
            if (trayToggle != null) {
                trayToggle.setLabel(idle ? "Start Recording" : "Stop Recording");
                trayToggle.setEnabled(recBtn.isEnabled());
                trayPause.setLabel(state == State.PAUSED ? "Resume Recording" : "Pause Recording");
                trayPause.setEnabled(pauseBtn.isEnabled());
            }
            boolean rec = state == State.RECORDING;
            applyAppIcon(rec);
            preview.recording = rec;
            syncMeters();
        }

        void updateRegionLabel() {
            regionLbl.setText(cfg.region == null ? "none selected"
                    : cfg.region[2] + " x " + cfg.region[3] + " at (" + cfg.region[0] + ", " + cfg.region[1] + ")");
        }

        void updateFfLabel() {
            ffLbl.setText(ffmpeg == null ? "not found" : "FFmpeg " + caps.version);
            ffLbl.setToolTipText(ffmpeg);
        }

        String captureSizeText() {
            if (cfg.captureMode.equals("window")) return "Window (size follows the window)";
            if (cfg.captureMode.equals("region")) return cfg.region == null ? "-" : cfg.region[2] + "x" + cfg.region[3];
            if (mons.isEmpty()) return "-";
            Monitor m = mons.get(clamp(cfg.monitorIndex, 0, mons.size() - 1));
            return m.phys().width + "x" + m.phys().height;
        }

        void updateBaseRes() { baseResLbl.setText(captureSizeText()); }

        void setStatus(String s, boolean error) {
            statusLbl.setText(s);
            statusLbl.setForeground(error ? new Color(0xff6b6b) : C_TEXT);
            statusLbl.setToolTipText(s);
        }

        @Override public void log(String line) {
            SwingUtilities.invokeLater(() -> {
                logArea.append(line + "\n");
                if (logArea.getLineCount() > 800) {
                    try { logArea.replaceRange("", 0, logArea.getLineEndOffset(200)); } catch (Exception ignored) { }
                }
                logArea.setCaretPosition(logArea.getDocument().getLength());
            });
        }

        void alert(int type, String title, String msg) {
            String m = msg.length() > 1400 ? msg.substring(0, 1400) + "..." : msg;
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(frame, m, title, type, Ic.dialog(type)));
        }

        // ================================================================ scenes & sources

        void ensureScenes() {
            if (cfg.scenes.isEmpty()) {
                Scene s = new Scene("Scene");
                s.mode = cfg.captureMode;
                s.monitor = cfg.monitorIndex;
                s.region = cfg.region;
                s.window = cfg.windowTitle;
                s.cursor = cfg.cursor;
                cfg.scenes.add(s);
                cfg.activeScene = 0;
            }
            cfg.activeScene = clamp(cfg.activeScene, 0, cfg.scenes.size() - 1);
            loadScene(cfg.scenes.get(cfg.activeScene));
        }

        void loadScene(Scene s) {
            cfg.captureMode = s.mode;
            cfg.monitorIndex = s.monitor;
            cfg.region = s.region;
            cfg.windowTitle = s.window;
            cfg.cursor = s.cursor;
        }

        void commitScene() {
            Scene s = cfg.scenes.get(cfg.activeScene);
            s.mode = cfg.captureMode;
            s.monitor = cfg.monitorIndex;
            s.region = cfg.region;
            s.window = cfg.windowTitle;
            s.cursor = cfg.cursor;
        }

        void refreshScenes() {
            boolean was = filling;
            filling = true;
            sceneModel.clear();
            for (Scene s : cfg.scenes) sceneModel.addElement(s.name);
            sceneList.setSelectedIndex(cfg.activeScene);
            filling = was;
            updateTitle();
        }

        void updateTitle() {
            frame.setTitle(APP_NAME + " - Profile: Untitled - Scene: " + cfg.scenes.get(cfg.activeScene).name);
        }

        void switchScene(int i) {
            if (i < 0 || i >= cfg.scenes.size() || i == cfg.activeScene || state != State.IDLE) return;
            commitScene();
            cfg.activeScene = i;
            loadScene(cfg.scenes.get(i));
            syncWidgets();
            refreshSources();
            updateTitle();
            if (sceneList.getSelectedIndex() != i) {          // keep the highlight in step when switched programmatically
                filling = true;
                sceneList.setSelectedIndex(i);
                filling = false;
            }
            cfg.save();
        }

        void addScene() {
            if (state != State.IDLE) return;
            String name = askText("Add Scene", "Scene " + (cfg.scenes.size() + 1));
            if (name == null || name.isBlank()) return;
            commitScene();
            Scene s = new Scene(name.trim());
            cfg.scenes.add(s);
            cfg.activeScene = cfg.scenes.size() - 1;
            loadScene(s);
            refreshScenes();
            syncWidgets();
            refreshSources();
            applyStates();
            cfg.save();
        }

        String askText(String title, String initial) {
            Object r = JOptionPane.showInputDialog(frame, "Scene name:", title, JOptionPane.QUESTION_MESSAGE,
                    Ic.dialog(JOptionPane.QUESTION_MESSAGE), null, initial);
            return r == null ? null : r.toString();
        }

        void renameScene() {
            if (state != State.IDLE) return;
            Scene s = cfg.scenes.get(cfg.activeScene);
            String name = askText("Rename Scene", s.name);
            if (name == null || name.isBlank()) return;
            s.name = name.trim();
            refreshScenes();
            cfg.save();
        }

        void removeScene() {
            if (state != State.IDLE) return;
            if (cfg.scenes.size() <= 1) {
                alert(JOptionPane.INFORMATION_MESSAGE, APP_NAME, "At least one scene is required.");
                return;
            }
            Scene s = cfg.scenes.get(cfg.activeScene);
            if (JOptionPane.showConfirmDialog(frame, "Remove scene '" + s.name + "'?", "Remove Scene", JOptionPane.YES_NO_OPTION,
                    JOptionPane.QUESTION_MESSAGE, Ic.dialog(JOptionPane.QUESTION_MESSAGE)) != JOptionPane.YES_OPTION) return;
            cfg.scenes.remove(cfg.activeScene);
            cfg.activeScene = clamp(cfg.activeScene - 1, 0, cfg.scenes.size() - 1);
            loadScene(cfg.scenes.get(cfg.activeScene));
            refreshScenes();
            syncWidgets();
            refreshSources();
            applyStates();
            cfg.save();
        }

        String deviceLabel(String v, String def) {
            String val = v != null ? v : def;
            if (!notEmpty(val)) return "none";
            for (Device d : av.audio) if (d.value().equals(val)) return d.label();
            return val;
        }

        void refreshSources() {
            sourceModel.clear();
            String v = switch (cfg.captureMode) {
                case "region" -> "Region Capture (" + (cfg.region == null ? "not set" : cfg.region[2] + "x" + cfg.region[3]) + ")";
                case "window" -> "Window Capture (" + (notEmpty(cfg.windowTitle) ? cfg.windowTitle : "not set") + ")";
                default -> "Display Capture (" + (mons.isEmpty() ? "Display 1" : mons.get(clamp(cfg.monitorIndex, 0, mons.size() - 1)).name()) + ")";
            };
            sourceModel.addElement(new SourceItem(cfg.captureMode, v));
            sourceModel.addElement(new SourceItem("desktop", "Desktop Audio (" + deviceLabel(cfg.systemAudio, av.defaultSystem) + ")"));
            sourceModel.addElement(new SourceItem("mic", "Mic/Aux (" + deviceLabel(cfg.micAudio, av.defaultMic) + ")"));
            preview.note = "";
        }

        void sourceProperties() {
            if (state != State.IDLE) return;
            int i = sourceList.getSelectedIndex();
            if (i > 0) openSettings("Audio");
            else openSourceDialog(null);
        }

        // ================================================================ discovery

        void discover(boolean full) {
            if (ffmpeg == null) {
                setStatus("FFmpeg not found. Install it (Windows: winget install Gyan.FFmpeg | macOS: brew install ffmpeg | Linux: sudo apt install ffmpeg) or use Tools > Locate FFmpeg.", true);
                updateFfLabel();
                applyStates();
                return;
            }
            setStatus(full ? "Detecting devices and testing encoders..." : "Refreshing devices...", false);
            String ff = ffmpeg;
            new Thread(() -> {
                Caps c = full ? probeCaps(ff) : null;
                AVDevices a = discoverAv(ff);
                SwingUtilities.invokeLater(() -> onDiscovered(c, a));
            }, "discover").start();
            if (WIN && full) refreshWindows();
        }

        void onDiscovered(Caps c, AVDevices a) {
            String keepSys = ready ? audioValue(sysCb) : cfg.systemAudio;      // a refresh must not undo unsaved choices
            String keepMic = ready ? audioValue(micCb) : cfg.micAudio;
            if (c != null) caps = c;
            av = a;
            mons = listMonitors(av.macScreens);
            ready = ffmpeg != null && !caps.encoders.isEmpty();
            if (c != null) {
                syncWidgets();
            } else {
                filling = true;
                try {
                    fillMonitors();
                    fillAudio(sysCb, keepSys, av.defaultSystem);
                    fillAudio(micCb, keepMic, av.defaultMic);
                } finally {
                    filling = false;
                }
            }
            refreshSources();
            updateBaseRes();
            updateFfLabel();
            applyStates();
            if (ready) {
                setStatus("Ready.", false);
                log("FFmpeg " + caps.version + "; usable encoders: " + caps.encoders + (caps.ddagrab ? "; DXGI capture available" : ""));
            } else {
                setStatus("No usable H.264 encoder found in this FFmpeg build (need libx264 or a GPU encoder).", true);
            }
        }

        void fillMonitors() {
            if (mons.isEmpty()) return;
            monitorCb.setModel(new DefaultComboBoxModel<>(mons.stream()
                    .map(m -> m.name() + "  -  " + m.phys().width + "x" + m.phys().height).toArray(String[]::new)));
            monitorCb.setSelectedIndex(clamp(cfg.monitorIndex, 0, mons.size() - 1));
        }

        void fillAudio(JComboBox<String> cb, String saved, String def) {
            List<String> labels = new ArrayList<>();
            labels.add("None");
            av.audio.forEach(d -> labels.add(d.label()));
            cb.setModel(new DefaultComboBoxModel<>(labels.toArray(new String[0])));
            String target = saved != null ? saved : def;
            int idx = 0;
            for (int i = 0; i < av.audio.size(); i++) if (av.audio.get(i).value().equals(target)) idx = i + 1;
            cb.setSelectedIndex(idx);
        }

        void fillEncoders() {
            List<String> labels = new ArrayList<>();
            labels.add("Auto (best available)");
            caps.encoders.forEach(e -> labels.add(ENC_LABEL.getOrDefault(e, e)));
            encCb.setModel(new DefaultComboBoxModel<>(labels.toArray(new String[0])));
            int idx = caps.encoders.indexOf(cfg.encoder);
            encCb.setSelectedIndex(idx >= 0 ? idx + 1 : 0);
        }

        String audioValue(JComboBox<String> cb) {
            int i = cb.getSelectedIndex();
            return (i <= 0 || i - 1 >= av.audio.size()) ? "" : av.audio.get(i - 1).value();
        }

        String resolvedEnc() {
            int i = encCb.getSelectedIndex();
            if (i > 0 && i - 1 < caps.encoders.size()) return caps.encoders.get(i - 1);
            return caps.encoders.isEmpty() ? "libx264" : caps.encoders.get(0);
        }

        void refreshWindows() {
            if (!WIN) return;
            new Thread(() -> {
                Result r = run(List.of("powershell", "-NoProfile", "-Command",
                        "[Console]::OutputEncoding=[Text.Encoding]::UTF8; Get-Process | Where-Object {$_.MainWindowTitle} | Select-Object -ExpandProperty MainWindowTitle"),
                        20, null);
                TreeSet<String> titles = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
                for (String l : r.out().split("\\R")) if (notEmpty(l) && !l.trim().startsWith(APP_NAME)) titles.add(l.trim());
                if (notEmpty(cfg.windowTitle)) titles.add(cfg.windowTitle);
                SwingUtilities.invokeLater(() -> {
                    Object keep = windowCb.getSelectedItem();
                    windowCb.setModel(new DefaultComboBoxModel<>(titles.toArray(new String[0])));
                    if (keep != null) windowCb.setSelectedItem(keep);
                });
            }, "windows").start();
        }

        // ================================================================ dialogs helpers

        void browseDir() {
            JFileChooser fc = new JFileChooser(dirField.getText());
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) dirField.setText(fc.getSelectedFile().getAbsolutePath());
        }

        void locateFfmpeg() {
            JFileChooser fc = new JFileChooser();
            fc.setDialogTitle("Select the ffmpeg executable");
            if (fc.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
                cfg.ffmpegPath = fc.getSelectedFile().getAbsolutePath();
                ffmpeg = findFfmpeg(cfg.ffmpegPath);
                discover(true);
            }
        }

        void openFolder() {
            Path dir = Paths.get(cfg.outDir);
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(dir.toFile());
                    return;
                }
            } catch (Exception ignored) { }
            try {
                new ProcessBuilder(WIN ? "explorer" : MAC ? "open" : "xdg-open", dir.toString()).start();
            } catch (IOException e) {
                alert(JOptionPane.ERROR_MESSAGE, APP_NAME, "Could not open " + dir);
            }
        }

        Rectangle virtualLogicalBounds() {
            List<Monitor> real = realMonitors(mons);
            if (real.isEmpty()) return new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            Rectangle u = new Rectangle(real.get(0).logical());
            for (Monitor m : real) u = u.union(m.logical());
            return u;
        }

        void selectRegion() {
            boolean reopen = sourceDlg.isVisible();
            sourceDlg.setVisible(false);
            frame.setVisible(false);
            Timer t = new Timer(350, e -> {                     // give the windows time to disappear
                try {
                    new RegionSelector(virtualLogicalBounds(), mons, rect -> {
                        showWindow();
                        if (rect != null) {
                            cfg.region = new int[]{rect.x, rect.y, rect.width, rect.height};
                            rbRegion.setSelected(true);
                            updateRegionLabel();
                            applyStates();
                        }
                        if (reopen) openSourceDialog(null);
                    });
                } catch (AWTException | SecurityException ex) {
                    showWindow();
                    alert(JOptionPane.ERROR_MESSAGE, APP_NAME, "Could not capture the screen for region selection: " + ex.getMessage());
                    if (reopen) openSourceDialog(null);
                }
            });
            t.setRepeats(false);
            t.start();
        }

        // ================================================================ preview

        Rectangle previewRect() {
            if (mons.isEmpty() || cfg.captureMode.equals("window")) return null;
            if (cfg.captureMode.equals("region")) {
                if (cfg.region == null) return null;
                double s = 1.0;
                for (Monitor m : realMonitors(mons)) if (m.phys().contains(cfg.region[0], cfg.region[1])) { s = m.scale(); break; }
                return new Rectangle((int) Math.round(cfg.region[0] / s), (int) Math.round(cfg.region[1] / s),
                        (int) Math.round(cfg.region[2] / s), (int) Math.round(cfg.region[3] / s));
            }
            return new Rectangle(mons.get(clamp(cfg.monitorIndex, 0, mons.size() - 1)).logical());
        }

        void previewStep() {
            preview.enabled = cfg.previewOn;
            boolean rec = state == State.RECORDING || state == State.BUSY;
            int delay = rec ? 500 : 250;      // cheaper while recording
            if (previewTick.getDelay() != delay) previewTick.setDelay(delay);
            // The preview grabs its own screenshot (java.awt.Robot), separate from FFmpeg's capture.
            // Doing both at once competes for CPU/GPU and is a common cause of dropped frames, so by
            // default the live preview pauses for the duration of the recording unless the user opts in.
            if (rec && !cfg.previewWhileRecording) {
                preview.img = null;
                preview.note = "Live preview paused while recording (Settings > General to keep it on)";
                preview.repaint();
                return;
            }
            if (!cfg.previewOn || !frame.isShowing() || (frame.getExtendedState() & Frame.ICONIFIED) != 0) {
                preview.repaint();
                return;
            }
            Rectangle r = previewRect();
            if (r == null) {
                preview.img = null;
                preview.note = cfg.captureMode.equals("window") ? "Window capture (no preview)" : mons.isEmpty() ? "" : "No region selected";
                preview.repaint();
                return;
            }
            if (!previewBusy.compareAndSet(false, true)) return;
            previewExec.execute(() -> {
                try {
                    if (robot == null) robot = new Robot();
                    BufferedImage full = robot.createScreenCapture(r);
                    double s = Math.min(1.0, Math.min(960.0 / full.getWidth(), 540.0 / full.getHeight()));
                    BufferedImage out = full;
                    if (s < 1.0) {
                        out = new BufferedImage(Math.max(1, (int) (full.getWidth() * s)), Math.max(1, (int) (full.getHeight() * s)), BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = out.createGraphics();
                        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                        g.drawImage(full, 0, 0, out.getWidth(), out.getHeight(), null);
                        g.dispose();
                    }
                    preview.img = out;
                } catch (Exception e) {
                    preview.img = null;
                    preview.note = "Preview unavailable";
                } finally {
                    previewBusy.set(false);
                    SwingUtilities.invokeLater(preview::repaint);
                }
            });
        }

        // ================================================================ audio meters & live mixer

        void syncMeters() {
            boolean want = ready && ffmpeg != null && cfg.metersOn && switch (state) {
                case IDLE, PAUSED, FINALIZING -> true;
                case RECORDING -> segLive && cfg.metersWhileRecording;
                case BUSY -> false;
            };
            String s = want ? nz(cfg.systemAudio) : "", m = want ? nz(cfg.micAudio) : "";
            if (!s.equals(sysMeter.dev)) sysMeter.start(ffmpeg, s);
            if (!m.equals(micMeter.dev)) micMeter.start(ffmpeg, m);
        }

        static double gainDb(int gain, boolean muted) {
            return muted || gain <= 0 ? -90 : 20 * Math.log10(gain / 100.0);
        }

        void updateMeters() {
            if (sysRow == null) return;
            double gs = gainDb(cfg.systemGain, cfg.systemMuted), gm = gainDb(cfg.micGain, cfg.micMuted);
            boolean on = cfg.metersOn;
            sysRow.meter.update(on ? sysMeter.rms + gs : -90, on ? sysMeter.peak + gs : -90);
            micRow.meter.update(on ? micMeter.rms + gm : -90, on ? micMeter.peak + gm : -90);
        }

        /** Sends the current fader/mute values to the running capture (no restart needed). */
        void pushAudio(boolean force) {
            FFProc p = proc;
            if (p == null || !segLive) return;
            long now = System.nanoTime();
            if (!force && now - lastPushNs < 60_000_000L) return;
            lastPushNs = now;
            if (notEmpty(cfg.systemAudio)) p.command("volume@sys", "volume", volumeArg(cfg.systemGain, cfg.systemMuted));
            if (notEmpty(cfg.micAudio)) p.command("volume@mic", "volume", volumeArg(cfg.micGain, cfg.micMuted));
        }

        // ================================================================ recording control

        void toggleRecord() {
            if (state == State.IDLE) startRecording();
            else if (state == State.RECORDING || state == State.PAUSED) stopRecording();
        }

        void togglePause() {
            if (state == State.RECORDING) pauseRecording();
            else if (state == State.PAUSED) resumeRecording();
        }

        void startRecording() {
            if (state != State.IDLE || !ready) return;
            collect();
            if (cfg.captureMode.equals("region") && cfg.region == null) {
                alert(JOptionPane.INFORMATION_MESSAGE, APP_NAME, "This scene has no region yet. Open the source properties and drag out a region.");
                return;
            }
            if (cfg.captureMode.equals("window") && !notEmpty(cfg.windowTitle)) {
                alert(JOptionPane.INFORMATION_MESSAGE, APP_NAME, "Pick a window first (source properties > Window, use Refresh if it is not listed).");
                return;
            }
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            try {
                Path outDir = Paths.get(cfg.outDir);
                Files.createDirectories(outDir);
                finalPath = outDir.resolve("Recording_" + stamp + "." + cfg.container);
                tmpDir = outDir.resolve(".recording_" + stamp);
                Files.createDirectories(tmpDir);
            } catch (IOException | InvalidPathException e) {
                alert(JOptionPane.ERROR_MESSAGE, APP_NAME, "Cannot write to the output folder:\n" + e.getMessage());
                return;
            }
            segments.clear();
            segIndex = 0;
            elapsedDone = 0;
            sizeDone = curSize = 0;
            dropDone = framesDone = 0;
            lastFps = lastSpeed = "";
            pending = null;
            enc = resolvedEnc();
            useDxgi = WIN && caps.ddagrab && cfg.captureMethod.equals("auto");
            cfg.save();
            sysMeter.stop();                     // release the audio devices before the recording opens them
            micMeter.stop();
            if (!startSegment()) {
                deleteTree(tmpDir);
                applyStates();
                return;
            }
            state = State.RECORDING;
            setStatus("Starting FFmpeg (opening devices)...", false);
            applyStates();
            if (cfg.minimizeOnStart) hideWindow();
        }

        boolean startSegment() {
            segIndex++;
            Path seg = tmpDir.resolve(String.format("part%03d.mkv", segIndex));
            Built b = buildCommand(ffmpeg, cfg, mons, enc, useDxgi, seg);
            dxgiActive = b.dxgi();
            log("$ " + pretty(b.cmd()));
            try {
                proc = new FFProc(b.cmd(), segIndex, this);
            } catch (IOException e) {
                segIndex--;
                alert(JOptionPane.ERROR_MESSAGE, APP_NAME, "Could not start FFmpeg:\n" + e.getMessage());
                return false;
            }
            segments.add(seg);
            segLive = false;                     // FFmpeg needs a moment to open the devices
            segStartNs = System.nanoTime();
            curSize = 0;
            dropCur = framesCur = 0;
            lastSizeNs = 0;
            kbps = 0;
            return true;
        }

        void pauseRecording() {
            if (state != State.RECORDING || proc == null) return;
            pending = "pause";
            state = State.BUSY;
            setStatus("Pausing...", false);
            applyStates();
            proc.quit();
        }

        void resumeRecording() {
            if (state != State.PAUSED) return;
            sysMeter.stop();
            micMeter.stop();
            if (startSegment()) {
                state = State.RECORDING;
                setStatus("Resuming (opening devices)...", false);
            }
            applyStates();
        }

        void stopRecording() {
            if (state == State.RECORDING && proc != null) {
                pending = "stop";
                state = State.BUSY;
                setStatus("Stopping...", false);
                applyStates();
                proc.quit();
            } else if (state == State.PAUSED) {
                beginFinalize();
            }
        }

        // ---- FFProc.Listener: called from background threads, hop to the EDT
        @Override public void stats(int tag, Map<String, String> s) { SwingUtilities.invokeLater(() -> onStats(tag, s)); }
        @Override public void exited(int tag, int rc, List<String> tail) { SwingUtilities.invokeLater(() -> onExit(tag, rc, tail)); }

        static long parseLong(String s) {
            try { return Long.parseLong(s == null ? "0" : s.trim()); } catch (NumberFormatException e) { return 0; }
        }

        void onStats(int tag, Map<String, String> s) {
            if (proc == null || tag != proc.tag) return;
            long frames = parseLong(s.get("frame")), outUs = parseLong(s.get("out_time_us"));
            if (!segLive && frames > 0) {        // first real frame: the file's timeline starts here
                segLive = true;
                segStartNs = System.nanoTime() - Math.max(0, outUs) * 1000L;
                if (state == State.RECORDING) setStatus("Recording to " + finalPath + "   (encoder: " + ENC_LABEL.getOrDefault(enc, enc) + ")", false);
                applyStates();                   // lets the meters resume now that the recording holds the devices
                pushAudio(true);                 // in case a fader moved while FFmpeg was starting
            }
            curSize = parseLong(s.get("total_size"));
            framesCur = frames;
            dropCur = parseLong(s.get("drop_frames"));
            lastFps = s.getOrDefault("fps", "");
            lastSpeed = s.getOrDefault("speed", "").trim();
            long now = System.nanoTime();
            if (lastSizeNs != 0 && now > lastSizeNs && curSize >= lastSizeSample) {
                double inst = (curSize - lastSizeSample) * 8.0 / 1000.0 / ((now - lastSizeNs) / 1e9);
                kbps = kbps == 0 ? inst : kbps * 0.6 + inst * 0.4;
            }
            lastSizeSample = curSize;
            lastSizeNs = now;
        }

        void onExit(int tag, int rc, List<String> tail) {
            if (proc == null || tag != proc.tag) return;
            proc = null;
            Path seg = segments.get(segments.size() - 1);
            double dur = segLive ? (System.nanoTime() - segStartNs) / 1e9 : 0;
            long size = fileSize(seg);
            boolean produced = segLive;
            dropDone += dropCur;
            framesDone += framesCur;
            dropCur = framesCur = 0;
            kbps = 0;
            String pend = pending;
            pending = null;

            if (pend != null) {                                       // we asked it to stop
                elapsedDone += dur;
                sizeDone += size;
                curSize = 0;
                if (pend.equals("pause")) {
                    state = State.PAUSED;
                    setStatus("Paused - press Resume Recording to continue in the same file.", false);
                    applyStates();
                    if (quitRequested) stopRecording();
                } else {
                    beginFinalize();
                }
                return;
            }

            boolean early = !produced && segments.size() == 1;        // died before producing a single frame
            if (early && dxgiActive) {
                log("DXGI capture failed - falling back to GDI capture.");
                try { Files.deleteIfExists(seg); } catch (IOException ignored) { }
                segments.remove(segments.size() - 1);
                segIndex--;
                useDxgi = false;
                if (startSegment()) return;
            }
            if (early && rc != 0) {
                alert(JOptionPane.ERROR_MESSAGE, "Recording failed", tailText(tail, rc));
                deleteTree(tmpDir);
                state = State.IDLE;
                setStatus("Recording failed - see View > Log for details.", true);
                applyStates();
                showWindow();
                return;
            }
            elapsedDone += dur;                                       // died mid-recording: salvage what we have
            sizeDone += size;
            curSize = 0;
            if (rc != 0) alert(JOptionPane.WARNING_MESSAGE, "Recording stopped unexpectedly", tailText(tail, rc));
            beginFinalize();
        }

        static String tailText(List<String> tail, int rc) {
            if (tail.isEmpty()) return "FFmpeg exited with code " + rc + ".";
            return String.join("\n", tail.subList(Math.max(0, tail.size() - 8), tail.size()));
        }

        void beginFinalize() {
            state = State.FINALIZING;
            setStatus("Finalizing file...", false);
            applyStates();
            List<Path> segs = new ArrayList<>(segments);
            Path fin = finalPath, tmp = tmpDir;
            String container = cfg.container, ff = ffmpeg;
            new Thread(() -> {
                Path result = null;
                String err = null;
                try {
                    result = finalizeFiles(ff, segs, fin, container, tmp);
                } catch (Exception e) {
                    err = e.getMessage() + (Files.exists(tmp) ? "\nRaw segments kept in: " + tmp : "");
                }
                Path r = result;
                String er = err;
                SwingUtilities.invokeLater(() -> onFinal(r, er));
            }, "finalize").start();
        }

        void onFinal(Path result, String err) {
            state = State.IDLE;
            applyStates();
            showWindow();
            if (err != null) {
                setStatus("Could not finish the recording: " + err.replace('\n', ' '), true);
                alert(JOptionPane.ERROR_MESSAGE, APP_NAME, err);
            } else {
                setStatus("Saved: " + result + "  (" + humanSize(fileSize(result)) + ", " + fmtHms(elapsedDone) + ")", false);
            }
            if (quitRequested) shutdown();
        }

        void onClose() {
            if (state != State.IDLE) {
                int r = JOptionPane.showConfirmDialog(frame, "A recording is in progress. Stop, save and quit?",
                        APP_NAME, JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, Ic.dialog(JOptionPane.QUESTION_MESSAGE));
                if (r != JOptionPane.YES_OPTION) return;
                quitRequested = true;
                if (state == State.RECORDING || state == State.PAUSED) stopRecording();
                return;
            }
            shutdown();
        }

        void shutdown() {
            try { collect(); cfg.save(); } catch (Exception ignored) { }
            clockTick.stop();
            meterTick.stop();
            previewTick.stop();
            previewExec.shutdownNow();
            sysMeter.stop();
            micMeter.stop();
            if (tray != null) SystemTray.getSystemTray().remove(tray);
            frame.dispose();
            System.exit(0);
        }

        // ================================================================ live readout (status bar)

        void sampleCpu(long now) {
            long dt = now - lastCpuSampleNs;
            if (dt < 1_000_000_000L) return;
            lastCpuSampleNs = now;
            List<ProcessHandle> hs = new ArrayList<>();
            hs.add(ProcessHandle.current());
            FFProc fp = proc;
            if (fp != null && fp.p.isAlive()) hs.add(fp.p.toHandle());
            ProcessHandle h1 = sysMeter.handle(), h2 = micMeter.handle();
            if (h1 != null) hs.add(h1);
            if (h2 != null) hs.add(h2);
            double delta = 0;
            boolean any = false;
            Set<Long> seen = new HashSet<>();
            for (ProcessHandle h : hs) {
                Optional<Duration> d = h.info().totalCpuDuration();
                if (d.isEmpty()) continue;
                any = true;
                long c = d.get().toNanos(), pid = h.pid();
                seen.add(pid);
                Long prev = cpuLast.put(pid, c);
                if (prev != null && c >= prev) delta += c - prev;
            }
            cpuLast.keySet().retainAll(seen);
            if (any) cpuPct = Math.min(100, 100.0 * delta / (dt * (double) Runtime.getRuntime().availableProcessors()));
        }

        void updateClock() {
            blink = !blink;
            long now = System.nanoTime();
            sampleCpu(now);
            boolean running = proc != null && segLive;
            double cur = running ? (now - segStartNs) / 1e9 : 0;
            double total = elapsedDone + cur;
            Color dot = Color.GRAY;
            if (state == State.RECORDING || (state == State.BUSY && running)) dot = blink ? C_REC : new Color(0x7F1D1D);
            else if (state == State.PAUSED) dot = new Color(0xF9A825);
            recLbl.setIcon(dotIcon(dot));
            recLbl.setText("REC: " + fmtHms(state == State.IDLE || state == State.FINALIZING ? elapsedDone : total));
            cpuLbl.setText(cpuPct < 0 ? "CPU: n/a" : String.format(Locale.ROOT, "CPU: %.1f%%", cpuPct));
            boolean active = state == State.RECORDING || state == State.PAUSED || state == State.BUSY;
            boolean liveRow = active && running && !lastFps.isEmpty();
            fpsLbl.setText(liveRow ? lastFps + " fps" : "-- fps");
            kbpsLbl.setText(liveRow ? String.format(Locale.ROOT, "%d kb/s", (long) kbps) : "0 kb/s");
            long drops = dropDone + dropCur, frames = framesDone + framesCur + drops;
            dropLbl.setText(String.format(Locale.ROOT, "Dropped frames: %d (%.1f%%)", drops, frames == 0 ? 0.0 : 100.0 * drops / frames));
            dropLbl.setForeground(drops > 0 ? new Color(0xffb74d) : C_TEXT);
            boolean slow = false;
            if (running && cur > 6 && !lastSpeed.isEmpty() && !lastSpeed.equals("N/A")) {
                try { slow = Double.parseDouble(lastSpeed.replace("x", "")) < 0.95; } catch (NumberFormatException ignored) { }
            }
            if (slow) setStatus("Encoder can't keep up (speed " + lastSpeed + ") - lower the FPS or output size, or use a GPU encoder.", true);
            if (tray != null) tray.setToolTip(APP_NAME + " - " + (state == State.IDLE ? "idle" : fmtHms(total)));
        }
    }

    // ======================================================================
    // Finalization: rename / concat pause segments / remux to MP4 (stream copy, no re-encode)
    // ======================================================================

    static Path finalizeFiles(String ff, List<Path> segs, Path fin, String container, Path tmp) throws Exception {
        List<Path> good = new ArrayList<>();
        for (Path s : segs) if (Files.exists(s) && Files.size(s) > 2048) good.add(s);
        if (good.isEmpty()) {
            deleteTree(tmp);
            throw new IOException("Nothing was recorded.");
        }
        if (good.size() == 1 && container.equals("mkv")) {
            Files.move(good.get(0), fin, StandardCopyOption.REPLACE_EXISTING);
        } else {
            List<String> cmd = new ArrayList<>(List.of(ff, "-hide_banner", "-y", "-loglevel", "error"));
            if (good.size() == 1) {
                cmd.addAll(List.of("-i", good.get(0).toString()));
            } else {
                StringBuilder sb = new StringBuilder();
                for (Path s : good) sb.append("file '").append(s.toString().replace('\\', '/').replace("'", "'\\''")).append("'\n");
                Path list = tmp.resolve("list.txt");
                Files.writeString(list, sb.toString(), StandardCharsets.UTF_8);
                cmd.addAll(List.of("-f", "concat", "-safe", "0", "-i", list.toString()));
            }
            cmd.addAll(List.of("-map", "0", "-c", "copy"));
            if (container.equals("mp4")) cmd.addAll(List.of("-movflags", "+faststart"));
            cmd.add(fin.toString());
            Result r = run(cmd, 3600, null);
            if (r.code() != 0) throw new IOException(r.out().isBlank() ? "Remux failed." : r.out().trim());
        }
        deleteTree(tmp);
        return fin;
    }

    // ======================================================================
    // Entry point
    // ======================================================================

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            installTheme();
            new App();
        });
    }
}
