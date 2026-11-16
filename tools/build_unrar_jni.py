# -*- coding: utf-8 -*-
"""Сборка родной части RAR5 (UnRAR + JNI) для четырёх архитектур прямо компилятором NDK, без CMake.
Результат: app/src/main/jniLibs/<abi>/libunrar_jni.so (около 0,2 МБ на архитектуру). Запуск: python tools/build_unrar_jni.py"""
import os, subprocess, sys
from concurrent.futures import ThreadPoolExecutor
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
NDK = r"C:\Android\ndk\27.0.12077973\toolchains\llvm\prebuilt\windows-x86_64\bin"
SRC = os.path.join(ROOT, "app", "src", "main", "cpp")
U = os.path.join(SRC, "unrar")
NAMES = "rar strlist strfn pathfn smallfn global file filefn filcreat archive arcread unicode system crypt crc rawread encname resource match timefn rdwrfn consio options errhnd rarvm secpassword rijndael getbits sha1 sha256 blake2s hash extinfo extract volume list find unpack headers threadpool rs16 cmddata ui largepage filestr scantree dll qopen".split()
ABIS = {"arm64-v8a": "aarch64-none-linux-android26", "armeabi-v7a": "armv7a-none-linux-androideabi26", "x86": "i686-none-linux-android26", "x86_64": "x86_64-none-linux-android26"}
DEFS = ["-D_UNIX", "-DRARDLL", "-DSILENT", "-D_FILE_OFFSET_BITS=64", "-D_LARGEFILE_SOURCE"]
FLAGS = ["-Os", "-fPIC", "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections", "-w", "-std=c++14"]
def run(c):
    r = subprocess.run(c, capture_output=True, text=True)
    if r.returncode: print(" ".join(c[:3]), r.stderr[:2000]); sys.exit(1)
for abi, tgt in ABIS.items():
    out = os.path.join(ROOT, "build_unrar", abi); os.makedirs(out, exist_ok=True)
    srcs = [os.path.join(SRC, "unrar_jni.cpp")] + [os.path.join(U, n + ".cpp") for n in NAMES]
    objs = [os.path.join(out, os.path.basename(s)[:-4] + ".o") for s in srcs]
    def comp(i): run([os.path.join(NDK, "clang++.exe"), "--target=" + tgt, "-c", srcs[i], "-o", objs[i], "-I", SRC] + DEFS + FLAGS)
    with ThreadPoolExecutor(8) as ex: list(ex.map(comp, range(len(srcs))))
    dst = os.path.join(ROOT, "app", "src", "main", "jniLibs", abi); os.makedirs(dst, exist_ok=True)
    so = os.path.join(dst, "libunrar_jni.so")
    run([os.path.join(NDK, "clang++.exe"), "--target=" + tgt, "-shared", "-o", so] + objs + ["-static-libstdc++", "-llog", "-Wl,--gc-sections", "-Wl,--strip-all", "-Wl,--exclude-libs,ALL", "-Wl,-z,max-page-size=16384"])
    print(abi, os.path.getsize(so))
