#!/usr/bin/env python3
"""
Convert YOLO .pt checkpoints to ONNX for SentinelAI and maintain custom-index.json.

Pipeline (run by .github/workflows/convert-pt.yml):
  1. Scan every .pt WITHOUT executing it (opcode scan of data.pkl). Refuse files with dangerous calls.
  2. Export to ONNX with the official Ultralytics exporter (or the original YOLOv5 repo for legacy files).
  3. Verify the ONNX with onnxruntime and read the class names / task / input size.
  4. Write out/custom-<slug>.onnx and merge the entry into out/custom-index.json.

The app reads custom-index.json from the "models" release and lists the converted models for download.
"""
import argparse
import datetime
import hashlib
import json
import os
import pickletools
import re
import subprocess
import sys
import traceback
import zipfile
from pathlib import Path

DANGER_MODULES = {
    "os", "posix", "nt", "subprocess", "sys", "socket", "shutil", "runpy", "importlib", "ctypes",
    "pty", "marshal", "pickle", "_pickle", "webbrowser", "urllib", "urllib2", "http", "requests",
    "asyncio", "threading", "multiprocessing", "code", "codeop", "platform", "tempfile", "commands",
    "timeit", "pip", "setuptools", "distutils", "ftplib", "smtplib", "telnetlib", "xmlrpc",
    "socketserver", "signal", "resource", "pdb", "trace", "profile", "cProfile",
}
DANGER_BUILTINS = {"eval", "exec", "compile", "__import__", "open", "input", "breakpoint"}
DANGER_PREFIX = (
    "torch.hub.", "torch.load", "torch.jit.load", "torch.utils.cpp_extension.",
    "operator.attrgetter", "operator.methodcaller", "types.CodeType", "types.FunctionType",
)
STRING_OPS = {"UNICODE", "BINUNICODE", "SHORT_BINUNICODE", "BINUNICODE8", "STRING", "BINSTRING", "SHORT_BINSTRING"}
MEMO_PUT = {"BINPUT", "LONG_BINPUT", "PUT", "MEMOIZE"}
MEMO_GET = {"BINGET", "LONG_BINGET", "GET"}


class Unsupported(Exception):
    pass


def is_dangerous(g):
    top = g.split(".", 1)[0]
    if top in ("builtins", "__builtin__"):
        return g.split(".", 1)[-1] in DANGER_BUILTINS
    if top in DANGER_MODULES:
        return True
    return g.startswith(DANGER_PREFIX)


def scan_globals(data):
    """Return every module.name referenced by GLOBAL / STACK_GLOBAL, reading opcodes only."""
    found = set()
    recent = []          # values pushed by the most recent ops (strings or None)
    memo = {}
    memo_index = 0
    for op, arg, _pos in pickletools.genops(data):
        n = op.name
        if n in ("GLOBAL", "INST"):
            found.add(str(arg).replace(" ", ".", 1))
        elif n == "STACK_GLOBAL":
            name = recent[-1] if len(recent) >= 1 else None
            mod = recent[-2] if len(recent) >= 2 else None
            found.add("%s.%s" % (mod, name))
        if n in MEMO_PUT:
            if n == "MEMOIZE":
                memo[memo_index] = recent[-1] if recent else None
                memo_index += 1
            else:
                memo[arg] = recent[-1] if recent else None
            continue
        if n in STRING_OPS:
            recent.append(arg if isinstance(arg, str) else None)
        elif n in MEMO_GET:
            recent.append(memo.get(arg))
        else:
            recent.append(None)
        if len(recent) > 8:
            del recent[:-8]
    return found


def inspect_pt(pt):
    """Return (kind, globals, risks). kind: ultralytics | yolov5 | torchscript | other."""
    if not zipfile.is_zipfile(pt):
        raise Unsupported("not a PyTorch zip checkpoint (legacy or corrupt file)")
    with zipfile.ZipFile(pt) as z:
        names = z.namelist()
        if any(n.endswith("constants.pkl") or "/code/__torch__" in n for n in names):
            raise Unsupported("TorchScript archive: cannot be exported to ONNX with Ultralytics; export from the original weights")
        pkl = next((n for n in names if n.endswith("/data.pkl") or n == "data.pkl"), None)
        if pkl is None:
            raise Unsupported("no data.pkl inside: not a torch.save checkpoint")
        data = z.read(pkl)
    globs = scan_globals(data)
    risks = sorted(g for g in globs if is_dangerous(g))
    if any(g.startswith("ultralytics.") for g in globs):
        kind = "ultralytics"
    elif any(g.startswith("models.yolo") or g.startswith("models.common") for g in globs):
        kind = "yolov5"
    else:
        kind = "other"
    return kind, globs, risks


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def slugify(stem):
    s = re.sub(r"[^a-z0-9]+", "-", stem.lower()).strip("-")[:40]
    return s or "model-" + hashlib.sha1(stem.encode("utf-8")).hexdigest()[:4]


