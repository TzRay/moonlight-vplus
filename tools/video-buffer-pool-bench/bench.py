"""Run isolated, screen-free A/B tests on Android over a real LAN socket."""
import csv
import hashlib
import json
import math
import os
from pathlib import Path
import re
import socket
import statistics
import struct
import subprocess
import sys
import time
import zlib

stage_name, output, serial, clip_name = sys.argv[1:5]
out, clip = Path(output).resolve(), Path(clip_name).resolve()
if not re.fullmatch(r"shield-rust-pool-[0-9-]+", out.name):
    raise ValueError("Pass the experimental build directory.")
remote = "/data/local/tmp/" + out.name
adb_bin = str(Path(os.environ["LOCALAPPDATA"])/"Android/Sdk/platform-tools/adb.exe")
client = serial.split(":")[0]
with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
    s.connect((client, 54379))
    host = s.getsockname()[0]
port = 54379
mixed = int(os.environ.get("POOL_INTERLEAVE", "0"))

def adb(*args, timeout=35, check=True):
    return subprocess.run([adb_bin, "-s", serial, *map(str, args)], timeout=timeout, check=check, capture_output=True,
                          encoding="utf-8", errors="replace")

def state():
    dump = adb("shell", "dumpsys", "power").stdout
    return {key: re.search(pattern, dump).group(1) for key, pattern in
            [("wakefulness", r"mWakefulness=(\w+)"), ("display", r"Display Power: state=(\w+)")]}

def stage():
    adb("shell", "mkdir", "-p", remote)
    for arm in ("c","rust"):
        file = out/("rx_"+arm)
        adb("push", file, remote+"/"+file.name)
        adb("shell", "chmod", "700", remote+"/"+file.name)
        assert adb("shell", "sha256sum", remote+"/"+file.name).stdout.split()[0] == hashlib.sha256(file.read_bytes()).hexdigest()
    print("Receivers verified", host, client, state(), flush=True)

def read_csv(path):
    with path.open(encoding="ascii") as f:
        meta = f.readline().strip().split(",")
        assert meta[0] == "META"
        keys = "frames delivered injected requests rcvbuf takes fallback peak arena".split()
        assert len(meta) == len(keys)+1
        rows = [{k:int(v) for k,v in row.items()} for row in csv.DictReader(f)]
        assert [r["frame"] for r in rows] == list(range(1,int(meta[1])+1))
        return dict(zip(keys,map(int,meta[1:]))),rows

def run_one(label, arm, n, *, verify=False, inject=False, slots=1024):
    outfile = remote+"/frames.csv"
    logfile = out/(label+".receiver.log")
    cmd = [adb_bin, "-s", serial, "shell", remote+"/rx_"+arm, client, str(port), str(n), str(mixed),
           str(int(verify)), str(int(inject)), str(slots), outfile]
    before = state()
    with logfile.open("w",encoding="utf-8") as log, subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=log) as rx:
        try:
            ready = rx.stdout.readline().strip()
            if ready != b"READY":
                raise RuntimeError(f"receiver did not start: {ready!r} {logfile.read_text(errors='replace')[-350:]}")
            env = dict(os.environ, POOL_CLIP=str(clip), POOL_HOST=host, POOL_CLIENT=client, POOL_PORT=str(port),
                       POOL_FRAMES=str(n), POOL_INTERLEAVE=str(mixed))
            with (out/(label+".sender.log")).open("wb") as senderlog:
                subprocess.run([str(out/"sender.exe"), "--gtest_filter=BufferPoolBench.SendClip"],
                               cwd=out, env=env, stdout=senderlog, stderr=subprocess.STDOUT, check=True, timeout=n/60+35)
            log.write(rx.communicate(timeout=18)[0].decode(errors="replace"))
            if rx.returncode:
                raise RuntimeError(f"receiver failed: {rx.returncode}; see {logfile}")
        finally:
            if rx.poll() is None:
                rx.terminate()
    data = out/(label+".csv")
    adb("pull", outfile, data)
    adb("shell", "rm", "-f", outfile)
    meta, rows = read_csv(data)
    assert meta["frames"]==n and meta["delivered"]==sum(bool(r["ready_ns"]) for r in rows)
    result = {"label":label,"arm":arm,"file":data.name,"verification":verify,"inject":inject,"slot_limit":slots,
              "interleaved":mixed,"power_before":before,"power_after":state(),**meta}
    if not verify:
        sample = timed(rows)
        wall = sample[-1]["first_ns"] - sample[0]["first_ns"]
        result.update(cpu_pct=100*(sample[-1]["thread_cpu_ns"]-sample[0]["thread_cpu_ns"])/wall,
                      p99_ms=percentile(latencies(sample[:-1]), .99))
    (out/(label+".json")).write_text(json.dumps(result,indent=2),encoding="utf-8")
    print(label, arm, "complete", meta["delivered"], "/", n, "takes", meta["takes"], "miss",meta["fallback"],
          "CPU",round(result.get("cpu_pct",0),2),"P99",round(result.get("p99_ms",0),3), flush=True)
    return rows

