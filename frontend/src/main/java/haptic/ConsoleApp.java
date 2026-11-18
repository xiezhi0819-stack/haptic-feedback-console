package haptic;

import com.google.gson.JsonObject;
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

public final class ConsoleApp extends Application {
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");
    private final ObservableList<LogRow> entries = FXCollections.observableArrayList();
    private final FilteredList<LogRow> visible = new FilteredList<>(entries);
    private final ListView<LogRow> log = new ListView<>(visible);
    private final Map<Integer, Consumer<JsonObject>> pending = new HashMap<>();
    private final Label summary = new Label("正在启动通信后端…");
    private final Button dualStart = new Button("双手启动");
    private final Button dualStop = new Button("双手停止");
    private HandPanel left;
    private HandPanel right;
    private Bridge bridge;
    private int requestId;
    private boolean closing;
    private int groupRequests;
    private boolean groupStopping;
    private Stage stage;

    @Override
    public void start(Stage primary) {
        stage = primary;
        left = new HandPanel("L", "左手");
        right = new HandPanel("R", "右手");
        var title = new Label("触觉反馈控制台");
        title.getStyleClass().add("title");
        var version = new Label("V0.2");
        version.getStyleClass().add("version");
        HBox heading = row(title, version);
        var strap = new Label("ESP32-C3  /  DRV2605L");
        strap.getStyleClass().add("meta");
        var header = new VBox(6, heading, strap);
        dualStart.getStyleClass().add("primary");
        dualStop.getStyleClass().add("stop");
        dualStart.setOnAction(e -> group("dual_start"));
        dualStop.setOnAction(e -> group("dual_stop"));
        HBox toolbar = row(header, spacer(), dualStart, dualStop);

        HBox devices = new HBox(16, left.root, right.root);
        HBox.setHgrow(left.root, Priority.ALWAYS);
        HBox.setHgrow(right.root, Priority.ALWAYS);

        var filter = new ComboBox<>(FXCollections.observableArrayList("全部", "左手", "右手"));
        filter.setId("log-filter");
        log.setId("communication-log");
        filter.getSelectionModel().selectFirst();
        filter.valueProperty().addListener((observable, previous, selected) -> {
            visible.setPredicate(item -> switch (selected) {
                case "左手" -> item.hand.equals("L");
                case "右手" -> item.hand.equals("R");
                case null, default -> true;
            });
            scrollLog();
        });
        var logTitle = new Label("通信日志");
        logTitle.getStyleClass().add("section");
        var copy = new Button("复制选中行");
        copy.disableProperty().bind(log.getSelectionModel().selectedItemProperty().isNull());
        copy.setOnAction(e -> {
            var content = new javafx.scene.input.ClipboardContent();
            content.putString(log.getSelectionModel().getSelectedItem().text);
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
        });
        log.setPlaceholder(new Label("暂无通信记录"));
        log.setCellFactory(view -> new ListCell<>() {
            @Override protected void updateItem(LogRow item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : item.text);
                getStyleClass().removeAll("log-left", "log-right");
                if (!empty && item != null && !item.hand.isEmpty()) {
                    getStyleClass().add(item.hand.equals("L") ? "log-left" : "log-right");
                }
            }
        });
        VBox logs = new VBox(10, row(logTitle, spacer(), filter, copy), log);
        logs.getStyleClass().add("log-panel");
        VBox.setVgrow(log, Priority.ALWAYS);
        VBox.setVgrow(logs, Priority.ALWAYS);
        summary.getStyleClass().add("summary");
        summary.setWrapText(true);

        VBox root = new VBox(16, toolbar, devices, logs, summary);
        root.setPadding(new Insets(22, 26, 18, 26));
        Scene scene = new Scene(root, 1200, 830);
        scene.getStylesheets().add(getClass().getResource("/console.css").toExternalForm());
        primary.setScene(scene);
        primary.setTitle("触觉反馈控制台 V0.2");
        primary.setMinWidth(1060);
        primary.setMinHeight(760);
        primary.setOnCloseRequest(event -> {
            event.consume();
            close();
        });
        primary.show();

