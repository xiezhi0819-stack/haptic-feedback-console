package haptic;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

final class Bridge {
    private static final Gson JSON = new Gson();
    private final Process process;
    private final BufferedWriter writer;
    private final ExecutorService sender = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("python-writer").factory());
    private final Consumer<JsonObject> message;
    private final Consumer<String> log;
    private final Runnable exited;

    Bridge(Path project, Consumer<JsonObject> message, Consumer<String> log, Runnable exited) throws IOException {
        this.message = message;
        this.log = log;
        this.exited = exited;
        Path python = project.resolve(".venv/Scripts/python.exe");
        process = new ProcessBuilder(python.toString(), "-u", "backend/main.py")
                .directory(project.toFile()).start();
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread.ofVirtual().name("python-stdout").start(this::readMessages);
        Thread.ofVirtual().name("python-stderr").start(this::readDiagnostics);
    }

    void send(JsonObject request) {
        try {
            sender.execute(() -> {
                try {
                    writer.write(JSON.toJson(request));
                    writer.newLine();
                    writer.flush();
                } catch (IOException e) {
                    Platform.runLater(() -> log.accept("发送失败 · " + e.getMessage()));
                    process.destroy();
                }
            });
        } catch (RejectedExecutionException ignored) {
            // The bridge has already closed.
        }
    }

    private void readMessages() {
        try (var reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    JsonObject object = JsonParser.parseString(line).getAsJsonObject();
                    Platform.runLater(() -> message.accept(object));
                } catch (RuntimeException e) {
                    Platform.runLater(() -> log.accept("后端消息格式错误 · " + e.getMessage()));
                }
            }
        } catch (IOException e) {
            Platform.runLater(() -> log.accept("后端管道中断 · " + e.getMessage()));
        } finally {
            sender.shutdownNow();
            Platform.runLater(exited);
        }
    }

    private void readDiagnostics() {
        try (var reader = new BufferedReader(new InputStreamReader(
                process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String text = line;
                Platform.runLater(() -> log.accept(text));
            }
        } catch (IOException ignored) {
            // Process termination closes stderr.
        }
    }

    void terminate() {
        sender.shutdownNow();
        if (process.isAlive()) {
            process.destroyForcibly();
        }
    }
}
