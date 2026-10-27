package dev.silentreview.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import java.util.regex.*;

/** Read-only Git operations. All arguments are passed directly, never through a shell. */
public final class GitRepository {
    private final Path root;
    private final BooleanSupplier canceled;
    public GitRepository(Path directory, BooleanSupplier canceled) throws IOException {
        this.canceled = canceled;
        this.root = Path.of(execute(directory, List.of("rev-parse", "--show-toplevel"), canceled).strip());
    }
    public Path root() { return root; }
    public String resolve(String revision) throws IOException {
        String value = run("rev-parse", "--verify", "--end-of-options", revision + "^{commit}").strip();
        if (!value.matches("[0-9a-f]{40,64}")) throw new IOException("无法解析提交编号");
        return value;
    }
    public String head() throws IOException { return resolve("HEAD"); }
    public String branch() throws IOException { return run("rev-parse", "--abbrev-ref", "HEAD").strip(); }
    public String parent(String target) throws IOException {
        String[] ancestors = run("rev-list", "--parents", "-n", "1", target).strip().split(" ");
        return ancestors.length > 1 ? ancestors[1] : run("hash-object", "-t", "tree", "--stdin").strip();
    }
    public String upstream() throws IOException { return resolve("@{upstream}"); }
    public String mergeBase(String reference, String target) throws IOException {
        return run("merge-base", resolve(reference), target).strip();
    }
    public ChangeSet collect(String target, String baseline) throws IOException {
        target = resolve(target);
        String base = baseline == null ? parent(target) : resolve(baseline);
        String[] parts = run("diff", "--name-status", "-z", "--no-ext-diff", "--find-renames", base, target).split("\\x00");
        List<ChangedFile> files = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        for (int i = 0; i < parts.length;) {
            String status = parts[i++];
            if (status.isEmpty()) continue;
            if (i >= parts.length) throw new IOException("无法解析 Git 文件清单");
            String beforePath = parts[i++], path = beforePath;
            if (status.startsWith("R") || status.startsWith("C")) {
                if (i >= parts.length) throw new IOException("无法解析 Git 重命名路径");
                path = parts[i++];
            }
            try {
                String before = status.startsWith("A") ? "" : run("show", base + ":" + beforePath);
                String after = status.startsWith("D") ? "" : run("show", target + ":" + path);
                if (before.indexOf(0) >= 0 || after.indexOf(0) >= 0) {
                    limitations.add("跳过二进制文件：" + path); continue;
                }
                if (before.length() + after.length() > 200_000) {
                    limitations.add("文件超出 200000 字符上下文上限：" + path); continue;
                }
                // Include both names for rename diffs, protecting path arguments with --.
                String diff = run("diff", "--no-ext-diff", "--no-textconv", "--no-color", "--unified=3", base, target, "--", beforePath, path);
                files.add(new ChangedFile(path, beforePath, status, before, after, diff, changedLines(diff, true), changedLines(diff, false)));
            } catch (IOException e) {
                limitations.add("文件读取未完成：" + path + "；" + e.getMessage());
            }
        }
        return new ChangeSet(root.toString(), branch(), base, target, List.copyOf(files), List.copyOf(limitations));
    }
    public String run(String... args) throws IOException { return execute(root, List.of(args), canceled); }

    static String execute(Path root, List<String> args, BooleanSupplier canceled) throws IOException {
        List<String> command = new ArrayList<>(List.of("git", "--literal-pathspecs", "-C", root.toString(), "-c", "core.quotepath=false"));
        command.addAll(args);
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
        Process process = builder.start();
        process.getOutputStream().close();
        CompletableFuture<byte[]> output = new CompletableFuture<>();
        Thread reader=new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] bytes = in.readNBytes(2_000_001);
                if (bytes.length > 2_000_000) { process.destroyForcibly(); throw new IOException("Git 输出超过大小上限"); }
                output.complete(bytes);
            } catch (Exception e) { output.completeExceptionally(e); }
        },"silent-review-git-output");
        reader.setDaemon(true);reader.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        try {
            while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                if (canceled.getAsBoolean()) throw new CancellationException("检查已取消");
                if (System.nanoTime() > deadline) throw new IOException("Git 读取超时");
            }
            if (canceled.getAsBoolean()) throw new CancellationException("检查已取消");
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output.get(5, TimeUnit.SECONDS))).toString();
            if (process.exitValue() != 0) throw new IOException("Git 无法读取指定范围：" + text.strip().substring(0, Math.min(text.strip().length(), 350)));
            return text;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new CancellationException("检查已中断");
        } catch (ExecutionException | TimeoutException e) { throw new IOException("无法读取 Git 输出", e);
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    public static Set<Integer> changedLines(String diff, boolean after) {
        Pattern header = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@.*");
        Set<Integer> lines = new HashSet<>();
        int oldLine = 0, newLine = 0;
        boolean inHunk = false;
        for (String line : diff.split("\\n", -1)) {
            Matcher match = header.matcher(line);
            if (match.matches()) { oldLine = Integer.parseInt(match.group(1)); newLine = Integer.parseInt(match.group(2)); inHunk = true; continue; }
            if (!inHunk || line.isEmpty() || line.startsWith("\\")) continue;
            if (line.startsWith("+")) { if (after) lines.add(newLine); newLine++; }
            else if (line.startsWith("-")) { if (!after) lines.add(oldLine); oldLine++; }
            else if (line.startsWith(" ")) { oldLine++; newLine++; }
        }
        return Set.copyOf(lines);
    }
    public record ChangedFile(String path, String beforePath, String status, String before, String after,
                              String diff, Set<Integer> addedLines, Set<Integer> deletedLines) {}
    public record ChangeSet(String root, String branch, String base, String target, List<ChangedFile> files, List<String> limitations) {}
}
