# 反馈控制台 V0.2

Windows 双手触觉反馈控制台。JavaFX 提供操作界面，Python 管理左右手的 BLE UART 或 USB CDC，ESP32-C3 通过 GPIO 软件 I²C 控制 DRV2605L。旧版 `MyESP32` 可用同级目录的原控制台操作。

## 1. 接线与烧录

两块 ESP32-C3 使用同样的接线和 ERM 马达配置。

| DRV2605L | ESP32-C3 |
| --- | --- |
| SDA | GPIO3，外部上拉 |
| SCL | GPIO4，外部上拉 |
| EN | GPIO2，ESP32 主动驱动 |
| GND | GND |

这套接线对应原控制台的实验硬件。GPIO2 属于启动相关引脚，若出现下载或启动异常，先核对实际板级电路。

1. 在 Arduino IDE 安装 Espressif ESP32 开发板包。左右草图各自带有同目录的 `HapticNode.h` 和 `HapticNode.cpp`，可直接打开编译。移动草图时复制整个草图文件夹。
2. 打开 `firmware/esp32c3_left/esp32c3_left.ino`，选择 **ESP32C3 Dev Module**、**USB CDC On Boot → Enabled** 和左手板的 USB 端口，上传后按 RESET。
3. 同样上传 `firmware/esp32c3_right/esp32c3_right.ino` 到右手板。建议在实物上标记左、右。两块板分别广播 `Haptic-Left`、`Haptic-Right`。

从项目根目录用 Arduino CLI 构建时，分别运行以下命令，Arduino 会编译各草图目录内的配套源码。

```powershell
arduino-cli compile --fqbn esp32:esp32:esp32c3:CDCOnBoot=cdc firmware/esp32c3_left
arduino-cli compile --fqbn esp32:esp32:esp32c3:CDCOnBoot=cdc firmware/esp32c3_right
```

修改共享固件源码后，运行 `python firmware/sync_sketches.py` 同步左右两套草图，再重新编译。

## 2. 启动与连接

- Windows 10/11，Python 3.10+，JDK 21，Maven 3.9+。将 `JAVA_HOME` 指向 JDK 21，Maven 的 `bin` 与 Python 加入 PATH。双击 `启动上位机.cmd`。首次启动联网安装 Maven 依赖，并在项目的 `.venv` 安装 Python 依赖。 若项目附带便携工具，脚本优先使用 `.tools/jdk-21` 和 `.tools/maven`，无需修改系统 PATH。
- **BLE**：给两板上电并打开电脑蓝牙，在左右卡片扫描，分别选择推荐的手别再连接。使用 BLE GATT UART，无需 Windows 预配对或蓝牙 COM 口。板子已连接手机时，先断开手机。适配器需要支持两条并行 BLE 连接。
- **USB**：插入支持数据的 USB-C 线，关闭串行监视器，在对应手的卡片选 USB 串口并刷新，选择该板的 COM。可同时连接两条 USB，或一手 BLE、一手 USB。接错手别时会显示身份不匹配。若使用载板，确认连接的是 SuperMini 下载和串口用 USB-C。
- **操作**：每手独立启动、停止、查询状态、检测 I²C、播放 ROM 效果，或发送文本命令。滑条修改目标强度，启动或应用时发送，弱／中／强预设会立即应用。顶部双手启动用各手的设定强度并发下发，一手失败时另一手可继续。双手停止会逐手报告确认结果。振动状态来自板端状态与驱动操作结果。
- **诊断**：日志可按左右手筛选，`RX` 是实际上行通道，`SRC` 是固件标记的触发来源，`TX` 是下行通道。连接失败时检查配套固件、手别、USB CDC 设置及端口占用。若驱动报告 ERR，先核对 EN、供电、共地、I²C 上拉及接线，再执行 I²C 检测。

## 3. 固件协议

USB 速率 115200。BLE 服务 `6e400001-b5a3-f393-e0a9-e50e24dcca9e`，RX 写入为 `6e400002-b5a3-f393-e0a9-e50e24dcca9e`，TX 通知为 `6e400003-b5a3-f393-e0a9-e50e24dcca9e`。手机可用 BLE GATT 工具订阅 TX 后向 RX 写文本，每次一条命令，最多 63 字节，末尾 CR/LF 可选。USB 命令以 CR 或 LF 结尾。回复使用 ASCII 文本，BLE 每个通知最多 20 字节，按 LF 重组。

| 命令 | 含义 |
| --- | --- |
| `on` / `1` | 以强度 100 持续振动 |
| `off` / `0` | 停止 |
| `rtp N` | 设置实时强度 0–127，0 停止 |
| `effect N` | 播放编号 0–123 的 ROM 效果 |
| `status` / `s` | 查询板端状态和协议版本 |
| `i2cscan` / `scan` | 检查 0x5A 和 0x5B |
| `ping` | 为当前控制通道续期 |
| `help` / `h` | 查看帮助 |

上位机发送如 `@00AF rtp 80`，成功回复 `OK RTP 80 hand=L id=00AF src=BLE`，随后返回带同一 ID 的 STATUS。手工发送 `status` 时回复带手别和来源。控制通道失去心跳约 3 秒后，板端停止振动，BLE 断开会停止由 BLE 开启的振动。另一通道可停止振动。退出上位机时会尝试发出停止并等待确认。

BLE 自定义 GATT 服务适用于近距离实验，连接时无需认证。

## 4. 上位机进程接口

Java 启动 Python 后端，双方以 UTF-8 JSON Lines 经标准输入输出通信。各请求带整数 `id`，每个结果包含 `hand`、`ok`、`message`。连接与命令分别等待手别校验与板端确认。事件使用 `event` 字段，按 `hand` 推送设备列表、连接状态、遥测、I²C 结果与日志。

```json
{"id":1,"action":"scan","hand":"L","mode":"BLE"}
{"id":2,"action":"connect","hand":"R","mode":"USB","endpoint":"COM5"}
{"id":3,"action":"send","hand":"R","command":"effect 14"}
{"id":4,"action":"dual_start","strengths":{"L":60,"R":90}}
{"id":5,"action":"dual_stop"}
{"id":6,"action":"shutdown"}
```

## 5. 自测与验证

```powershell
python -m unittest discover -s backend -v
mvn -f frontend/pom.xml clean package
```

实测顺序建议为分别验证左手、右手，再验证双 BLE、混合连接及双手启停。检查每手的 RTP、ROM、I²C 和日志。振动时断开该手 BLE，检查该手停机及另一手仍可控制。使用串行监视器发送 `on` 后停止发送心跳，确认约 3 秒停机。查看 [VALIDATION.md](VALIDATION.md) 获取本版执行结果。
