"""Two independent, identity-checked haptic sessions."""
import asyncio
from dataclasses import dataclass

from protocol import Lines, NAMES, SERVICE, normalize, parse
from transport import Ble, Usb

ERRORS = {
    "BUSY": "另一条通道正在控制振动",
    "DRV_NOT_READY": "DRV2605L 未就绪",
    "DRV_I2C": "DRV2605L I²C 操作失败",
}


@dataclass
class Pending:
    command: str
    future: asyncio.Future
    log: bool


class Session:
    def __init__(self, hand, emit):
        self.hand = hand
        self.emit = emit
        self.lifecycle = asyncio.Lock()
        self.writer = asyncio.Lock()
        self.counter = 0
        self.pending = {}
        self.transport = self.monitor = self.endpoint = None
        self.mode = None
        self.ready = False
        self.verified = False
        self.lines = Lines()
        self.last_rx = 0
        self.fault = None

    def event(self, name, **data):
        self.emit(event=name, hand=self.hand, **data)

    def receive(self, data, lines):
        if self.lines is not lines:
            return
        for line in lines.feed(data):
            if line is None:
                self.event("log", rx=self.mode, text="接收行过长，已丢弃")
                continue
            try:
                reply = parse(line)
            except ValueError as exc:
                self.event("log", rx=self.mode, text=f"{exc} | {line}")
                continue
            fields = reply.fields
            pending = self.pending.get(fields.get("id")) if fields["src"] == self.mode else None
            if reply.kind != "PONG" or (pending and pending.log):
                self.event("log", rx=self.mode, src=fields["src"], text=line)
            if fields["hand"] != self.hand or (reply.kind == "STATUS" and fields["protocol"] != "4"):
                if pending and not pending.future.done():
                    self.fault = ValueError(
                        f"预期 {NAMES[self.hand]} / 协议 4，收到手别 {fields['hand']} / "
                        f"协议 {fields.get('protocol', '未知')}"
                    )
                    pending.future.set_exception(self.fault)
                continue
            if pending and not pending.future.done() and reply.matches(pending.command):
                if reply.kind in {"PONG", "STATUS"}:
                    self.last_rx = asyncio.get_running_loop().time()
                pending.future.set_result(reply)
            if reply.kind == "STATUS" and self.ready:
                self.event("status", fields=fields)
            elif reply.kind == "I2CSCAN" and self.ready:
                self.event("i2c", fields=fields)
            elif reply.kind == "EVENT" and self.ready and fields["src"] == "SYSTEM" and reply.body in {
                ("BLE_LOST", "OFF"), ("TIMEOUT", "OFF")
            }:
                self.event("stopped")

    async def exchange(self, command, timeout=2, internal=False, log=True):
        if not internal and not self.ready:
            raise ConnectionError("设备尚未通过身份校验")
        transport = self.transport
        if transport is None:
            raise ConnectionError("设备未连接")
        loop = asyncio.get_running_loop()
        async with self.writer:
            if transport is not self.transport or not transport.connected or (not internal and not self.ready):
                raise ConnectionError("连接已关闭")
            self.counter = (self.counter + 1) % 65536
            while f"{self.counter:04X}" in self.pending:
                self.counter = (self.counter + 1) % 65536
            token = f"{self.counter:04X}"
            future = loop.create_future()
            self.pending[token] = Pending(command, future, log)
            try:
                if log:
                    self.event("log", tx=self.mode, text=f"@{token} {command}")
                await transport.write(f"@{token} {command}\n".encode("ascii"))
            except BaseException:
                self.pending.pop(token, None)
                if future.done() and not future.cancelled():
                    future.exception()
                future.cancel()
                raise
        try:
            reply = await asyncio.wait_for(future, timeout)
        except asyncio.TimeoutError:
            raise TimeoutError(f"板端未确认 {command}") from None
        finally:
            self.pending.pop(token, None)
        if reply.kind == "ERR":
            code = reply.body[0]
            raise RuntimeError(f"{ERRORS.get(code, '板端拒绝命令')} ({code})")
        return " ".join((reply.kind, *reply.body)), reply.fields

    async def connect(self, mode, endpoint, make_transport):
        async with self.lifecycle:
            if self.transport:
                raise ValueError("请先断开当前设备")
            self.mode, self.endpoint = mode, endpoint
            self.lines = lines = Lines()
            self.fault = None
            self.event("state", state="connecting", mode=mode, endpoint=endpoint)
            try:
                self.transport = make_transport(lambda data: self.receive(data, lines))
                await self.transport.open()
                self.event("state", state="verifying", mode=mode, endpoint=endpoint)
                self.monitor = asyncio.create_task(self.watch(self.transport, lines))
                try:
                    _, fields = await self.exchange("status", timeout=4, internal=True)
                except TimeoutError as exc:
                    raise TimeoutError("未收到有效 STATUS，请检查 V0.2 固件和连接通道") from exc
                self.ready = self.verified = True
                self.event("state", state="ready", mode=mode, endpoint=endpoint)
                self.event("status", fields=fields)
            except BaseException:
                await self.cleanup(stop=False)
                raise

    async def watch(self, transport, lines):
        loop = asyncio.get_running_loop()
        next_ping = next_status = loop.time()
        probes = {}
        reason = None
        try:
            while transport is self.transport:
                if not transport.connected:
                    raise ConnectionError("设备掉线")
                data = transport.read()
                if data:
                    self.receive(data, lines)
                now = loop.time()
                if self.ready:
                    if self.fault:
                        raise self.fault
                    if now - self.last_rx > 4:
                        raise TimeoutError("设备 4 秒未响应")
                    for name, due, interval in (("ping", next_ping, 0.7), ("status", next_status, 2)):
                        previous = probes.get(name)
                        if previous and previous.done():
                            probes.pop(name)
                            try:
                                previous.result()
                            except TimeoutError:
                                pass  # Both probes share the 4-second liveness check.
                        if now >= due and name not in probes:
                            probes[name] = asyncio.create_task(
                                self.exchange(name, timeout=0.65 if name == "ping" else 1.8,
                                              internal=True, log=False)
                            )
                            if name == "ping":
                                next_ping = now + interval
                            else:
                                next_status = now + interval
                await asyncio.sleep(0.03)
        except Exception as exc:
            reason = str(exc)
        finally:
            for task in probes.values():
                task.cancel()
            await asyncio.gather(*probes.values(), return_exceptions=True)
        if reason:
            self.event("log", rx=self.mode, text=f"通信中断：{reason}")
            asyncio.create_task(self.drop(transport, reason))

    async def drop(self, transport, reason):
        async with self.lifecycle:
            if self.transport is transport:
                await self.cleanup(stop=False, reason=reason)

    async def cleanup(self, stop=True, reason=None):
        verified = self.verified
        self.ready = False
        note = "已断开"
        confirmed = False
        if self.transport:
            self.event("state", state="closing", mode=self.mode, endpoint=self.endpoint)
        if stop and verified:
            try:
                await self.exchange("off", timeout=1, internal=True)
                note = "已确认停止并断开"
                confirmed = True
            except Exception as exc:
                note = f"已断开 · 停止未确认：{exc}"
                self.event("log", rx=self.mode, text=note)
        if self.monitor:
            self.monitor.cancel()
            await asyncio.gather(self.monitor, return_exceptions=True)
            self.monitor = None
        async with self.writer:
            transport, self.transport = self.transport, None
            self.lines = Lines()
            self.verified = False
            for pending in self.pending.values():
                if not pending.future.done():
                    pending.future.set_exception(ConnectionError("连接已关闭"))
            if transport:
                try:
                    await transport.close()
                except Exception as exc:
                    self.event("log", rx=self.mode, text=f"释放连接失败：{exc}")
        self.endpoint = self.mode = None
        self.event("state", state="offline", detail=reason or note)
        return (not verified or confirmed), note

    async def disconnect(self):
        async with self.lifecycle:
            return await self.cleanup()

    async def send(self, text):
        command = normalize(text)
        transport = self.transport
        try:
            return await self.exchange(command)
        except (TimeoutError, OSError) as exc:
            if command == "on" or command == "off" or command.startswith(("effect ", "rtp ")):
                # A command may have reached the board even when its confirmation was lost.
                async with self.lifecycle:
                    if self.transport is transport and self.ready:
                        await self.cleanup(stop=True, reason=f"{exc}，连接已关闭")
            raise


