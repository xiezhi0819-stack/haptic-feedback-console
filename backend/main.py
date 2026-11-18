"""UTF-8 JSON Lines bridge for the JavaFX child-process client."""
import asyncio
import json
import sys
import threading

from service import Console


def emit(**message):
    sys.stdout.buffer.write((json.dumps(message, ensure_ascii=False) + "\n").encode("utf-8"))
    sys.stdout.buffer.flush()


async def main():
    loop = asyncio.get_running_loop()
    inbox = asyncio.Queue()
    console = Console(emit)
    tasks = set()

    def read_requests():
        try:
            for line in sys.stdin.buffer:
                try:
                    request = json.loads(line)
                    if not isinstance(request, dict) or type(request.get("id")) is not int:
                        raise ValueError("请求缺少整数 ID")
                except ValueError as exc:
                    print(exc, file=sys.stderr, flush=True)
                    continue
                loop.call_soon_threadsafe(inbox.put_nowait, request)
                if request.get("action") == "shutdown":
                    return
        finally:
            loop.call_soon_threadsafe(inbox.put_nowait, None)

    async def handle(request):
        try:
            emit(id=request["id"], results=await console.dispatch(request))
        except Exception as exc:
            emit(id=request["id"], error=str(exc) or type(exc).__name__)

    threading.Thread(target=read_requests, daemon=True).start()
    shutdown = None
    try:
        while (request := await inbox.get()) is not None:
            if request.get("action") == "shutdown":
                shutdown = request
                break
            task = asyncio.create_task(handle(request))
            tasks.add(task)
            task.add_done_callback(tasks.discard)
    finally:
        for task in list(tasks):
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        results = await console.dispatch({"action": "shutdown"})
        if shutdown is not None:
            emit(id=shutdown["id"], results=results)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except BrokenPipeError:
        pass
