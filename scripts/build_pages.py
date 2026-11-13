#!/usr/bin/env python3
"""Assemble a static landing page and Eclipse p2 site for GitHub Pages."""
from datetime import datetime, timezone
import json
from pathlib import Path
import shutil
import subprocess
import zipfile


def main():
    root = Path(__file__).resolve().parents[1]
    repository = root / "repository/target/repository"
    archive = root / "repository/target/doma-result-generator-repository-0.1.0-SNAPSHOT.zip"
    if not repository.is_dir() or not archive.is_file():
        raise SystemExit("Run mvn verify before assembling Pages.")
    if not (repository / "content.jar").is_file() or not (repository / "artifacts.jar").is_file():
        raise SystemExit("The p2 repository is missing its metadata.")
    plugins = list((repository / "plugins").glob("io.github.domaresult.eclipse_*.jar"))
    if len(plugins) != 1:
        raise SystemExit("Expected one Doma Result Generator plugin in the update site.")
    with zipfile.ZipFile(plugins[0]) as jar:
        manifest = jar.read("META-INF/MANIFEST.MF").decode("utf-8")
    version = next(line.partition(": ")[2] for line in manifest.splitlines() if line.startswith("Bundle-Version: "))
    output = root / "_site"
    if output.is_symlink():
        raise SystemExit("Refusing to replace a symlink at _site.")
    if output.exists():
        shutil.rmtree(output)
    shutil.copytree(repository, output)
    shutil.copytree(root / "site", output, dirs_exist_ok=True)
    (output / ".nojekyll").touch()
    downloads = output / "downloads"
    downloads.mkdir()
    shutil.copy2(archive, downloads / "doma-result-generator-update-site.zip")
    commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=root, capture_output=True, text=True)
    build = {
        "plugin_version": version,
        "commit": commit.stdout.strip() if commit.returncode == 0 else None,
        "built_at": datetime.now(timezone.utc).isoformat(),
    }
    (output / "build.json").write_text(json.dumps(build, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"GitHub Pages output: {output}")
    print(f"Update site plugin version: {version}")


if __name__ == "__main__":
    main()
