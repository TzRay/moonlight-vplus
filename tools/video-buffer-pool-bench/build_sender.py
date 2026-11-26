"""Link a test-only Sunshine sender with an existing Release test build."""
import os
from pathlib import Path
import subprocess
import sys

out, sun = (Path(a).resolve() for a in sys.argv[1:])
src = Path(__file__).resolve().parent
host = sun / "build/upstream-sync-20261103/windows"
tools = sun / ".codex-tools/msys64/ucrt64/bin"
out.mkdir(parents=True, exist_ok=True)

# Use the paired sender's source snapshot where present to match its compiled dependencies.
tested = sun / "build/video-fec-interleave"
snapshot = tested / "source-78e035a7"
source = (snapshot if (snapshot / "src/stream.cpp").exists() else sun) / "src/stream.cpp"
(out / "send.cpp").write_text(
    f'#include "{source.as_posix()}"\n#include "{(src / "sunshine_sender.inc").as_posix()}"\n', encoding="utf-8")
manifest = (tested / "probe.ninja").read_text(encoding="utf-8").splitlines()

def stanza(prefix):
    i = next(i for i, line in enumerate(manifest) if line.startswith(prefix))
    result = [manifest[i].split(" || ")[0]]
    for line in manifest[i+1:]:
        if not line.startswith("  "):
            break
        result.append(line)
    return "\n".join(result)

def ninja_path(p):
    return p.as_posix().replace(":", "$:").replace(" ", "$ ")

obj = "CMakeFiles/sunshine_host_objects.dir/src/video_broadcast_probe.cpp.obj"
exe = "tests/video_broadcast_probe.exe"
compile_rule = stanza("build " + obj + ":").replace(
    ninja_path(tested / "broadcast_probe.cpp"), ninja_path(out / "send.cpp"))
if source.parent.parent == snapshot:
    compile_rule = compile_rule.replace("  INCLUDES = ", "  INCLUDES = -I" + snapshot.as_posix() + " ", 1)
link_rule = stanza("build " + exe + ":")
body = (compile_rule + "\n" + link_rule).replace(obj, ninja_path(out/"send.obj"))
body = body.replace(exe + ":", ninja_path(out/"sender.exe") + ":")
body = body.replace(r"tests\video_broadcast_probe.exe", (out/"sender.exe").as_posix())
body = body.replace(r"tests\libvideo_broadcast_probe.dll.a", (out/"sender.dll.a").as_posix())
(out/"sender.ninja").write_text("CONFIGURATION = Release\nbuilddir = " + ninja_path(out) +
    "\ninclude CMakeFiles/rules.ninja\n" + body + "\n", encoding="utf-8")
env = dict(os.environ, PATH=str(tools) + os.pathsep + os.environ["PATH"])
subprocess.run([str(tools/"ninja.exe"), "-f", str(out/"sender.ninja"), (out/"sender.exe").as_posix()],
               cwd=host, env=env, check=True)