def names_list(names):
    if isinstance(names, dict):
        return [str(names[k]) for k in sorted(names, key=lambda x: int(x))]
    return [str(x) for x in (names or [])]


def snap32(v):
    v = int(v)
    return max(160, min(1280, ((v + 31) // 32) * 32))


def resolve_imgsz(model, arg):
    if str(arg).lower() != "auto":
        return snap32(arg)
    cands = []
    ck = getattr(model, "ckpt", None)
    if isinstance(ck, dict):
        cands.append((ck.get("train_args") or {}).get("imgsz"))
    cands.append((getattr(model, "overrides", None) or {}).get("imgsz"))
    for c in cands:
        if isinstance(c, (list, tuple)) and c:
            return snap32(max(int(x) for x in c))
        if isinstance(c, (int, float)) and c > 0:
            return snap32(c)
    return 640


def parse_names_literal(raw):
    """names metadata is a python dict/list literal such as {0: 'person', 1: 'car'}."""
    if not raw:
        return []
    items = re.findall(r"""["']?(\d+)["']?\s*:\s*(?:'((?:\\.|[^'\\])*)'|"((?:\\.|[^"\\])*)")""", raw)
    if items:
        d = {int(i): (a or b) for i, a, b in items}
        return [d.get(k, "class%d" % k) for k in range(max(d) + 1)]
    quoted = re.findall(r"""'((?:\\.|[^'\\])*)'|"((?:\\.|[^"\\])*)\"""", raw)
    return [a or b for a, b in quoted]


def verify_onnx(path, imgsz):
    import numpy as np
    import onnxruntime as ort
    sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    inp = sess.get_inputs()[0]
    shape = inp.shape
    if len(shape) != 4:
        raise RuntimeError("unexpected input rank %s" % (shape,))
    h = shape[2] if isinstance(shape[2], int) else imgsz
    w = shape[3] if isinstance(shape[3], int) else imgsz
    out = sess.run(None, {inp.name: np.zeros((1, 3, h, w), dtype=np.float32)})[0]
    if out.ndim != 3:
        raise RuntimeError("output is not a detection tensor, shape=%s (classification models are not supported)" % (list(out.shape),))
    return [int(x) for x in out.shape], dict(sess.get_modelmeta().custom_metadata_map), int(h)


def convert_ultralytics(pt, imgsz_arg):
    from ultralytics import YOLO
    model = YOLO(str(pt))
    task = getattr(model, "task", "detect") or "detect"
    if task == "classify":
        raise Unsupported("classification models do not output boxes")
    imgsz = resolve_imgsz(model, imgsz_arg)
    names = names_list(getattr(model, "names", None))
    exported = model.export(format="onnx", imgsz=imgsz, simplify=True, dynamic=False, half=False, batch=1)
    return Path(str(exported)), task, names, imgsz


def ensure_yolov5_repo():
    repo = Path(os.environ.get("YOLOV5_DIR", "yolov5"))
    if not (repo / "export.py").exists():
        subprocess.run(["git", "clone", "--depth", "1", "https://github.com/ultralytics/yolov5", str(repo)], check=True)
        subprocess.run([sys.executable, "-m", "pip", "install", "-q", "-r", str(repo / "requirements.txt")], check=True)
    return repo


def convert_yolov5(pt, imgsz_arg):
    repo = ensure_yolov5_repo()
    imgsz = 640 if str(imgsz_arg).lower() == "auto" else snap32(imgsz_arg)
    subprocess.run(
        [sys.executable, "export.py", "--weights", str(pt.resolve()), "--include", "onnx",
         "--imgsz", str(imgsz), "--simplify", "--opset", "12"],
        cwd=str(repo), check=True,
    )
    onnx = pt.with_suffix(".onnx")
    if not onnx.exists():
        raise RuntimeError("yolov5 export produced no ONNX file")
    return onnx, "detect", [], imgsz


def convert_one(pt, out_dir, imgsz_arg):
    kind, _globs, risks = inspect_pt(pt)
    if risks:
        raise Unsupported("refused: dangerous calls inside the checkpoint: " + ", ".join(risks[:5]))
    if kind == "other":
        raise Unsupported("not an Ultralytics or YOLOv5 checkpoint (YOLOv7/YOLOX/YOLO-NAS: export to ONNX from the original repo)")
    if kind == "yolov5":
        onnx, task, names, imgsz = convert_yolov5(pt, imgsz_arg)
    else:
        onnx, task, names, imgsz = convert_ultralytics(pt, imgsz_arg)

    shape, meta, real_size = verify_onnx(onnx, imgsz)
    if not names:
        names = parse_names_literal(meta.get("names", ""))
    if not names:
        raise RuntimeError("could not read class names from the checkpoint or the ONNX metadata")
    end2end = len(shape) == 3 and shape[2] == 6 and 7 <= shape[1] <= 1000

    cid = "custom-" + slugify(pt.stem)
    target = Path(out_dir) / (cid + ".onnx")
    if target.exists():
        target.unlink()
    Path(onnx).replace(target)
    return {
        "id": cid,
        "title": pt.stem,
        "file": target.name,
        "size": target.stat().st_size,
        "sha256": sha256(target),
        "task": task,
        "imgsz": int(real_size),
        "nc": len(names),
        "names": names,
        "end2end": bool(end2end),
        "outputShape": shape,
        "src": pt.name,
        "srcSha256": sha256(pt),
        "converted": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }


def load_index(path):
    try:
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        items = data.get("models", [])
        return {m["id"]: m for m in items if isinstance(m, dict) and "id" in m}
    except Exception:
        return {}


def write_index(path, models):
    items = sorted(models.values(), key=lambda m: m.get("converted", ""), reverse=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump({"version": 1, "models": items}, f, ensure_ascii=False, indent=2)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default="inbox", help="folder with .pt files")
    ap.add_argument("--out", default="out", help="output folder (ONNX + custom-index.json)")
    ap.add_argument("--imgsz", default="auto", help="input size or 'auto' (read from the checkpoint)")
    ap.add_argument("--force", action="store_true", help="reconvert files that were already converted")
    args = ap.parse_args()

    src, out = Path(args.src), Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    index_path = out / "custom-index.json"
    models = load_index(index_path)
    files = sorted(src.glob("*.pt"))
    print("found %d .pt file(s) in %s" % (len(files), src))

    rows, failed = [], 0
    for pt in files:
        print("\n=== %s ===" % pt.name, flush=True)
        try:
            digest = sha256(pt)
            cid = "custom-" + slugify(pt.stem)
            old = models.get(cid)
            if old and old.get("srcSha256") == digest and not args.force:
                print("already converted (same checksum) -> skipped")
                rows.append((pt.name, "skipped", "already converted"))
                continue
            entry = convert_one(pt, out, args.imgsz)
            models[entry["id"]] = entry
            print("OK: %s | %s | %d classes | imgsz %d | out %s" % (entry["id"], entry["task"], entry["nc"], entry["imgsz"], entry["outputShape"]))
            rows.append((pt.name, "ok", "%s · %d classes · %d" % (entry["task"], entry["nc"], entry["imgsz"])))
        except Unsupported as e:
            failed += 1
            print("UNSUPPORTED: %s" % e)
            rows.append((pt.name, "failed", str(e)))
        except Exception as e:
            failed += 1
            traceback.print_exc()
            rows.append((pt.name, "failed", "%s: %s" % (type(e).__name__, str(e)[:200])))

    write_index(index_path, models)

    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as f:
            f.write("### Convert custom YOLO\n\n| file | result | details |\n|---|---|---|\n")
            for name, res, det in rows:
                f.write("| %s | %s | %s |\n" % (name, res, det.replace("|", "/")))
    print("\ndone: %d converted/skipped, %d failed" % (len(rows) - failed, failed))
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
