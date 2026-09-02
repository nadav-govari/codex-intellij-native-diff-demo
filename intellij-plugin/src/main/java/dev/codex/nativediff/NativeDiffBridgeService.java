package dev.codex.nativediff;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationInfo;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.vfs.VirtualFile;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.jetbrains.annotations.NotNull;

@Service(Service.Level.APP)
public final class NativeDiffBridgeService implements Disposable {
    private static final Logger LOG = Logger.getInstance(NativeDiffBridgeService.class);
    private static final int PROTOCOL_VERSION = 1;
    private static final Gson GSON = new Gson();
    private static final int MAX_PENDING_CONTEXTS = 20;
    private static final int MAX_SELECTED_TEXT_CHARS = 32 * 1024;
    private static final long CONTEXT_TTL_MILLIS = 10 * 60 * 1000L;
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> PRIVATE_PERMISSIONS = EnumSet.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

    private final AtomicBoolean started = new AtomicBoolean();
    private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "CodexNativeDiffBridge");
        thread.setDaemon(true);
        return thread;
    });
    private final ConcurrentHashMap<String, ReentrantLock> projectLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ArrayDeque<BridgeModels.EditorContextAttachment>>
            editorContexts = new ConcurrentHashMap<>();
    private final PatchReviewService reviewService = new PatchReviewService();
    private final String token = createToken();
    private final long pid = ProcessHandle.current().pid();

    private volatile ServerSocketChannel server;
    private volatile Path socketPath;
    private volatile Path discoveryPath;

    public void ensureStarted() {
        if (started.compareAndSet(false, true)) {
            executor.execute(this::serve);
        } else {
            publishDiscoveryQuietly();
        }
    }

    private void serve() {
        try {
            Path endpoints = endpointDirectory();
            Files.createDirectories(endpoints);
            setPermissions(endpoints, DIRECTORY_PERMISSIONS);
            cleanupStaleEndpoints(endpoints);

            socketPath = endpoints.getParent().resolve(pid + ".sock");
            discoveryPath = endpoints.resolve(pid + ".json");
            Files.deleteIfExists(socketPath);

            server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
            server.bind(UnixDomainSocketAddress.of(socketPath));
            setPermissions(socketPath, PRIVATE_PERMISSIONS);
            publishDiscovery();

            while (started.get()) {
                SocketChannel connection = server.accept();
                executor.execute(() -> handleConnection(connection));
            }
        } catch (IOException exception) {
            if (started.get()) {
                LOG.error("Could not start Codex native-diff bridge", exception);
            }
        } finally {
            deleteEndpointFiles();
        }
    }

    private void handleConnection(SocketChannel connection) {
        try (connection;
                BufferedReader reader = new BufferedReader(
                        Channels.newReader(connection, StandardCharsets.UTF_8));
                BufferedWriter writer = new BufferedWriter(
                        Channels.newWriter(connection, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null) {
                return;
            }
            JsonObject request = JsonParser.parseString(line).getAsJsonObject();
            JsonObject response = dispatch(request);
            writer.write(GSON.toJson(response));
            writer.newLine();
            writer.flush();
        } catch (Exception exception) {
            LOG.warn("Codex native-diff bridge request failed", exception);
        }
    }

    private JsonObject dispatch(JsonObject request) {
        String requestId = stringValue(request, "requestId");
        JsonObject response = new JsonObject();
        response.addProperty("version", PROTOCOL_VERSION);
        response.addProperty("requestId", requestId);
        try {
            if (request.get("version") == null
                    || request.get("version").getAsInt() != PROTOCOL_VERSION) {
                throw new IOException("Unsupported bridge protocol version");
            }
            if (!token.equals(stringValue(request, "token"))) {
                throw new IOException("Bridge authentication failed");
            }
            String method = stringValue(request, "method");
            JsonObject params = request.getAsJsonObject("params");
            if (params == null) {
                throw new IOException("Missing request parameters");
            }
            Object result = switch (method) {
                case "reviewPatch" -> reviewPatch(params);
                case "validatePatch" -> validatePatch(params);
                case "takeEditorContext" -> takeEditorContext(params);
                default -> throw new IOException("Unknown bridge method: " + method);
            };
            response.add("result", GSON.toJsonTree(result));
        } catch (Exception exception) {
            JsonObject error = new JsonObject();
            error.addProperty("message", safeMessage(exception));
            response.add("error", error);
        }
        return response;
    }

    private BridgeModels.ReviewResult reviewPatch(JsonObject params) throws Exception {
        Path requestedRoot = Path.of(stringValue(params, "projectRoot")).toRealPath();
        Project project = findProject(requestedRoot);
        String patch = stringValue(params, "patch");
        ReentrantLock lock = projectLocks.computeIfAbsent(requestedRoot.toString(), ignored -> new ReentrantLock());
        lock.lockInterruptibly();
        try {
            return reviewService.review(project, requestedRoot, patch);
        } finally {
            lock.unlock();
        }
    }

    private BridgeModels.ValidationResult validatePatch(JsonObject params) {
        return reviewService.validate(
                stringValue(params, "reviewToken"),
                stringValue(params, "projectRoot"),
                stringValue(params, "rewrittenPatch"));
    }

    BridgeModels.EditorContextAttachment queueEditorContext(
            Project project,
            VirtualFile file,
            Integer startLine,
            Integer endLine,
            String selectedText)
            throws IOException {
        ensureStarted();
        if (project.isDisposed() || project.getBasePath() == null) {
            throw new IOException("The IntelliJ project is not available");
        }
        Path root = Path.of(project.getBasePath()).toRealPath();
        Path filePath = Path.of(file.getPath()).toRealPath();
        if (!filePath.startsWith(root)) {
            throw new IOException("The active file is outside the IntelliJ project");
        }
        Path relativePath = root.relativize(filePath);
        if (relativePath.getNameCount() > 0
                && relativePath.getName(0).toString().equals(".git")) {
            throw new IOException("Files inside .git cannot be attached");
        }
        if ((startLine == null) != (endLine == null)
                || (startLine != null && (startLine < 1 || endLine < startLine))) {
            throw new IOException("The editor selection has an invalid line range");
        }

        boolean truncated = selectedText != null && selectedText.length() > MAX_SELECTED_TEXT_CHARS;
        String boundedText = truncated
                ? selectedText.substring(0, MAX_SELECTED_TEXT_CHARS)
                : selectedText;
        BridgeModels.EditorContextAttachment attachment = new BridgeModels.EditorContextAttachment(
                relativePath.toString().replace('\\', '/'),
                startLine,
                endLine,
                boundedText,
                truncated,
                System.currentTimeMillis());

        ArrayDeque<BridgeModels.EditorContextAttachment> queue =
                editorContexts.computeIfAbsent(root.toString(), ignored -> new ArrayDeque<>());
        synchronized (queue) {
            removeExpired(queue, System.currentTimeMillis());
            queue.removeIf(existing -> existing.path.equals(attachment.path)
                    && Objects.equals(existing.startLine, attachment.startLine)
                    && Objects.equals(existing.endLine, attachment.endLine));
            while (queue.size() >= MAX_PENDING_CONTEXTS) {
                queue.removeFirst();
            }
            queue.addLast(attachment);
        }
        return attachment;
    }

    private BridgeModels.EditorContextResult takeEditorContext(JsonObject params) throws IOException {
        Path requestedRoot = Path.of(stringValue(params, "projectRoot")).toRealPath();
        Project project = findProject(requestedRoot);
        if (project.getBasePath() == null) {
            return new BridgeModels.EditorContextResult(List.of());
        }
        Path projectRoot = Path.of(project.getBasePath()).toRealPath();
        ArrayDeque<BridgeModels.EditorContextAttachment> queue = editorContexts.get(projectRoot.toString());
        if (queue == null) {
            return new BridgeModels.EditorContextResult(List.of());
        }
        List<BridgeModels.EditorContextAttachment> attachments;
        synchronized (queue) {
            removeExpired(queue, System.currentTimeMillis());
            attachments = List.copyOf(queue);
            queue.clear();
        }
        editorContexts.remove(projectRoot.toString(), queue);
        return new BridgeModels.EditorContextResult(attachments);
    }

    private static void removeExpired(
            ArrayDeque<BridgeModels.EditorContextAttachment> queue, long now) {
        queue.removeIf(attachment -> now - attachment.attachedAtEpochMillis > CONTEXT_TTL_MILLIS);
    }

    private Project findProject(Path requestedRoot) throws IOException {
        List<Project> matches = new ArrayList<>();
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (project.isDisposed() || project.getBasePath() == null) {
                continue;
            }
            try {
                Path base = Path.of(project.getBasePath()).toRealPath();
                if (requestedRoot.startsWith(base)) {
                    matches.add(project);
                }
            } catch (IOException ignored) {
                // A closing or remotely backed project is not a local bridge target.
            }
        }
        return matches.stream()
                .max(Comparator.comparingInt(project -> project.getBasePath().length()))
                .orElseThrow(() -> new IOException(
                        "No open IntelliJ project contains " + requestedRoot));
    }

    private void publishDiscoveryQuietly() {
        try {
            publishDiscovery();
        } catch (IOException exception) {
            LOG.warn("Could not refresh Codex native-diff discovery record", exception);
        }
    }

    private synchronized void publishDiscovery() throws IOException {
        if (socketPath == null || discoveryPath == null || !Files.exists(socketPath)) {
            return;
        }
        JsonObject record = new JsonObject();
        record.addProperty("version", PROTOCOL_VERSION);
        record.addProperty("pid", pid);
        record.addProperty("socketPath", socketPath.toString());
        record.addProperty("token", token);
        record.addProperty("ideBuild", ApplicationInfo.getInstance().getBuild().asString());
        JsonElement roots = GSON.toJsonTree(openProjectRoots());
        record.add("projectRoots", roots);
        Path temporary = discoveryPath.resolveSibling(discoveryPath.getFileName() + ".tmp");
        Files.writeString(
                temporary,
                GSON.toJson(record),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        setPermissions(temporary, PRIVATE_PERMISSIONS);
        Files.move(
                temporary,
                discoveryPath,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        setPermissions(discoveryPath, PRIVATE_PERMISSIONS);
    }

    private List<String> openProjectRoots() {
        List<String> roots = new ArrayList<>();
        for (Project project : ProjectManager.getInstance().getOpenProjects()) {
            if (!project.isDisposed() && project.getBasePath() != null) {
                try {
                    roots.add(Path.of(project.getBasePath()).toRealPath().toString());
                } catch (IOException ignored) {
                    // Do not publish roots that cannot be canonicalized.
                }
            }
        }
        return roots;
    }

    private static Path endpointDirectory() {
        return Path.of(
                System.getProperty("user.home"),
                "Library",
                "Caches",
                "CodexNativeDiff",
                "v1",
                "endpoints");
    }

    private static void cleanupStaleEndpoints(Path endpoints) {
        try (var stream = Files.list(endpoints)) {
            stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> {
                        try {
                            JsonObject record = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
                            long recordPid = record.get("pid").getAsLong();
                            if (ProcessHandle.of(recordPid).isEmpty()) {
                                Files.deleteIfExists(path);
                                JsonElement socket = record.get("socketPath");
                                if (socket != null) {
                                    Files.deleteIfExists(Path.of(socket.getAsString()));
                                }
                            }
                        } catch (Exception ignored) {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException ignoredAgain) {
                                // Best effort only.
                            }
                        }
                    });
        } catch (IOException ignored) {
            // Best effort only.
        }
    }

    private static String stringValue(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Missing string field: " + name);
        }
        return value.getAsString();
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private static String createToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void setPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // V1 targets macOS, but leave a useful error path for unusual filesystems.
        }
    }

    private void deleteEndpointFiles() {
        try {
            if (discoveryPath != null) {
                Files.deleteIfExists(discoveryPath);
            }
            if (socketPath != null) {
                Files.deleteIfExists(socketPath);
            }
        } catch (IOException exception) {
            LOG.warn("Could not remove Codex native-diff endpoint", exception);
        }
    }

    @Override
    public void dispose() {
        started.set(false);
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
            // Closing is best effort.
        }
        executor.shutdownNow();
        deleteEndpointFiles();
    }
}
