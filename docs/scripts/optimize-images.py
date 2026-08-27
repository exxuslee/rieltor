from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]


def save_webp(source: Path, target: Path, width: int | None = None) -> None:
    with Image.open(source) as image:
        image.load()
        if width and image.width > width:
            height = round(image.height * width / image.width)
            image = image.resize((width, height), Image.Resampling.LANCZOS)
        target.parent.mkdir(parents=True, exist_ok=True)
        image.save(target, "WEBP", quality=82, method=6)


for source in (ROOT / "images").rglob("*.png"):
    save_webp(source, source.with_suffix(".webp"))
    if source.parent.name == "properties":
        save_webp(source, source.with_name(f"{source.stem}-768.webp"), 768)
    else:
        save_webp(source, source.with_name(f"{source.stem}-600.webp"), 600)

print("Optimized PNG assets to WebP; original files were preserved.")
