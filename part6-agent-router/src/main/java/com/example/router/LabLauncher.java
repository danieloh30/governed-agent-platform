package com.example.router;

import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Starts only this lab's processes; it never kills a process to reclaim a port. */
@ApplicationScoped
public class LabLauncher {
    @ConfigProperty(name = "lab.directory")
    String directory;

    @Inject
    RoutingChecks checks;

    private final List<OwnedProcess> processes = new CopyOnWriteArrayList<>();
    private Path runtime;
    private ScheduledExecutorService tracker;
    private boolean closed;

    public void run(String[] args) throws Exception {
        Path root = Path.of(directory).toAbsolutePath().normalize();
        Options options = Options.parse(root, args);
        Path binary = Path.of(System.getenv().getOrDefault("AIGW_BIN", root.resolve(".bin/aigw").toString()));
        if (!Files.isExecutable(binary)) {
            throw new IllegalStateException("Run ./install.sh first, or set AIGW_BIN to an executable v1.1.0 path");
        }
        if (!Files.isRegularFile(options.config())) {
            throw new IllegalArgumentException("Missing configuration: " + options.config());
        }
        Path jar = root.resolve("target/quarkus-app/quarkus-run.jar");
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException("Build the Quarkus application before launching this lab");
        }
        verifyVersion(binary);
        checkPorts();
        Path state = Files.createDirectories(root.resolve(".runtime"));
        // Short path avoids the macOS Unix-domain socket path length limit.
        runtime = Files.createTempDirectory(Path.of("/tmp"), "acme-ar-");
        Thread shutdown = new Thread(this::cleanup, "part6-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdown);
        tracker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "part6-process-tracker");
            thread.setDaemon(true);
            return thread;
        });
        tracker.scheduleWithFixedDelay(() -> processes.forEach(OwnedProcess::capture), 0, 100, TimeUnit.MILLISECONDS);
        try {
            String java = Path.of(System.getProperty("java.home"), "bin/java").toString();
            for (String backend : List.of("primary", "fallback")) {
                int port = backend.equals("primary") ? 18081 : 18082;
                start(List.of(java, "-Dquarkus.profile=prod", "-Dquarkus.http.host-enabled=true",
                        "-Dquarkus.http.host=127.0.0.1", "-Dquarkus.http.port=" + port,
                        "-Dmodel.backend-name=" + backend, "-jar", jar.toString(), "serve"),
                        Map.of(), state.resolve(backend + ".log"));
            }
            await("Quarkus backend readiness", Duration.ofSeconds(60),
                    () -> healthy("http://127.0.0.1:18081/q/health/ready")
                            && healthy("http://127.0.0.1:18082/q/health/ready"));
            start(List.of(binary.toString(), "run", options.config().toString()), Map.of(
                    "AIGW_CONFIG_HOME", state.resolve("config").toString(),
                    "AIGW_DATA_HOME", state.resolve("data").toString(),
                    "AIGW_STATE_HOME", state.resolve("state").toString(),
                    "AIGW_RUNTIME_DIR", runtime.toString(),
                    "AI_GATEWAY_TRACING_SEMCONV", "gen_ai",
                    "OTEL_INSTRUMENTATION_GENAI_CAPTURE_MESSAGE_CONTENT", "false"),
                    state.resolve("gateway.log"));
            System.out.println("Starting Agent Router v1.1.0; first start downloads Envoy 1.38.1.");
            System.out.println("Logs: " + state);
            await("Agent Router readiness", Duration.ofSeconds(180),
                    () -> healthy("http://127.0.0.1:1064/health") && listening(1975));
            System.out.println("Ready: http://localhost:1975/v1/chat/completions");
            System.out.println("Model Routing Console: http://localhost:18081/");
            System.out.println("Quarkus controls: http://localhost:18081/admin and :18082/admin");
            if (options.smoke()) {
                checks.run();
            } else {
                System.out.println("Use a second terminal for the tutorial. Ctrl+C stops only this lab.");
                while (true) {
                    requireAlive();
                    Thread.sleep(500);
                }
            }
        } finally {
            cleanup();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException ignored) {
                // JVM shutdown has already started; its hook performs the same cleanup.
            }
        }
    }

    static record Options(Path config, boolean smoke) {
        static Options parse(Path root, String... args) {
            Path config = root.resolve("config.yaml");
            boolean smoke = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--smoke" -> smoke = true;
                    case "--config" -> {
                        if (++i == args.length) {
                            throw new IllegalArgumentException("--config requires a YAML file");
                        }
                        config = Path.of(args[i]).toAbsolutePath().normalize();
                    }
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
            return new Options(config, smoke);
        }
    }

    private void verifyVersion(Path binary) throws Exception {
        Process version = new ProcessBuilder(binary.toString(), "version").redirectErrorStream(true).start();
        try {
            if (!version.waitFor(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("aigw version check timed out");
            }
            String output = new String(version.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (version.exitValue() != 0 || !output.equals("Envoy AI Gateway CLI: v1.1.0")) {
                throw new IllegalStateException("Expected aigw v1.1.0; got: " + output);
            }
        } finally {
            if (version.isAlive()) version.destroyForcibly();
        }
    }

    private void checkPorts() throws IOException {
        for (int port : new int[]{1975, 1064, 18081, 18082}) {
            try (ServerSocket socket = new ServerSocket()) {
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("127.0.0.1", port));
            } catch (IOException error) {
                throw new IOException("Cannot bind port " + port + ": " + error.getMessage()
                        + ". Stop its owner or check permissions.", error);
            }
        }
    }

    private void start(List<String> command, Map<String, String> environment, Path log) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().putAll(environment);
        processes.add(new OwnedProcess(builder.start(), log));
    }

    private void await(String stage, Duration timeout, BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            requireAlive();
            if (condition.getAsBoolean()) return;
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException(stage + " timed out; inspect .runtime/*.log");
            }
            Thread.sleep(250);
        }
    }

    private void requireAlive() {
        for (OwnedProcess owned : processes) {
            if (!owned.process.isAlive()) {
                throw new IllegalStateException("Lab process exited; inspect " + owned.log);
            }
        }
    }

    private boolean healthy(String url) {
        try (HealthClient client = QuarkusRestClientBuilder.newBuilder().baseUri(URI.create(url))
                .connectTimeout(1, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS)
                .build(HealthClient.class); Response response = client.get()) {
            return response.getStatus() == 200;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private boolean listening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1000);
            return true;
        } catch (IOException error) {
            return false;
        }
    }

    private synchronized void cleanup() {
        if (closed) return;
        closed = true;
        processes.forEach(OwnedProcess::capture);
        if (tracker != null) tracker.shutdownNow();
        for (OwnedProcess owned : processes.reversed()) owned.stop();
        if (runtime != null) {
            try (var paths = Files.walk(runtime)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (IOException error) {
                System.err.println("Could not remove lab socket directory " + runtime + ": " + error.getMessage());
            }
        }
        System.out.println("Part 6 services stopped.");
    }

    private static final class OwnedProcess {
        final Process process;
        final Path log;
        final Set<ProcessHandle> descendants = ConcurrentHashMap.newKeySet();

        OwnedProcess(Process process, Path log) {
            this.process = process;
            this.log = log;
            capture();
        }

        void capture() {
            process.descendants().forEach(descendants::add);
        }

        void stop() {
            capture();
            // Retain handles before signalling the parent so reparented Envoy children are still owned.
            List<ProcessHandle> handles = new ArrayList<>(descendants);
            handles.add(process.toHandle());
            handles.forEach(ProcessHandle::destroy);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            for (ProcessHandle handle : handles) {
                try {
                    handle.onExit().get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (Exception ignored) {
                    if (handle.isAlive()) handle.destroyForcibly();
                }
            }
        }
    }
}
