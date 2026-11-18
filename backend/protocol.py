"""Line framing, command normalization, and protocol 4 decoding."""
import re
from dataclasses import dataclass

NAMES = {"L": "Haptic-Left", "R": "Haptic-Right"}
SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
ALIASES = {"1": "on", "0": "off", "s": "status", "h": "help",
           "scan": "i2cscan", "vibe": "effect"}
RESPONSES = {"status": "STATUS", "ping": "PONG", "i2cscan": "I2CSCAN", "help": "HELP"}
KINDS = {"READY", "STATUS", "OK", "ERR", "EVENT", "I2CSCAN", "PONG", "HELP"}


class Lines:
    def __init__(self, limit=4096):
        self.limit = limit
        self.buffer = bytearray()
        self.discard = False

    def feed(self, data):
        for byte in data:
            if byte == 10:
                if not self.discard and self.buffer:
                    yield bytes(self.buffer).removesuffix(b"\r").decode("ascii", errors="replace")
                self.buffer.clear()
                self.discard = False
            elif not self.discard:
                if len(self.buffer) == self.limit:
                    self.buffer.clear()
                    self.discard = True
                    yield None
                else:
                    self.buffer.append(byte)


def normalize(text):
    if not isinstance(text, str) or len(text) > 63 or not text.isascii() or "\r" in text or "\n" in text:
        raise ValueError("请输入一条 ASCII 命令")
    parts = text.lower().split()
    if parts:
        verb = ALIASES.get(parts[0], parts[0])
        if len(parts) == 1 and verb in {"on", "off", *RESPONSES}:
            return verb
        if len(parts) == 2 and verb in {"effect", "rtp"} and re.fullmatch(r"[0-9]+", parts[1]):
            value = int(parts[1])
            if value <= (123 if verb == "effect" else 127):
                return f"{verb} {value}"
    raise ValueError("支持命令 on / off / status / ping / help / i2cscan / effect 0–123 / rtp 0–127")


@dataclass
class Reply:
    kind: str
    body: tuple
    fields: dict

    def matches(self, command):
        if self.kind == "ERR":
            return True
        if command in RESPONSES:
            return self.kind == RESPONSES[command]
        expected = "OFF" if command == "rtp 0" else command.upper()
        return self.kind == "OK" and " ".join(self.body) == expected


def parse(line):
    if not line.strip() or not line.isascii() or not line.isprintable():
        raise ValueError("非 ASCII 文本帧")
    kind, *tokens = line.split()
    if kind not in KINDS:
        raise ValueError("未知回复类型")
    fields, body = {}, []
    for token in tokens:
        key, sep, value = token.partition("=")
        if not sep:
            body.append(token)
        elif not key or not value or key in fields:
            raise ValueError("无效或重复字段")
        else:
            fields[key] = value
    if fields.get("hand") not in NAMES or fields.get("src") not in {"BLE", "USB", "SYSTEM"}:
        raise ValueError("缺少手别或来源，请检查是否烧录 V0.2 固件")
    if "id" in fields and not re.fullmatch(r"[0-9A-F]{4}", fields["id"]):
        raise ValueError("无效请求 ID")
    if kind == "STATUS":
        required = {"motor": r"[01]", "protocol": r"[0-9]{1,3}", "ble": r"[01]",
                    "drv": r"OK|ERR", "addr": r"0x[0-9A-Fa-f]{1,2}", "sda": r"[0-9]{1,2}",
                    "scl": r"[0-9]{1,2}", "en": r"[01]", "mode": r"INIT|READY|STOP|RTP|EFFECT|ERROR",
                    "effect": r"[0-9]{1,3}", "rtp": r"[0-9]{1,3}", "drvstatus": r"0x[0-9A-Fa-f]{1,2}"}
        if body or any(not re.fullmatch(pattern, fields.get(key, "")) for key, pattern in required.items()):
            raise ValueError("STATUS 字段无效")
        if int(fields["effect"]) > 123 or int(fields["rtp"]) > 127:
            raise ValueError("STATUS 数值越界")
    elif kind == "PONG" and body:
        raise ValueError("PONG 格式无效")
    elif kind == "ERR" and (len(body) != 1 or not re.fullmatch(r"[A-Z][A-Z0-9_]*", body[0])):
        raise ValueError("ERR 格式无效")
    elif kind == "I2CSCAN" and (body or any(fields.get(key) not in {"ACK", "NO"} for key in ("0x5A", "0x5B"))):
        raise ValueError("I²C 回复格式无效")
    elif kind in {"OK", "READY", "EVENT", "HELP"} and not body:
        raise ValueError("回复正文缺失")
    return Reply(kind, tuple(body), fields)
