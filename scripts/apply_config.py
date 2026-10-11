#!/usr/bin/env python3
"""Apply the studio configuration to this Android project before building."""

import json
import os
import pathlib
import re
import urllib.request
import zipfile
from xml.sax.saxutils import escape

ROOT = pathlib.Path(__file__).resolve().parent.parent
APP = ROOT / "app"
RES = APP / "src" / "main" / "res"


def env(name, default=""):
    return os.environ.get(name, default) or default


def fetch(url, dest):
    if not url:
        return False
    dest.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(url, headers={"User-Agent": "mfstudio-build"})
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            data = response.read()
        if not data:
            print("empty response for " + url)
            return False
        dest.write_bytes(data)
        print("downloaded " + url + " -> " + str(dest))
        return True
    except Exception as error:
        print("could not download " + url + ": " + str(error))
        return False


def version_code(name):
    parts = re.findall(r"[0-9]+", name)
    parts = (parts + ["0", "0", "0"])[:3]
    return int("".join(p[:3] for p in parts)) or 1


package_id = re.sub(r"[^A-Za-z0-9_.]", "", env("PACKAGE_ID", "app.mfstudio.webview"))
if "." not in package_id:
    package_id = "app.mfstudio.webview"
app_name = env("APP_NAME", "MF Studio App")
version_name = env("VERSION_NAME", "1.0.0")
source_url = env("SOURCE_URL")
primary = env("PRIMARY", "#7C5CFF")
accent = env("ACCENT", "#F0B429")

# 1. The configuration file the app reads at runtime.
config = {
    "appName": app_name,
    "packageId": package_id,
    "versionName": version_name,
    "sourceUrl": source_url,
    "primaryColor": primary,
    "accentColor": accent,
    "iconUrl": env("ICON_URL"),
    "splashUrl": env("SPLASH_URL"),
}
assets = APP / "src" / "main" / "assets"
assets.mkdir(parents=True, exist_ok=True)
(assets / "studio.json").write_text(json.dumps(config, indent=2), encoding="utf-8")

# 2. Identity in Gradle.
gradle_path = APP / "build.gradle"
gradle = gradle_path.read_text(encoding="utf-8")
gradle = re.sub(r'applicationId\s+"[^"]*"', 'applicationId "%s"' % package_id, gradle)
gradle = re.sub(r'versionName\s+"[^"]*"', 'versionName "%s"' % version_name, gradle)
gradle = re.sub(r'versionCode\s+[0-9]+', "versionCode %d" % version_code(version_name), gradle)
gradle_path.write_text(gradle, encoding="utf-8")

# 3. Visible app name.
strings_path = RES / "values" / "strings.xml"
strings = strings_path.read_text(encoding="utf-8")
strings = re.sub(
    r'<string name="app_name">.*?</string>',
    '<string name="app_name">%s</string>' % escape(app_name),
    strings,
    flags=re.S,
)
strings_path.write_text(strings, encoding="utf-8")

# 4. Brand colors.
colors_path = RES / "values" / "colors.xml"
colors = colors_path.read_text(encoding="utf-8")
for token, value in (("brand_primary", primary), ("brand_accent", accent)):
    colors = re.sub(
        r'<color name="%s">.*?</color>' % token,
        '<color name="%s">%s</color>' % (token, value),
        colors,
    )
colors_path.write_text(colors, encoding="utf-8")

# 5. Owner-supplied icon replaces the built-in vector.
icon_ok = fetch(env("ICON_URL"), RES / "drawable" / "app_icon.png")
if icon_ok:
    fallback = RES / "drawable" / "app_icon.xml"
    if fallback.exists():
        fallback.unlink()

# 6. Owner-supplied splash becomes the window background.
splash_ok = fetch(env("SPLASH_URL"), RES / "drawable" / "splash.png")
if splash_ok:
    themes_path = RES / "values" / "themes.xml"
    themes = themes_path.read_text(encoding="utf-8")
    themes = themes.replace("@color/brand_background", "@drawable/splash")
    themes_path.write_text(themes, encoding="utf-8")

# 7. An uploaded site becomes the offline copy the app loads when no URL is set.
zip_url = env("SOURCE_ZIP")
if zip_url:
    archive = pathlib.Path("/tmp/source.zip")
    if fetch(zip_url, archive):
        target = assets / "www"
        target.mkdir(parents=True, exist_ok=True)
        try:
            with zipfile.ZipFile(archive) as zf:
                safe = [
                    n for n in zf.namelist()
                    if not n.startswith("/") and ".." not in pathlib.PurePosixPath(n).parts
                ]
                zf.extractall(target, members=safe)
                print("unpacked %d entries" % len(safe))
                # A single top-level folder is the usual case: lift it up.
                children = [c for c in target.iterdir() if not c.name.startswith("__")]
                if len(children) == 1 and children[0].is_dir() and not (target / "index.html").exists():
                    inner = children[0]
                    for item in list(inner.iterdir()):
                        item.rename(target / item.name)
                    inner.rmdir()
        except Exception as error:
            print("could not unpack archive: " + str(error))

print("configuration applied for " + app_name + " (" + package_id + ")")