class Console:
    def __init__(self, emit, ble_factory=Ble, usb_factory=Usb):
        self.sessions = {hand: Session(hand, emit) for hand in NAMES}
        self.emit = emit
        self.ble_factory = ble_factory
        self.usb_factory = usb_factory
        self.devices = {}
        self.reserved = set()
        self.scan_lock = asyncio.Lock()

    async def scan(self, hand, mode):
        if mode == "USB":
            from serial.tools.list_ports import comports
            ports = await asyncio.to_thread(comports)
            rows = [{"id": p.device, "label": f"{p.device} | {p.description}"} for p in sorted(ports)]
        elif mode == "BLE":
            from bleak import BleakScanner
            async with self.scan_lock:
                found = await BleakScanner.discover(timeout=6, return_adv=True)
                rows = []
                for device, adv in found.values():
                    name = adv.local_name or device.name or "未命名"
                    address = device.address
                    self.devices[address] = device
                    target = SERVICE in {uuid.lower() for uuid in adv.service_uuids} or name in NAMES.values()
                    rows.append({"id": address, "label": f"{name} | {address} | {adv.rssi} dBm",
                                 "rank": 0 if name == NAMES[hand] else 1 if target else 2})
                rows.sort(key=lambda row: (row.pop("rank"), row["label"]))
        else:
            raise ValueError("通道必须为 BLE 或 USB")
        self.emit(event="devices", hand=hand, mode=mode, rows=rows)
        return f"发现 {len(rows)} 个设备"

    async def connect(self, hand, mode, endpoint):
        if not isinstance(endpoint, str) or not endpoint:
            raise ValueError("请选择设备")
        session = self.sessions[hand]
        if mode == "BLE":
            device = self.devices.get(endpoint)
            if device is None:
                raise ValueError("请先扫描并选择 BLE 设备")
            factory = lambda receive: self.ble_factory(device, receive)
        elif mode == "USB":
            if not endpoint.upper().startswith("COM") or not endpoint[3:].isdigit():
                raise ValueError("请选择有效的 COM 端口")
            factory = lambda _: self.usb_factory(endpoint)
        else:
            raise ValueError("通道必须为 BLE 或 USB")
        key = (mode, endpoint.upper())
        if key in self.reserved or any(
            other.mode == mode and (other.endpoint or "").upper() == key[1]
            for other in self.sessions.values()
        ):
            raise ValueError("该设备已分配")
        self.reserved.add(key)
        try:
            await session.connect(mode, endpoint, factory)
        finally:
            self.reserved.remove(key)
        return f"{mode} 已连接"

    async def result(self, hand, action):
        try:
            value = await action
            if isinstance(value, tuple) and isinstance(value[0], bool):
                ok, message = value
            else:
                ok, message = True, value[0] if isinstance(value, tuple) else value
        except Exception as exc:
            ok, message = False, str(exc) or type(exc).__name__
        return {"hand": hand, "ok": ok, "message": message}

    async def dispatch(self, request):
        action, hand = request.get("action"), request.get("hand")
        if action == "dual_start":
            values = request.get("strengths")
            if (not isinstance(values, dict) or
                    any(type(values.get(h)) is not int or not 1 <= values[h] <= 127
                        or not s.ready for h, s in self.sessions.items())):
                return [{"hand": h, "ok": False, "message": "双手启动需要两手在线且强度为 1–127"} for h in NAMES]
            return await asyncio.gather(*(self.result(h, s.send(f"rtp {values[h]}"))
                                          for h, s in self.sessions.items()))
        if action == "dual_stop":
            return await asyncio.gather(*(self.result(h, s.send("off")) if s.ready else
                                          self.unconfirmed(h) for h, s in self.sessions.items()))
        if action == "shutdown":
            return await asyncio.gather(*(self.result(h, s.disconnect()) for h, s in self.sessions.items()))
        if hand not in NAMES:
            raise ValueError("手别必须为 L 或 R")
        session = self.sessions[hand]
        if action == "scan":
            operation = self.scan(hand, request.get("mode"))
        elif action == "connect":
            operation = self.connect(hand, request.get("mode"), request.get("endpoint"))
        elif action == "disconnect":
            operation = session.disconnect()
        elif action == "send":
            operation = session.send(request.get("command"))
        else:
            raise ValueError("未知操作")
        return [await self.result(hand, operation)]

    async def unconfirmed(self, hand):
        return {"hand": hand, "ok": False, "message": "设备离线，停止未确认"}
