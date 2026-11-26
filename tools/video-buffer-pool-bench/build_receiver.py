"""Build a stock receiver and an allocator-only variant against the same common-c checkout."""
import os
from pathlib import Path
import shutil
import subprocess
import sys

src = Path(__file__).resolve().parent
root = src.parents[1]
out = Path(sys.argv[1]).resolve()
out.mkdir(parents=True, exist_ok=True)
replay = "--replay" in sys.argv[2:]
bridge = root/"app/src/main/jni/moonlight-core"
common = bridge/"moonlight-common-c"
c = common/"src"
rs = common/"nanors"
sdk = Path(os.environ["LOCALAPPDATA"])/"Android/Sdk"
cc = sdk/"ndk/28.2.13676358/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android30-clang.cmd"
tool = out/"toolchain/rustup.exe"
rustc = os.environ.get("RUSTC") or shutil.which("rustc")
env = os.environ.copy()
if not rustc:
    env["RUSTUP_HOME"] = str(out/"toolchain/rustup-home")
    env["CARGO_HOME"] = str(out/"toolchain/cargo-home")
    rust = [str(tool), "run", "stable", "rustc"]
else:
    rust = [rustc]
subprocess.run([*rust, "--edition=2021", "--target", "aarch64-linux-android", "--crate-type", "staticlib",
                "-C", "opt-level=3", "-C", "panic=abort", str(src/"packet_pool.rs"), "-o", str(out/"libpacket_pool.a")], check=True, env=env)

# Keep the C build byte-for-byte stock. In the experimental build change only
# packet allocations/releases, including rejected, recovered and queued frames.
patch = {
    "VideoStream.c": [("malloc(bufferSize)", "take_packet(bufferSize)", 1), ("free(buffer);", "release_packet(buffer);", 1)],
    "RtpVideoQueue.c": [("malloc(packetBufferSize)", "take_packet(packetBufferSize)", 1),
                        *[(f"free({expr});", f"release_packet({expr});", n) for expr,n in
                          [("entry->packet",1), ("block->shards[i]->packet",1), ("packets[i]",3),
                           ("parityEntry->packet",1), ("packet",1)]]],
    "VideoDepacketizer.c": [("free(lastEntry->allocPtr);", "release_packet(lastEntry->allocPtr);", 2),
                            ("free(existingEntry->allocPtr);", "release_packet(existingEntry->allocPtr);", 1)],
}
shared = [c/(n+".c") for n in ("Connection", "Platform", "PlatformCrypto", "PlatformSockets", "EndpointRefresh", "EndpointRefreshProtocol",
          "Misc", "ByteBuffer", "LinkedBlockingQueue", "VideoDiagnostics", "PyrowaveReassembly", "PyrowaveProtocol")]
shared += [common/"enet"/(n+".c") for n in ("unix", "callbacks")]
shared += [rs/"rs.c", rs/"deps/obl/oblas_common.c", rs/"deps/obl/oblas_lite.c"]
flags = ["-std=gnu11", "-O2", "-DNDEBUG", "-DHAS_SOCKLEN_T=1", "-DHAVE_CLOCK_GETTIME=1", "-DLC_ANDROID",
         "-ffunction-sections", "-fdata-sections", "-fno-strict-aliasing", "-fPIE",
         *["-I"+str(p) for p in [src, c, common/"enet/include", bridge/"openssl/include", rs, rs/"deps", rs/"deps/obl"]]]
def compile_file(source, obj, extra=()):
    subprocess.run([str(cc), *extra, *flags, "-c", str(source), "-o", str(obj)], check=True)

objects = []
for path in shared:
    obj = out/(path.stem+".o")
    compile_file(path, obj)
    objects.append(obj)
for mode in ("c", "rust"):
    variant = mode + ("_replay" if replay else "")
    overlay = out/variant
    overlay.mkdir(exist_ok=True)
    for filename, substitutions in patch.items():
        original = (c/filename).read_text(encoding="utf-8")
        if mode == "rust":
            for a,b,count in substitutions:
                if original.count(a) != count:
                    raise ValueError(f"Upstream ownership path changed: {filename}: {a}")
                original = original.replace(a, b)
            original = '#include "pool_api.h"\n' + original
        (overlay/filename).write_text(original, encoding="utf-8")
    local = []
    for n, path in [("receiver", src/"receiver.c"), ("RtpVideoQueue", overlay/"RtpVideoQueue.c"),
                    ("VideoDepacketizer", overlay/"VideoDepacketizer.c")]:
        obj = overlay/(n+".o")
        extra = ["-DREPLAY_TAPE"] if replay else []
        compile_file(path, obj, [*extra, "-I"+str(overlay), "-DUSE_RUST_POOL="+str(int(mode=="rust"))])
        local.append(obj)
    libs = [out/"libpacket_pool.a"] if mode == "rust" else []
    args = [str(cc), "-pie", "-Wl,--gc-sections", *map(str, local+objects+libs),
            str(bridge/"openssl/arm64-v8a/libcrypto.a"), "-llog", "-landroid", "-lz", "-lm", "-ldl", "-o", str(out/("rx_"+variant))]
    subprocess.run(args, check=True)
if replay:
    subprocess.run([str(cc), "-std=gnu11", "-O2", str(src/"tape.c"), "-o", str(out/"capture")], check=True)
