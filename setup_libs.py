import os
import glob
import zipfile
import shutil

libs_dir = os.path.abspath("StreamBravia-TV/libs")
os.makedirs(libs_dir, exist_ok=True)

gradle_cache = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")

# 1. Media3 AARs
media3_aars = glob.glob(os.path.join(gradle_cache, "androidx.media3/**/media3*.aar"), recursive=True)
for aar in media3_aars:
    name = os.path.basename(aar)[:-4] + ".jar"
    dest = os.path.join(libs_dir, name)
    try:
        with zipfile.ZipFile(aar) as z:
            if "classes.jar" in z.namelist():
                with open(dest, "wb") as f:
                    f.write(z.read("classes.jar"))
    except Exception as e:
        print(f"Error {aar}: {e}")

# 2. AndroidX Core AAR
core_aars = glob.glob(os.path.join(gradle_cache, "androidx.core/core/1.12.0/*.aar"), recursive=True)
if core_aars:
    with zipfile.ZipFile(core_aars[0]) as z:
        if "classes.jar" in z.namelist():
            with open(os.path.join(libs_dir, "androidx-core-1.12.0.jar"), "wb") as f:
                f.write(z.read("classes.jar"))

# 3. Guava & Annotation JARs
for pattern, target in [
    ("com.google.guava/guava/**/guava-*-android.jar", "guava-android.jar"),
    ("androidx.annotation/annotation-jvm/**/annotation-jvm-*.jar", "annotation.jar"),
]:
    jars = glob.glob(os.path.join(gradle_cache, pattern), recursive=True)
    if jars:
        shutil.copy2(jars[0], os.path.join(libs_dir, target))

print(f"Total jars in {libs_dir}: {len(os.listdir(libs_dir))}")