def source_crc():
    result = []
    with clip.open("rb") as f:
        count, = struct.unpack("<I", f.read(4))
        for _ in range(count):
            size, expected = struct.unpack("<II", f.read(8))
            frame = f.read(size)
            assert zlib.crc32(frame)==expected
            crc = 0
            for nal in re.split(b"\0\0\1",frame)[1:]:
                nal = nal.rstrip(b"\0")
                if nal and ((nal[0] >> 1) & 63) <= 34:
                    crc = zlib.crc32(struct.pack("<I", len(nal)),crc)
                    crc = zlib.crc32(nal,crc)
            result.append(crc)
    assert len(result)==60
    return result

def verify():
    golden = source_crc()
    canonical = {}
    all_runs = []
    for label,arm,drop,slots in [("verify-c","c",False,1024),("verify-rust","rust",False,1024),
                                 ("fec-c","c",True,1024),("fec-rust","rust",True,1024),
                                 ("overflow-rust","rust",True,32)]:
        rows = run_one(label, arm, 120, verify=True, inject=drop, slots=slots)
        all_runs.append((label,rows))
        for r in rows:
            if not r["ready_ns"]: continue
            i=(r["frame"]-1)%60
            if r["video_crc"] != golden[i]:
                raise AssertionError(f"{label} frame {r['frame']} picture CRC {r['video_crc']} expected {golden[i]}")
            delivered=(r["length"],r["crc"])
            if i in canonical: assert delivered==canonical[i], (label,r["frame"],delivered,canonical[i])
            else: canonical[i]=delivered
        meta=json.loads((out/(label+".json")).read_text())
        if drop: assert meta["injected"] and sum(r["repairs"] for r in rows) > 0
        if slots==32: assert meta["fallback"]>0
    assert len(canonical)==60
    for label,rows in all_runs:
        assert len({(r["frame"]-1)%60 for r in rows if r["ready_ns"]})==60, label
    (out/"verified-lengths.json").write_text(json.dumps([canonical[i][0] for i in range(60)]))
    print("All 60 source pictures and every submitted output checksum matched across both receivers.",flush=True)

def timed(rows):
    # Reference both clocks at the first packet of frame 121 and the first
    # packet of the final frame. A terminal UDP marker never affects the window.
    assert rows[120]["first_ns"] and rows[-1]["first_ns"]
    return rows[120:]

def percentile(values,q):
    seq=sorted(values)
    return seq[math.ceil(len(seq)*q)-1]

def latencies(rows):
    return [(r["ready_ns"]-r["first_ns"])/1e6 for r in rows if r["ready_ns"]]

def measure():
    assert (out/"verified-lengths.json").is_file()
    for index, arm in enumerate(("c","rust","rust","c","rust","c","c","rust"),1):
        run_one(f"run-{index:02}-{arm}", arm, 1080)
    analyze()

