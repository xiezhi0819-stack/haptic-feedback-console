"""BLE UART and USB CDC transports."""
import asyncio
from protocol import RX, TX


class Ble:
    def __init__(self, device, received):
        from bleak import BleakClient
        self.client = BleakClient(device, timeout=15)
        self.received = received

    async def open(self):
        await asyncio.wait_for(self.client.connect(), 15)
        if any(not self.client.services.get_characteristic(uuid) for uuid in (RX, TX)):
            raise ValueError("缺少 BLE UART 特征，请烧录配套固件")
        await asyncio.wait_for(self.client.start_notify(TX, self.notify), 3)

    def notify(self, _, data):
        self.received(bytes(data))

    @property
    def connected(self):
        return self.client.is_connected

    async def write(self, payload):
        try:
            await asyncio.wait_for(self.client.write_gatt_char(RX, payload, response=True), 2)
        except Exception as exc:
            raise ConnectionError(f"BLE 写入失败：{exc or type(exc).__name__}") from exc

    def read(self):
        return b""

    async def close(self):
        await asyncio.wait_for(self.client.disconnect(), 5)


class Usb:
    def __init__(self, port):
        self.name = port
        self.port = None

    async def open(self):
        from serial import Serial
        opening = asyncio.create_task(asyncio.to_thread(
            Serial, self.name, 115200, timeout=0, write_timeout=0.25))
        try:
            self.port = await asyncio.shield(opening)
        except asyncio.CancelledError:
            # If shutdown races with open(), release the handle as soon as it arrives.
            try:
                (await opening).close()
            finally:
                raise
        await asyncio.sleep(2)
        self.port.reset_input_buffer()

    @property
    def connected(self):
        return self.port is not None and self.port.is_open

    async def write(self, payload):
        if self.port.write(payload) != len(payload):
            raise OSError("串口写入不完整")

    def read(self):
        return self.port.read(min(self.port.in_waiting, 4096))

    async def close(self):
        if self.port is not None:
            self.port.close()
