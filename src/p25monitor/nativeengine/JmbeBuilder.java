package p25monitor.nativeengine;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.jar.*;
import java.util.zip.*;

/**
 * Builds the JMBE codec library on the user's machine from its open-source code (github.com/DSheirer/jmbe), so
 * TrunkDeck never distributes a compiled IMBE vocoder. Needs a Java compiler: the installers put a full JDK (Eclipse
 * Temurin) in the install folder as TrunkDeck's runtime, so its built-in compiler is used; javac on PATH / JAVA_HOME
 * is the fallback when TrunkDeck runs on another Java.
 */
public final class JmbeBuilder {
    public static final String NOTICE = "<html><body style='width:520px'><b>About the P25 voice codec (IMBE)</b><br><br>" +
            "P25 Phase 1 voice uses the IMBE vocoder, which is covered by patents held by Digital Voice Systems, Inc. (DVSI). " +
            "TrunkDeck does <b>not</b> include a compiled vocoder.<br><br>This step downloads the source code of <b>JMBE</b> " +
            "(an open-source Java implementation by Dennis Sheirer, GPLv3) from GitHub and compiles it <b>on your computer</b>, " +
            "Depending on where you live, using it may require a patent licence; " +
            "you are responsible for deciding whether you may use it.<br><br>Without it the engine still follows and " +
            "logs calls - they are just silent.</body></html>";

    final Path profile, engineLib;
    final Consumer<String> out;

    public JmbeBuilder(Path profile, Consumer<String> out) {
        this.profile = profile; this.out = out;
        this.engineLib = EngineLibs.engineLib();
    }