def analyze():
    lengths = json.loads((out/"verified-lengths.json").read_text())
    modes={arm:{"cpu":0,"process":0,"wall":0,"delays":[],"attempted":0,"completed":0,"packets":0,"repairs":0,
                "eligible":0,"windows":[],"miss":0,"peak":0,"calls":0} for arm in ("c","rust")}
    for path in sorted(out.glob("run-*.csv")):
        meta,rows=read_csv(path)
        arm=path.stem.split("-")[2]
        s=modes[arm]
        for row in rows:
            if row["ready_ns"]: assert row["length"]==lengths[(row["frame"]-1)%60], (path,row)
        stable=timed(rows)
        start,stop=stable[0],stable[-1]
        active=stable[:-1]
        elapsed=stop["first_ns"]-start["first_ns"]
        cpu=stop["thread_cpu_ns"]-start["thread_cpu_ns"]
        s["wall"]+=elapsed; s["cpu"]+=cpu; s["process"]+=stop["process_cpu_ns"]-start["process_cpu_ns"]
        delays=latencies(active)
        s["delays"]+=delays; s["attempted"]+=len(active); s["completed"]+=len(delays)
        s["packets"]+=sum(r["packets"] for r in active); s["repairs"]+=sum(r["repairs"] for r in active)
        s["eligible"]+=len(rows); s["miss"]+=meta["fallback"]; s["peak"]=max(s["peak"],meta["peak"]); s["calls"]+=meta["takes"]
        s["windows"].append({"file":path.name,"cpu_pct":100*cpu/elapsed,"p99_ms":percentile(delays,.99),
                             "complete":len(delays),"attempted":len(active),"packets":sum(r["packets"] for r in active)})
    summary={}
    for arm,s in modes.items():
        assert len(s["windows"])==4
        summary[arm]={"cpu_pct_of_one_core":100*s["cpu"]/s["wall"],"process_cpu_pct":100*s["process"]/s["wall"],
                      "cpu_ms_per_completed":s["cpu"]/1e6/s["completed"],"completed":s["completed"],"expected":s["attempted"],
                      "received_packets":s["packets"],"repaired_shards":s["repairs"],"p50_ms":percentile(s["delays"],.5),
                      "p95_ms":percentile(s["delays"],.95),"p99_ms":percentile(s["delays"],.99),
                      "slowest_one_percent_mean_ms":statistics.mean(sorted(s["delays"],reverse=True)[:math.ceil(len(s["delays"])*.01)]),
                      "pool_takes_full_runs":s["calls"],"pool_fallback":s["miss"],"pool_peak_slots":s["peak"],"runs":s["windows"]}
    a,b=summary["c"],summary["rust"]
    overall={"input_sha256":hashlib.sha256(clip.read_bytes()).hexdigest(),"input_video_mbps":round((clip.stat().st_size-4-60*8)*8/1e6,3),
             "fec_percent":30,"target_fps":60,"packet_order":"interleaved" if mixed else "sequential",
             "method":"Same 60 pre-encoded 4K HEVC Main10 pictures looped. Isolated Sunshine videoBroadcastThread, physical LAN UDP, Android common-c VideoReceiveThreadProc + RtpVideoQueue + VideoDepacketizer in direct-submit mode. Clock from first app-read packet of a frame to complete DECODE_UNIT callback. Both allocators omit decode, JNI copy, audio and video encryption. CPU includes user and kernel time charged to the receive thread. 100% is one core. Warmup 120 frames per run, final frame excluded. Nearest-rank percentiles on delivered frames; missing frames reported separately.",
             "arms":summary,"improvement":{"cpu_percentage_points":a["cpu_pct_of_one_core"]-b["cpu_pct_of_one_core"],
             "cpu_relative_percent":100*(1-b["cpu_pct_of_one_core"]/a["cpu_pct_of_one_core"]),
             "p99_ms":a["p99_ms"]-b["p99_ms"],"p99_relative_percent":100*(1-b["p99_ms"]/a["p99_ms"])}}
    paired = []
    common_delays = {"c": [], "rust": []}
    order = sorted(out.glob("run-*.csv"))
    for left, right in zip(order[::2], order[1::2]):
        pair = {}
        for path in (left, right):
            arm = path.stem.split("-")[2]
            pair[arm] = timed(read_csv(path)[1])[:-1]
        both = [(l,r) for l,r in zip(pair["c"],pair["rust"]) if l["ready_ns"] and r["ready_ns"]]
        assert all(l["frame"]==r["frame"] for l,r in both)
        c_ms=latencies([l for l,r in both]); rust_ms=latencies([r for l,r in both])
        common_delays["c"].extend(c_ms); common_delays["rust"].extend(rust_ms)
        pair_cpu={}
        for path in (left,right):
            record=json.loads(path.with_suffix(".json").read_text(encoding="utf-8"))
            pair_cpu[record["arm"]]=record["cpu_pct"]
        paired.append({"files":[left.name,right.name],"common_complete":len(both),
                       "cpu_improvement_pp":pair_cpu["c"]-pair_cpu["rust"],
                       "common_frame_p99_improvement_ms":percentile(c_ms,.99)-percentile(rust_ms,.99)})
    overall["matched_sensitivity"]={"same_index_complete_in_both":len(common_delays["c"]),
                                    "p99_ms":{arm:percentile(delays,.99) for arm,delays in common_delays.items()},
                                    "pairs":paired}
    (out/"results.json").write_text(json.dumps(overall,indent=2),encoding="utf-8")
    print(json.dumps(overall["improvement"]),flush=True)

