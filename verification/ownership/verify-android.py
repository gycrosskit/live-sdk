#!/usr/bin/env python3
"""Compile the production Android account path with controllable SDK/Main fixtures."""
import os
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
cache = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
def jar(group, artifact, version):
    matches = list((cache / group / artifact / version).glob(f"*/{artifact}-{version}.jar"))
    if len(matches) != 1:
        raise SystemExit(f"Expected one cached {group}:{artifact}:{version}; run Gradle dependency resolution first")
    return matches[0]
version = "2.2.21-1.0.0"
stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", version)
annotations = jar("org.jetbrains", "annotations", "13.0")
compiler = [jar("org.jetbrains.kotlin", name, version) for name in
            ("kotlin-compiler-embeddable", "kotlin-script-runtime", "kotlin-daemon-embeddable")]
compiler += [stdlib, annotations, jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
             jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0")]
output = root / "build/ownership/android"
output.mkdir(parents=True, exist_ok=True)
sources = [root / "live-core/src/androidMain/kotlin/io/github/gycrosskit/livesdk" / name for name in
           ("AtomicXSession.kt", "AtomicMainThread.kt")]
sources += [root / "live-core/src/commonMain/kotlin/io/github/gycrosskit/livesdk/LiveAccountOwnership.kt"]
sources += sorted((root / "verification/ownership/android").glob("*.kt"))
subprocess.run(["java", "-cp", os.pathsep.join(map(str, compiler)), "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                "-no-stdlib", "-no-reflect", "-nowarn", "-jvm-target", "11", "-classpath",
                os.pathsep.join(map(str, [stdlib, annotations])), "-d", str(output / "contract.jar"), *map(str, sources)], check=True)
result = subprocess.run(["java", "-cp", os.pathsep.join(map(str, [output / "contract.jar", stdlib])),
                         "io.github.gycrosskit.livesdk.MainKt", str(output / "results.xml")])
sys.exit(result.returncode)
