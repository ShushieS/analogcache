package com.riley.analogfix.cache;

import com.riley.analogfix.AnalogFix;
import com.riley.analogfix.AnalogFixConfig;
import com.riley.analogfix.AnalogFixConfig.AudioFormat;
import com.palm1.analoglib.client.audio.ClientAudioEngine;
import com.palm1.analoglib.client.audio.nativeaudio.NativeMediaBinaryManager;
import com.palm1.analoglib.client.audio.nativeaudio.pipeline.MediaDurationProber;
import com.palm1.analoglib.util.AudioFormatUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.loading.FMLPaths;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public final class AudioCache {
    private static final long DOWNLOAD_TIMEOUT_MINUTES = 30;
    private static final List<List<String>> CLIENT_ATTEMPTS = List.of(
            List.of(),
            List.of("--extractor-args", "youtube:player_client=mweb,android,web"));
    private static final String PENDING_DELETE = ".pending-delete";

    private static final Map<String, Path> INDEX = new ConcurrentHashMap<>();
    private static final Map<Path, Long> DURATIONS = new ConcurrentHashMap<>();
    private static final Map<String, CompletableFuture<Result>> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final Set<String> LIVESTREAMS = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final ExecutorService DOWNLOADER = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "AnalogFix-CacheDownloader-" + THREAD_ID.incrementAndGet());
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private static Path cacheDir;
    private static Path tmpDir;

    private AudioCache() {
    }

    public static void init() {
        cacheDir = FMLPaths.CONFIGDIR.get().resolve("analogfix").resolve("cache").toAbsolutePath().normalize();
        tmpDir = cacheDir.resolve(".tmp");
        try {
            Files.createDirectories(tmpDir);
            deleteContents(tmpDir);
            deletePending();
        } catch (IOException e) {
            AnalogFix.LOGGER.error("[AnalogFix] Could not create cache directory {}", cacheDir, e);
        }
        rescan();
        AnalogFix.LOGGER.info("[AnalogFix] Audio cache ready: {} tracks in {}", INDEX.size(), cacheDir);
    }

    public static Path directory() {
        return cacheDir;
    }

    public static boolean shouldCache(String url) {
        return cacheDir != null && url != null && !url.isBlank() && AnalogFixConfig.enabled()
                && !AudioFormatUtils.isDirectMediaUrl(url);
    }

    public static Result awaitFile(String url) {
        String key = UrlKeys.key(url);
        if (LIVESTREAMS.contains(key)) {
            return Result.LIVESTREAM;
        }
        Path cached = INDEX.get(key);
        if (cached != null && !Files.isRegularFile(cached)) {
            INDEX.remove(key);
            cached = null;
        }
        if (cached != null && matchesFormat(cached)) {
            touch(cached);
            AnalogFix.LOGGER.info("[AnalogFix] Playing {} from cache ({})", url, cached.getFileName());
            return new Result(cached, false);
        }

        Path existing = cached;
        CompletableFuture<Result> future = IN_FLIGHT.computeIfAbsent(key, k -> {
            if (existing == null) {
                notifyDownloading();
            }
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return existing != null ? convertExisting(existing, k) : download(url, k);
                } catch (Throwable t) {
                    AnalogFix.LOGGER.warn("[AnalogFix] Caching failed for {}", url, t);
                    return existing != null ? new Result(existing, false) : Result.FAILED;
                }
            }, DOWNLOADER);
        });
        future.whenComplete((r, e) -> IN_FLIGHT.remove(key, future));
        try {
            return future.get(DOWNLOAD_TIMEOUT_MINUTES + 1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.FAILED;
        } catch (ExecutionException | TimeoutException e) {
            return Result.FAILED;
        }
    }

    private static void notifyDownloading() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.translatable("message.analogfix.downloading")
                        .withStyle(ChatFormatting.GRAY), true);
            }
        });
    }

    private static Result download(String url, String key) throws IOException, InterruptedException {
        String ytDlp = NativeMediaBinaryManager.getYtDlpPath();
        if (ytDlp == null) {
            return Result.FAILED;
        }
        String ffmpeg = NativeMediaBinaryManager.getFfmpegPath();

        AnalogFix.LOGGER.info("[AnalogFix] Downloading {}", url);
        long started = System.currentTimeMillis();
        for (List<String> clientArgs : CLIENT_ATTEMPTS) {
            DownloadOutcome outcome = runYtDlp(ytDlp, ffmpeg, clientArgs, url, key);
            if (outcome.file() != null) {
                Path target = store(outcome.file(), key, ffmpeg);
                AnalogFix.LOGGER.info("[AnalogFix] Downloaded {} -> {} ({}) in {} ms", url, target.getFileName(),
                        formatSize(sizeOf(target)), System.currentTimeMillis() - started);
                evictToLimit(target);
                return new Result(target, false);
            }
            deleteTmpFor(key);
            if (outcome.exit() == 0) {
                LIVESTREAMS.add(key);
                AnalogFix.LOGGER.info("[AnalogFix] {} is a livestream; it will be streamed", url);
                return Result.LIVESTREAM;
            }
            AnalogFix.LOGGER.warn("[AnalogFix] yt-dlp {} exited with {} for {}: {}",
                    clientArgs.isEmpty() ? "(default clients)" : String.join(" ", clientArgs),
                    outcome.exit(), url, String.join(" | ", outcome.tail()));
        }
        return Result.FAILED;
    }

    private static DownloadOutcome runYtDlp(String ytDlp, String ffmpeg, List<String> clientArgs, String url, String key)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(ytDlp);
        if (ffmpeg != null) {
            cmd.add("--ffmpeg-location");
            cmd.add(ffmpeg);
        }
        cmd.addAll(clientArgs);
        cmd.add("-f");
        cmd.add("ba/b");
        cmd.add("--no-playlist");
        cmd.add("--no-check-certificates");
        cmd.add("--no-progress");
        cmd.add("--no-warnings");
        cmd.add("--no-mtime");
        cmd.add("--no-part");
        cmd.add("--match-filter");
        cmd.add("!is_live");
        cmd.add("-o");
        cmd.add(tmpDir.resolve(key + ".%(ext)s").toString());
        cmd.add("--print");
        cmd.add("after_move:filepath");
        cmd.add(url);

        Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        CompletableFuture.delayedExecutor(DOWNLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES).execute(() -> {
            if (process.isAlive()) {
                AnalogFix.LOGGER.warn("[AnalogFix] Download of {} timed out after {} minutes", url, DOWNLOAD_TIMEOUT_MINUTES);
                process.destroyForcibly();
            }
        });
        Path downloaded = null;
        Deque<String> tail = new ArrayDeque<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                Path candidate = asExistingFile(trimmed);
                if (candidate != null && candidate.startsWith(tmpDir)) {
                    downloaded = candidate;
                } else {
                    if (tail.size() == 10) {
                        tail.removeFirst();
                    }
                    tail.addLast(trimmed);
                }
            }
        }
        return new DownloadOutcome(downloaded, process.waitFor(), List.copyOf(tail));
    }

    private static Result convertExisting(Path existing, String key) throws IOException, InterruptedException {
        String ffmpeg = NativeMediaBinaryManager.getFfmpegPath();
        Path staged = tmpDir.resolve(existing.getFileName());
        Files.copy(existing, staged, StandardCopyOption.REPLACE_EXISTING);
        Path target = store(staged, key, ffmpeg);
        AnalogFix.LOGGER.info("[AnalogFix] Converted cached {} -> {} without re-downloading",
                existing.getFileName(), target.getFileName());
        return new Result(target, false);
    }

    private static Path store(Path source, String key, String ffmpeg) throws IOException, InterruptedException {
        Path finalSource = source;
        if (AnalogFixConfig.audioFormat() == AudioFormat.MP3 && !extension(source).equals("mp3")) {
            Path mp3 = tmpDir.resolve(key + ".mp3");
            if (ffmpeg != null && convertToMp3(ffmpeg, source, mp3)) {
                Files.deleteIfExists(source);
                finalSource = mp3;
            } else {
                AnalogFix.LOGGER.warn("[AnalogFix] mp3 conversion failed for {}; keeping {}", key, source.getFileName());
            }
        }

        Path target = cacheDir.resolve(finalSource.getFileName());
        moveReplacing(finalSource, target);
        touch(target);
        INDEX.put(key, target);
        deleteSiblings(key, target);
        return target;
    }

    private static boolean convertToMp3(String ffmpeg, Path source, Path target) throws IOException, InterruptedException {
        Path partial = tmpDir.resolve(target.getFileName() + ".part");
        Process process = new ProcessBuilder(ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                "-i", source.toString(), "-vn", "-map", "0:a:0", "-c:a", "libmp3lame", "-q:a", "2",
                "-f", "mp3", partial.toString())
                .redirectErrorStream(true)
                .start();
        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = String.join(" | ", reader.lines().toList());
        }
        if (!process.waitFor(10, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            Files.deleteIfExists(partial);
            return false;
        }
        if (process.exitValue() != 0 || !Files.isRegularFile(partial) || sizeOf(partial) == 0) {
            AnalogFix.LOGGER.warn("[AnalogFix] ffmpeg mp3 conversion exited with {}: {}", process.exitValue(), output);
            Files.deleteIfExists(partial);
            return false;
        }
        moveReplacing(partial, target);
        return true;
    }

    private static boolean matchesFormat(Path file) {
        return AnalogFixConfig.audioFormat() != AudioFormat.MP3 || extension(file).equals("mp3");
    }

    private static synchronized void evictToLimit(Path keep) {
        long limit = AnalogFixConfig.maxCacheBytes();
        List<Path> files = listCacheFiles();
        long total = files.stream().mapToLong(AudioCache::sizeOf).sum();
        if (total <= limit) {
            return;
        }
        files.sort(Comparator.comparing(AudioCache::lastModified));
        for (Path file : files) {
            if (total <= limit) {
                break;
            }
            if (file.equals(keep)) {
                continue;
            }
            long size = sizeOf(file);
            try {
                Files.deleteIfExists(file);
                total -= size;
                INDEX.values().remove(file);
                AnalogFix.LOGGER.info("[AnalogFix] Evicted {} ({})", file.getFileName(), formatSize(size));
            } catch (IOException e) {
            }
        }
    }

    public static CompletableFuture<ClearResult> clear() {
        ClientAudioEngine.stopAll("ANALOGFIX_CLEAR_CACHE", false);
        return CompletableFuture.supplyAsync(AudioCache::deleteAllWithRetry, DOWNLOADER);
    }

    private static synchronized ClearResult deleteAllWithRetry() {
        int deleted = 0;
        long freed = 0;
        List<Path> remaining = listCacheFiles();
        for (int attempt = 0; attempt < 10 && !remaining.isEmpty(); attempt++) {
            if (attempt > 0) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            List<Path> locked = new ArrayList<>();
            for (Path file : remaining) {
                long size = sizeOf(file);
                try {
                    Files.deleteIfExists(file);
                    deleted++;
                    freed += size;
                } catch (IOException e) {
                    locked.add(file);
                }
            }
            remaining = locked;
        }
        if (!remaining.isEmpty()) {
            markPending(remaining);
        }
        LIVESTREAMS.clear();
        rescan();
        AnalogFix.LOGGER.info("[AnalogFix] Cache cleared: {} files deleted ({}), {} still locked (deleted on next launch)",
                deleted, formatSize(freed), remaining.size());
        return new ClearResult(deleted, remaining.size(), freed);
    }

    public static Stats stats() {
        List<Path> files = listCacheFiles();
        return new Stats(files.size(), files.stream().mapToLong(AudioCache::sizeOf).sum());
    }

    private static void rescan() {
        INDEX.clear();
        DURATIONS.clear();
        Map<String, Path> best = new HashMap<>();
        for (Path file : listCacheFiles()) {
            String name = file.getFileName().toString();
            int dot = name.indexOf('.');
            if (dot <= 0) {
                continue;
            }
            best.merge(name.substring(0, dot), file, (a, b) -> matchesFormat(a) ? a : b);
        }
        INDEX.putAll(best);
    }

    private static List<Path> listCacheFiles() {
        List<Path> files = new ArrayList<>();
        if (cacheDir == null || !Files.isDirectory(cacheDir)) {
            return files;
        }
        try (Stream<Path> stream = Files.list(cacheDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .forEach(files::add);
        } catch (IOException e) {
            AnalogFix.LOGGER.warn("[AnalogFix] Could not list cache directory", e);
        }
        return files;
    }

    private static void deleteSiblings(String key, Path keep) {
        List<Path> locked = new ArrayList<>();
        for (Path file : listCacheFiles()) {
            if (!file.equals(keep) && file.getFileName().toString().startsWith(key + ".")) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    locked.add(file);
                }
            }
        }
        if (!locked.isEmpty()) {
            markPending(locked);
        }
    }

    private static synchronized void markPending(List<Path> files) {
        try {
            Files.write(cacheDir.resolve(PENDING_DELETE),
                    files.stream().map(p -> p.getFileName().toString()).toList(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            AnalogFix.LOGGER.warn("[AnalogFix] Could not record files to delete later", e);
        }
    }

    private static void deletePending() throws IOException {
        Path pending = cacheDir.resolve(PENDING_DELETE);
        if (!Files.isRegularFile(pending)) {
            return;
        }
        for (String name : Files.readAllLines(pending, StandardCharsets.UTF_8)) {
            if (!name.isBlank() && !name.contains("/") && !name.contains("\\")) {
                try {
                    Files.deleteIfExists(cacheDir.resolve(name.trim()));
                } catch (IOException ignored) {
                }
            }
        }
        Files.deleteIfExists(pending);
    }

    private static void deleteTmpFor(String key) {
        try (Stream<Path> stream = Files.list(tmpDir)) {
            stream.filter(p -> p.getFileName().toString().startsWith(key)).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    private static void deleteContents(Path dir) throws IOException {
        try (Stream<Path> stream = Files.list(dir)) {
            stream.forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path asExistingFile(String line) {
        try {
            Path p = Path.of(line).toAbsolutePath().normalize();
            return Files.isRegularFile(p) ? p : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    public static long durationMs(Path file) {
        return DURATIONS.computeIfAbsent(file, f -> MediaDurationProber.probeDurationWithFfmpeg(f.toString()));
    }

    public static String toFileUrl(Path file) {
        return "file:///" + file.toAbsolutePath().toString().replace('\\', '/');
    }

    private static void touch(Path file) {
        try {
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()));
        } catch (IOException ignored) {
        }
    }

    private static long sizeOf(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    private static FileTime lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException e) {
            return FileTime.fromMillis(0);
        }
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private record DownloadOutcome(Path file, int exit, List<String> tail) {
    }

    public record Result(Path file, boolean isLivestream) {
        static final Result FAILED = new Result(null, false);
        static final Result LIVESTREAM = new Result(null, true);
    }

    public record Stats(int files, long bytes) {
    }

    public record ClearResult(int deleted, int locked, long freedBytes) {
    }
}