def clean():
    for name in ("rx_c","rx_rust","frames.csv","rx_c_replay","rx_rust_replay","capture","tape.bin"):
        adb("shell","rm","-f",remote+"/"+name)
    adb("shell","rmdir",remote)
    adb("shell","test","!","-e",remote)
    print("Removed device fixtures",state())

def replay():
    frames = 180
    for exe in ("capture", "rx_c_replay", "rx_rust_replay"):
        adb("push", out/exe, remote+"/"+exe)
        adb("shell","chmod","700",remote+"/"+exe)
        assert adb("shell","sha256sum",remote+"/"+exe).stdout.split()[0]==hashlib.sha256((out/exe).read_bytes()).hexdigest()
    path=remote+"/tape.bin"
    env=dict(os.environ,POOL_HOST=host,POOL_CLIENT=client,POOL_PORT=str(port),POOL_FRAMES=str(frames),
             POOL_CLIP=str(clip),POOL_INTERLEAVE=str(mixed))
    with subprocess.Popen([adb_bin,"-s",serial,"shell",remote+"/capture",client,str(port),str(frames),path],
                          stdout=subprocess.PIPE,stderr=subprocess.STDOUT) as recorder:
        assert recorder.stdout.readline().strip()==b"READY"
        with (out/"tape.sender.log").open("wb") as log:
            subprocess.run([str(out/"sender.exe"),"--gtest_filter=BufferPoolBench.SendClip"],cwd=out,env=env,
                           stdout=log,stderr=subprocess.STDOUT,check=True,timeout=35)
        end=recorder.communicate(timeout=35)[0].decode()
        assert recorder.returncode==0,end
    checksum=adb("shell","sha256sum",path).stdout.split()[0]
    print("Recorded",end.strip(),"for identical-input A/B",flush=True)
    checks,per_arm,all_delays={},{"c":[],"rust":[]},{"c":[],"rust":[]}
    source=source_crc()
    expected=None
    for i,arm in enumerate(("c","rust","c","rust","rust","c","rust","c","c","rust")):
        checking=i<2
        dest=remote+"/frames.csv"
        adb("shell","env","POOL_TAPE="+path,remote+"/rx_"+arm+"_replay",client,str(port),str(frames),
            str(mixed),str(int(checking)),"0","1024",dest)
        csvpath=out/f"tape-{i:02}-{arm}.csv"
        adb("pull",dest,csvpath)
        meta,rows=read_csv(csvpath)
        identity=[(r["frame"],r["packets"],r["length"],r["repairs"]) for r in rows]
        if expected is None: expected=identity
        else: assert identity==expected
        if checking:
            checks[arm]=[(r["frame"],r["crc"]) for r in rows if r["ready_ns"]]
            assert all(r["video_crc"]==source[(r["frame"]-1)%60] for r in rows if r["ready_ns"])
            if i==1: assert checks["c"]==checks["rust"]
            continue
        sample=rows[60:]
        deliveries=latencies(sample[:-1])
        assert len(deliveries)==len(sample)-1, "incomplete tape steady window"
        all_delays[arm].extend(deliveries)
        per_arm[arm].append({"csv":csvpath.name,"cpu_ms_per_frame":(sample[-1]["thread_cpu_ns"]-sample[0]["thread_cpu_ns"])/1e6/len(deliveries),
                             "p99_processing_ms":percentile(deliveries,.99),"completed":len(deliveries)})
    aggregate={arm:{"cpu_ms_per_frame":statistics.mean(x["cpu_ms_per_frame"] for x in runs),
                    "p99_processing_ms":percentile(all_delays[arm],.99),"completed":len(all_delays[arm])}
               for arm,runs in per_arm.items()}
    result={"sha256":checksum,"trace":end.strip(),"mode":"Unpaced, app-visible datagrams preloaded into RAM. Same data, order, drops and FEC both sides. Memory copy substitutes for the kernel socket read; absolute times are for processing only.",
            "verified_frames":len(checks["c"]),"power_after":state(),"aggregate":aggregate,"runs":per_arm}
    (out/"fixed-input.json").write_text(json.dumps(result,indent=2),encoding="utf-8")
    print("Fixed input:",json.dumps(aggregate),flush=True)

{"stage":stage,"verify":verify,"measure":measure,"analyze":analyze,"clean":clean,"replay":replay}[stage_name]()