        Path project = Path.of("").toAbsolutePath();
        if (!Files.isRegularFile(project.resolve("backend/main.py"))) {
            project = project.resolve("..").normalize();
        }
        try {
            bridge = new Bridge(project, this::receive, text -> append("", text), this::backendExited);
            summary.setText("选择 BLE 或 USB 串口并连接左右手设备。");
        } catch (IOException e) {
            summary.setText("Python 后端启动失败，请通过启动上位机.cmd运行。");
            append("", e.getMessage());
        }
        refresh();
    }

    private void request(JsonObject data, Consumer<JsonObject> completion) {
        data.addProperty("id", ++requestId);
        pending.put(requestId, completion);
        bridge.send(data);
    }

    private void receive(JsonObject message) {
        if (message.has("id")) {
            var callback = pending.remove(message.get("id").getAsInt());
            if (callback != null) callback.accept(message);
            return;
        }
        String hand = get(message, "hand", "");
        HandPanel panel = switch (hand) {
            case "L" -> left;
            case "R" -> right;
            default -> null;
        };
        if (panel == null) return;
        switch (get(message, "event", "")) {
            case "state" -> panel.state(message);
            case "status" -> panel.status(message.getAsJsonObject("fields"));
            case "i2c" -> {
                JsonObject fields = message.getAsJsonObject("fields");
                panel.i2c.setText("I²C · 0x5A " + get(fields, "0x5A", "—") +
                        "   0x5B " + get(fields, "0x5B", "—"));
            }
            case "stopped" -> panel.motor.setText("已停止");
            case "devices" -> panel.devices(message);
            case "log" -> {
                String rx = get(message, "rx", "");
                String tx = get(message, "tx", "");
                String src = get(message, "src", "");
                String tags = (rx.isEmpty() ? "" : "[RX/" + rx + "]") +
                        (tx.isEmpty() ? "" : "[TX/" + tx + "]") +
                        (src.isEmpty() ? "" : "[SRC/" + src + "]");
                append(hand, tags + " " + get(message, "text", ""));
            }
            default -> { }
        }
        refresh();
    }

    private void append(String hand, String text) {
        if (entries.size() >= 1500) entries.remove(0, 100);
        String tag = hand.equals("L") ? "  [左手]" : hand.equals("R") ? "  [右手]" : "";
        entries.add(new LogRow(hand, LocalTime.now().format(CLOCK) + tag + "  " + text.strip()));
        scrollLog();
    }

    private void scrollLog() {
        if (!visible.isEmpty()) log.scrollTo(visible.size() - 1);
    }

    private void group(String action) {
        JsonObject data = operation(action);
        if (action.equals("dual_start")) {
            JsonObject strengths = new JsonObject();
            strengths.addProperty("L", left.strength());
            strengths.addProperty("R", right.strength());
            data.add("strengths", strengths);
        }
        boolean stopRequest = action.equals("dual_stop");
        groupRequests++;
        if (stopRequest) groupStopping = true;
        refresh();
        request(data, response -> {
            groupRequests--;
            if (stopRequest) groupStopping = false;
            summary.setText(showResults(response, true));
            refresh();
        });
    }

    private String showResults(JsonObject response, boolean logResult) {
        if (response.has("error")) return get(response, "error", "操作失败");
        var result = new java.util.StringJoiner("  ｜  ");
        for (var item : response.getAsJsonArray("results")) {
            JsonObject row = item.getAsJsonObject();
            String hand = get(row, "hand", "");
            String label = hand.equals("L") ? "左手" : "右手";
            String text = (row.get("ok").getAsBoolean() ? "" : "未完成 · ") + get(row, "message", "");
            result.add(label + " " + text);
            if (logResult || !row.get("ok").getAsBoolean()) append(hand, text);
        }
        return result.toString();
    }

    private void refresh() {
        left.refresh();
        right.refresh();
        dualStart.setDisable(bridge == null || closing || groupRequests > 0 ||
                !left.ready || !right.ready || left.requests > 0 || right.requests > 0 ||
                left.strength() == 0 || right.strength() == 0);
        dualStop.setDisable(bridge == null || closing || groupStopping || !(left.ready || right.ready));
    }

    private void backendExited() {
        bridge = null;
        pending.clear();
        if (closing) {
            stage.hide();
            return;
        }
        left.offline("后端连接中断");
        right.offline("后端连接中断");
        summary.setText("通信后端已退出，设备状态未知。请重新启动控制台。");
        refresh();
    }

    private void close() {
        if (closing) return;
        closing = true;
        refresh();
        if (bridge == null) {
            stage.hide();
            return;
        }
        summary.setText("正在停止振动并释放设备连接…");
        request(operation("shutdown"), response -> append("", showResults(response, false)));
        PauseTransition deadline = new PauseTransition(Duration.seconds(12));
        deadline.setOnFinished(e -> {
            if (bridge != null) bridge.terminate();
            stage.hide();
        });
        deadline.play();
    }

    @Override public void stop() {
        if (bridge != null) bridge.terminate();
    }

    private static JsonObject operation(String action) {
        JsonObject data = new JsonObject();
        data.addProperty("action", action);
        return data;
    }

    private static String get(JsonObject value, String key, String fallback) {
        return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : fallback;
    }

    private static HBox row(Node... children) {
        HBox row = new HBox(9, children);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static Region spacer() {
        var region = new Region();
        HBox.setHgrow(region, Priority.ALWAYS);
        return region;
    }

    private record LogRow(String hand, String text) {}
    private record Device(String id, String label) {
        @Override public String toString() { return label; }
    }

    private final class HandPanel {
        final String hand;
        final VBox root = new VBox(11);
        final ComboBox<String> mode = new ComboBox<>(FXCollections.observableArrayList("BLE", "USB 串口"));
        final ComboBox<Device> devices = new ComboBox<>();
        final Label link = new Label("未连接");
        final Label motor = new Label("未知");
        final Label driver = new Label("—");
        final Label liveRtp = new Label("—");
        final Label i2c = new Label("I²C · 未检测");
        final Button scan = new Button("扫描 BLE");
        final Button connect = new Button("连接");
        final Button disconnect = new Button("断开");
        final Button start = new Button("启动振动");
        final Button stop = new Button("停止");
        final Button status = new Button("查询状态");
        final Button probe = new Button("I²C 检测");
        final Button play = new Button("播放效果");
        final Button apply = new Button("应用强度");
        final Button send = new Button("发送");
        final Button[] presets = {new Button("弱"), new Button("中"), new Button("强")};
        final Slider rtp = new Slider(0, 127, 100);
        final Spinner<Integer> effect = new Spinner<>(0, 123, 1);
        final TextField command = new TextField();
        boolean ready;
        int requests;
        boolean stopping;

        HandPanel(String hand, String title) {
            this.hand = hand;
            root.getStyleClass().addAll("device-panel", hand.equals("L") ? "left-panel" : "right-panel");
            root.setPrefWidth(560);
            root.setMinWidth(470);
            var label = new Label(title);
            label.getStyleClass().add("hand-title");
            link.getStyleClass().add("link-state");
            mode.getSelectionModel().selectFirst();
            devices.setPromptText("选择设备");
            devices.setMinWidth(100);
            devices.setPrefWidth(230);
            scan.setMinWidth(Region.USE_PREF_SIZE);
            devices.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(devices, Priority.ALWAYS);
            devices.valueProperty().addListener((obs, old, value) -> refresh());
            mode.setOnAction(e -> {
                devices.getItems().clear();
                scan.setText(mode().equals("BLE") ? "扫描 BLE" : "刷新串口");
                if (mode().equals("USB") && bridge != null) scan.fire();
            });
            scan.setOnAction(e -> requestHand("scan", data -> data.addProperty("mode", mode())));
            connect.setOnAction(e -> requestHand("connect", data -> {
                data.addProperty("mode", mode());
                data.addProperty("endpoint", devices.getValue().id());
            }));
            disconnect.setOnAction(e -> requestHand("disconnect", data -> {}));
            start.getStyleClass().add("primary");
            stop.getStyleClass().add("stop");
            start.setOnAction(e -> send("rtp " + strength()));
            stop.setOnAction(e -> send("off"));
            status.setOnAction(e -> send("status"));
            probe.setOnAction(e -> send("i2cscan"));
            play.setOnAction(e -> {
                String value = effect.getEditor().getText().strip();
                if (value.matches("[0-9]{1,3}") && Integer.parseInt(value) <= 123) {
                    effect.getValueFactory().setValue(Integer.parseInt(value));
                    send("effect " + value);
                } else {
                    summary.setText(title + " ROM 效果编号须为 0–123。");
                }
            });
            effect.setEditable(true);
            effect.setPrefWidth(76);
            apply.setOnAction(e -> send("rtp " + strength()));
            rtp.setBlockIncrement(1);
            rtp.valueProperty().addListener((obs, old, value) -> ConsoleApp.this.refresh());
            HBox.setHgrow(rtp, Priority.ALWAYS);
            var strength = new Label();
            strength.textProperty().bind(Bindings.createStringBinding(
                    () -> Integer.toString(strength()), rtp.valueProperty()));
            strength.getStyleClass().add("strength");
            strength.setMinWidth(30);
            int[] values = {60, 90, 127};
            for (int i = 0; i < presets.length; i++) {
                int value = values[i];
                presets[i].setOnAction(e -> {
                    rtp.setValue(value);
                    send("rtp " + value);
                });
            }
            command.setText("status");
            HBox.setHgrow(command, Priority.ALWAYS);
            command.setOnAction(e -> { if (!send.isDisabled()) send.fire(); });
            send.setOnAction(e -> send(command.getText()));
            i2c.getStyleClass().add("meta");

            HBox telemetry = row(metric("振动", motor), metric("驱动 / 模式", driver), metric("当前 RTP", liveRtp));
            root.getChildren().addAll(row(label, spacer(), link), row(mode, devices, scan),
                    row(connect, disconnect, spacer(), i2c), telemetry,
                    row(start, stop, spacer(), status, probe), new Separator(),
                    row(new Label("RTP 强度"), rtp, strength, apply),
                    row(presets[0], presets[1], presets[2], spacer(),
                            new Label("ROM 效果"), effect, play),
                    row(command, send));
        }

        VBox metric(String name, Label value) {
            var caption = new Label(name);
            caption.getStyleClass().add("meta");
            value.getStyleClass().add("metric-value");
            VBox metric = new VBox(4, caption, value);
            metric.getStyleClass().add("metric");
            metric.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(metric, Priority.ALWAYS);
            return metric;
        }

        String mode() { return mode.getSelectionModel().getSelectedIndex() == 0 ? "BLE" : "USB"; }

        int strength() { return (int) Math.round(rtp.getValue()); }

        void send(String text) { requestHand("send", data -> data.addProperty("command", text)); }

        void requestHand(String action, Consumer<JsonObject> parameters) {
            if (bridge == null || closing) return;
            JsonObject data = operation(action);
            data.addProperty("hand", hand);
            parameters.accept(data);
            boolean stopRequest = action.equals("send") && get(data, "command", "").equals("off");
            requests++;
            if (stopRequest) stopping = true;
            ConsoleApp.this.refresh();
            request(data, response -> {
                requests--;
                if (stopRequest) stopping = false;
                summary.setText(showResults(response, false));
                ConsoleApp.this.refresh();
            });
        }

        void devices(JsonObject message) {
            if (!mode().equals(get(message, "mode", ""))) return;
            devices.getItems().clear();
            for (var item : message.getAsJsonArray("rows")) {
                JsonObject row = item.getAsJsonObject();
                devices.getItems().add(new Device(get(row, "id", ""), get(row, "label", "")));
            }
            if (!devices.getItems().isEmpty()) devices.getSelectionModel().selectFirst();
        }

        void state(JsonObject message) {
            ready = false;
            switch (get(message, "state", "")) {
                case "ready" -> {
                    ready = true;
                    link.setText("已连接 · " + get(message, "mode", ""));
                    link.setTooltip(new Tooltip(get(message, "endpoint", "")));
                }
                case "connecting" -> link.setText("正在连接…");
                case "verifying" -> link.setText("正在验证手别…");
                case "closing" -> link.setText("正在断开…");
                default -> offline(get(message, "detail", "未连接"));
            }
        }

        void offline(String detail) {
            ready = false;
            link.setText("未连接");
            link.setTooltip(new Tooltip(detail));
            motor.setText("未知");
            driver.setText("—");
            liveRtp.setText("—");
            i2c.setText("I²C · 未检测");
        }

        void status(JsonObject fields) {
            motor.setText(get(fields, "motor", "0").equals("1") ? "振动中" : "已停止");
            driver.setText(get(fields, "drv", "—") + " / " + get(fields, "mode", "—"));
            driver.setTooltip(new Tooltip("地址 " + get(fields, "addr", "—") +
                    " · EN " + get(fields, "en", "—") + " · 状态 " + get(fields, "drvstatus", "—")));
            liveRtp.setText(get(fields, "rtp", "—"));
        }

        void refresh() {
            boolean available = bridge != null && !closing;
            boolean canConfigure = available && requests == 0 && !ready;
            mode.setDisable(!canConfigure);
            devices.setDisable(!canConfigure);
            scan.setDisable(!canConfigure);
            connect.setDisable(!canConfigure || devices.getValue() == null);
            disconnect.setDisable(!available || !ready || requests > 0 || groupRequests > 0);
            boolean control = available && ready && requests == 0 && groupRequests == 0;
            for (Button button : new Button[]{status, probe, play, apply, send}) button.setDisable(!control);
            for (Button button : presets) button.setDisable(!control);
            start.setDisable(!control || strength() == 0);
            stop.setDisable(!available || !ready || stopping);
        }
    }
}
