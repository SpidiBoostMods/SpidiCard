#!/usr/bin/env python3
"""SpidiBoost: package the tested release and its bounded updater manifest."""
import hashlib
import json
from pathlib import Path
import re
import zipfile

root = Path(__file__).resolve().parent.parent
version = re.search(r"^mod_version=(\d+\.\d+\.\d+)$", (root / "gradle.properties").read_text(), re.M).group(1)
output = root / "build/libs"
jar = output / f"SpidiCard-1.21.4-{version}.jar"
with zipfile.ZipFile(jar) as archive:
    metadata = json.loads(archive.read("fabric.mod.json"))
    assert metadata["id"] == "spidicard" and metadata["version"] == version
    assert metadata["name"] == "spidiboost.SpidiCard" and metadata["authors"] == ["SpidiBoost"]
    assert "spidicard-update-agent.jar" in archive.namelist()
    assert "assets/spidicard/icon.png" in archive.namelist()
    assert not any("verification/" in name or "runtimeProbe/" in name for name in archive.namelist())
digest = hashlib.sha256(jar.read_bytes()).hexdigest()
(output / "spidicard-update.properties").write_text(
    f"version={version}\nminecraft=1.21.4\nartifact={jar.name}\nsha256={digest}\n", encoding="utf-8")
source = output / f"SpidiCard-1.21.4-{version}-source-project.zip"
with zipfile.ZipFile(source, "w", zipfile.ZIP_DEFLATED) as archive:
    for name in ("src", "scripts", ".github", "gradle", "build.gradle", "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat", "README.md", ".gitignore", "docs"):
        path = root / name
        files = sorted(path.rglob("*")) if path.is_dir() else [path]
        for file in files:
            if file.is_file() and "__pycache__" not in file.parts:
                archive.write(file, f"SpidiCard-{version}/{file.relative_to(root).as_posix()}")
print(f"{jar.name}: SHA-256 {digest}")
print(f"Source: {source.name}")