    /** Finds a javac executable (or null). */
    public static Path findJavac() {
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        String exe = win ? "javac.exe" : "javac";
        List<Path> c = new ArrayList<>();
        String jh = System.getenv("JAVA_HOME");
        if (jh != null) c.add(Paths.get(jh, "bin", exe));
        String path = System.getenv("PATH");
        if (path != null) for (String p : path.split(File.pathSeparator)) if (!p.isBlank()) c.add(Paths.get(p, exe));
        List<Path> roots = win ? List.of(Paths.get("C:\\Program Files\\Java"), Paths.get("C:\\Program Files\\Eclipse Adoptium"), Paths.get("C:\\Program Files\\Microsoft"),
                Paths.get("C:\\Program Files\\Zulu"), Paths.get("C:\\Program Files\\Amazon Corretto")) : List.of(Paths.get("/usr/lib/jvm"), Paths.get("/opt"));
        for (Path r : roots) if (Files.isDirectory(r)) try (DirectoryStream<Path> ds = Files.newDirectoryStream(r)) { for (Path d : ds) c.add(d.resolve("bin").resolve(exe)); } catch (IOException ignored) {}
        for (Path p : c) if (Files.isRegularFile(p) && Files.isExecutable(p)) {
            try {
                Process pr = new ProcessBuilder(p.toString(), "-version").redirectErrorStream(true).start();
                String v = new String(pr.getInputStream().readAllBytes()).trim();
                pr.waitFor();
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("javac (\\d+)").matcher(v);
                if (m.find() && Integer.parseInt(m.group(1)) >= 17) return p;
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** In-process compiler if this runtime has the java.compiler module (the JDK the installers provide does). */
    static Object systemCompiler() {
        try { return Class.forName("javax.tools.ToolProvider").getMethod("getSystemJavaCompiler").invoke(null); } catch (Throwable t) { return null; }
    }

    static int runInProcess(Object jc, String[] args) throws Exception {
        java.lang.reflect.Method run = Class.forName("javax.tools.Tool").getMethod("run", InputStream.class, OutputStream.class, OutputStream.class, String[].class);
        return (Integer) run.invoke(jc, null, null, null, args);
    }

    public static boolean canBuild() { return systemCompiler() != null || findJavac() != null; }

    /** Downloads, compiles and packages JMBE. Returns the jar path. */
    public Path build() throws Exception {
        if (!canBuild()) throw new IOException("No Java compiler (JDK) found on this computer. Install one - Fedora: sudo dnf install java-latest-openjdk-devel; Windows: Eclipse Temurin JDK from adoptium.net - then try again." + " Or use 'Choose jar...' with a JMBE jar you already have.");
        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(20)).build();
        out.accept("Looking up the latest JMBE release on GitHub...");
        String json = http.send(HttpRequest.newBuilder(URI.create("https://api.github.com/repos/DSheirer/jmbe/releases/latest")).header("User-Agent", "TrunkDeck").build(),
                HttpResponse.BodyHandlers.ofString()).body();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        String tag = m.find() ? m.group(1) : "v2.0.2";
        String version = tag.replaceFirst("^v", "");
        Path work = Files.createTempDirectory("trunkdeck-jmbe");
        Path zip = work.resolve("jmbe.zip");
        out.accept("Downloading JMBE " + tag + " source code...");
        HttpResponse<Path> r = http.send(HttpRequest.newBuilder(URI.create("https://github.com/DSheirer/jmbe/archive/refs/tags/" + tag + ".zip")).header("User-Agent", "TrunkDeck").build(),
                HttpResponse.BodyHandlers.ofFile(zip));
        if (r.statusCode() != 200) throw new IOException("Download failed (HTTP " + r.statusCode() + ")");
        List<Path> sources = new ArrayList<>();
        Path src = work.resolve("src");
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null; ) {
                String n = e.getName();
                if (e.isDirectory() || !n.endsWith(".java")) continue;
                int i = n.indexOf("/src/main/java/");
                if (i < 0 || !(n.contains("/api/src/main/java/") || n.contains("/codec/src/main/java/"))) continue;
                Path dst = src.resolve(n.substring(i + "/src/main/java/".length())).normalize();
                if (!dst.startsWith(src)) continue;
                Files.createDirectories(dst.getParent());
                Files.copy(zin, dst, StandardCopyOption.REPLACE_EXISTING);
                sources.add(dst);
            }
        }
        out.accept("Extracted " + sources.size() + " source files");
        List<String> cp = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(engineLib, "*.jar")) {
            for (Path p : ds) { String n = p.getFileName().toString(); if (n.startsWith("JTransforms") || n.startsWith("JLargeArrays") || n.startsWith("slf4j-api") || n.startsWith("commons-math3")) cp.add(p.toString()); }
        }
        if (cp.size() < 4) throw new IOException("The libraries JMBE needs (JTransforms, JLargeArrays, slf4j-api, commons-math3) were not found in " + engineLib + ". Run the installer again.");
        Path classes = work.resolve("classes");
        Files.createDirectories(classes);
        Path list = work.resolve("sources.txt");
        Files.write(list, sources.stream().map(p -> "\"" + p.toString().replace("\\", "/") + "\"").toList());
        List<String> args = new ArrayList<>(List.of("--release", "17", "-nowarn", "-encoding", "UTF-8", "-cp", String.join(File.pathSeparator, cp), "-d", classes.toString(), "@" + list));
        out.accept("Compiling (this takes a few seconds)...");
        Object jc = systemCompiler();
        int rc;
        if (jc != null) rc = runInProcess(jc, args.toArray(new String[0]));
        else {
            Path javac = findJavac();
            if (javac == null) throw new IOException("No Java compiler found. Install a JDK (e.g. Fedora: sudo dnf install java-latest-openjdk-devel; Windows: Eclipse Temurin JDK), or use 'Choose jar...' with a JMBE jar you already have.");
            List<String> cmd = new ArrayList<>(); cmd.add(javac.toString()); cmd.addAll(args);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String o = new String(p.getInputStream().readAllBytes());
            rc = p.waitFor();
            if (rc != 0) out.accept(o);
        }
        if (rc != 0) throw new IOException("Compilation failed (exit " + rc + ")");
        Path dir = profile.resolve("jmbe");
        Files.createDirectories(dir);
        Path jar = dir.resolve("jmbe-" + version + ".jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        mf.getMainAttributes().putValue("Implementation-Title", "jmbe (built locally by TrunkDeck from " + tag + " source)");
        try (JarOutputStream jo = new JarOutputStream(Files.newOutputStream(jar), mf); var w = Files.walk(classes)) {
            for (Path p : (Iterable<Path>) w.filter(Files::isRegularFile)::iterator) {
                jo.putNextEntry(new JarEntry(classes.relativize(p).toString().replace('\\', '/')));
                Files.copy(p, jo);
                jo.closeEntry();
            }
        }
        try (var w = Files.walk(work)) { w.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete()); }
        out.accept("Created " + jar);
        if (!EngineLibs.loadJmbe(jar)) throw new IOException(EngineLibs.jmbeStatus());
        out.accept("Loaded: " + EngineLibs.jmbeStatus());
        return jar;
    }
}
