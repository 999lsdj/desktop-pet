"""下载或续传桌宠使用的 SDXL 补洞模型，完整 SHA-256 校验通过才算完成。"""

import argparse
from collections import deque
from concurrent.futures import ThreadPoolExecutor
import hashlib
from http.client import IncompleteRead
import json
from pathlib import Path
import time
import urllib.error
import urllib.request


NAME = "sd_xl_base_1.0_inpainting_0.1.safetensors"
URL = (
    "https://huggingface.co/wangqyqq/sd_xl_base_1.0_inpainting_0.1.safetensors/resolve/"
    "26a6b2ff3ea6d7762d33eb57b0ad424f96ac3e09/" + NAME
)
SIZE = 6938069944
SHA256 = "fe1b97fe6544814eb6fc8ce53f04ad8d339ec6946b58b0afd566fcc47813fa8a"


def request_range(start, end=None):
    # 独立查询参数避免代理把前一次 Range 响应误用于另一个区间。
    suffix = f"?range_start={start}&range_end={end if end is not None else 'end'}"
    request = urllib.request.Request(
        URL + suffix,
        headers={"Range": f"bytes={start}-{end if end is not None else ''}"},
    )
    response = urllib.request.urlopen(request, timeout=60)
    expected_end = SIZE - 1 if end is None else end
    expected = f"bytes {start}-{expected_end}/{SIZE}"
    if response.status != 206 or response.headers.get("Content-Range") != expected:
        response.close()
        raise ValueError(f"服务器未返回请求的数据区间：{expected}")
    return response


def verify_resume(path):
    size = path.stat().st_size
    if not size:
        return
    if size > SIZE:
        raise ValueError("本地文件大于完整模型，停止续传")
    with path.open("rb") as local:
        for start in sorted({0, max(0, size - 4096)}):
            end = min(start + 4095, size - 1)
            with request_range(start, end) as remote:
                data = remote.read()
            local.seek(start)
            if data != local.read(end - start + 1):
                raise ValueError("本地断点与远端模型不一致；保留原文件，停止续传")
    print("断点比对通过", flush=True)


def fetch_chunk(start, end):
    data = bytearray()
    expected = end - start + 1
    for attempt in range(5):
        try:
            with request_range(start + len(data), end) as response:
                while len(data) < expected:
                    block = response.read(min(1024 * 1024, expected - len(data)))
                    if not block:
                        raise OSError("数据段未传完")
                    data.extend(block)
            return bytes(data)
        except (OSError, urllib.error.URLError, IncompleteRead) as error:
            if isinstance(error, IncompleteRead):
                data.extend(error.partial)
            print(f"数据段 {start} 中断：{type(error).__name__}，已保留 {len(data)} 字节，尝试 {attempt + 1}/5", flush=True)
            if len(data) == expected:
                return bytes(data)
            if len(data) > expected:
                raise ValueError("返回的数据超过请求区间") from None
            if attempt == 4:
                raise RuntimeError("下载失败，现有断点已保留") from None
            time.sleep(3)


def download(path, workers):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and path.stat().st_size < SIZE:
        verify_resume(path)
    offset = path.stat().st_size if path.exists() else 0
    if offset > SIZE:
        raise ValueError("本地文件大小异常")
    started = time.monotonic()
    print(f"续传 {offset}/{SIZE} ({offset / SIZE:.2%})，{workers} 个连接", flush=True)
    chunk_size = 8 * 2**20
    ranges = iter((start, min(start + chunk_size, SIZE) - 1) for start in range(offset, SIZE, chunk_size))
    # 最多保留 workers 个 8 MiB 数据段；按顺序写回，任意中断仍留下连续断点。
    with ThreadPoolExecutor(max_workers=workers) as pool, path.open("ab") as target:
        pending = deque(pool.submit(fetch_chunk, *bounds) for bounds in [next(ranges, None) for _ in range(workers)] if bounds)
        while pending:
            target.write(pending.popleft().result())
            target.flush()
            current = target.tell()
            print(json.dumps({"bytes": current, "total": SIZE,
                "percent": round(current / SIZE * 100, 2),
                "MiB_per_second": round((current - offset) / (time.monotonic() - started) / 2**20, 2),
            }), flush=True)
            bounds = next(ranges, None)
            if bounds:
                pending.append(pool.submit(fetch_chunk, *bounds))
    if path.stat().st_size != SIZE:
        raise RuntimeError("下载未达到完整大小，断点已保留")
    print("下载完成，正在校验 SHA-256", flush=True)
    with path.open("rb") as model:
        digest = hashlib.file_digest(model, "sha256").hexdigest()
    if digest != SHA256:
        raise ValueError("SHA-256 不匹配，不能使用此模型")
    print(f"校验通过：{digest}", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--comfy-root", type=Path, default=Path("E:/AI/ComfyUI_windows_portable"))
    parser.add_argument("--workers", type=int, choices=range(1, 9), default=4)
    args = parser.parse_args()
    download(args.comfy_root / "ComfyUI/models/checkpoints" / NAME, args.workers)
