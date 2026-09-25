"""Assembles the landing demo frames recorded by record-landing-media.js into GIFs.

Usage: python frames-to-gif.py <frames-root> <output-dir> [width]
Requires Pillow (pip install pillow).
"""
import sys
from pathlib import Path

from PIL import Image

FRAME_MS = 110
FINAL_HOLD_MS = 1600


def build(clip_dir: Path, output: Path, width: int) -> None:
    frames = []
    previous = None
    durations = []
    for frame_path in sorted(clip_dir.glob("*.png")):
        with Image.open(frame_path) as source:
            frame = source.convert("RGB")
        height = round(frame.height * width / frame.width)
        frame = frame.resize((width, height), Image.LANCZOS)
        # Merge identical consecutive frames to keep the file small.
        if previous is not None and frame.tobytes() == previous.tobytes():
            durations[-1] += FRAME_MS
            continue
        frames.append(frame)
        durations.append(FRAME_MS)
        previous = frame
    if not frames:
        raise SystemExit(f"No frames in {clip_dir}")
    durations[-1] += FINAL_HOLD_MS
    palette_source = frames[len(frames) // 2].quantize(colors=128, method=Image.MEDIANCUT)
    quantized = [f.quantize(palette=palette_source, dither=Image.NONE) for f in frames]
    quantized[0].save(output, save_all=True, append_images=quantized[1:], duration=durations,
                      loop=0, optimize=True, disposal=1)
    print(f"{output.name}: {len(frames)} frames, {output.stat().st_size // 1024} KiB")


def main() -> None:
    frames_root, output_dir = Path(sys.argv[1]), Path(sys.argv[2])
    width = int(sys.argv[3]) if len(sys.argv) > 3 else 960
    output_dir.mkdir(parents=True, exist_ok=True)
    for clip_dir in sorted(p for p in frames_root.iterdir() if p.is_dir()):
        build(clip_dir, output_dir / f"{clip_dir.name}.gif", width)


if __name__ == "__main__":
    main()
