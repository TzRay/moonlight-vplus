# SHIELD 串流稳定性：分析、首轮实现与验证

日期：2026-10-27。目标：保持原有码率、4K 60fps、HEVC Main10 和 HDR，以减少卡顿和尾部延迟为验收依据。

## 1. 基线与证据范围

输入是《SHIELD Pro 串流分析结论》（2026-10-27）的 HTML，未包含它引用的原始 CSV、JSONL 和图表，因此本次使用报告中的观测结论，没有重新计算半小时统计。

| 对象 | 版本 / 提交 | 本次处理 |
| --- | --- | --- |
| 报告中的设备 APK | 12.12.0，`com.limelight.qiin` | 没有访问或更换设备上的 APK |
| 报告此前检查的源码 | 12.9.1.1 / 旧提交 | 作为问题线索 |
| 本次应用基线 | `TzRay/moonlight-vplus`，`94d921b68db9b4539fe44d0c98411575d9851463` | 从当前 master 新建分支；版本标记为 `12.14.0-beta.1` |
| 本次原生库基线 | `TzRay/moonlight-common-c`，`e6405a600f7397da7fdc5750300d56b24d0af477` | 从应用实际固定的子模块提交新建同名分支；不是 common-c 的 master |

报告支持优先调查 CPU0 网络负载与视频收包关键路径。CPU0 的流量相关性并不证明它导致了 18:46:33 的异常；没有本地 UDP 错误增量，也不能排除上游停发、链路丢包或晚到。增加普通 CPU 解码线程不能提升 NVIDIA 专用 HEVC 解码硬件的吞吐量。

## 2. 当前代码中的优化依据

### 视频接收存在串行关键路径

`VideoStream.c::VideoReceiveThreadProc()` 逐次调用 `recvUdpSocket()`，后者使用 `recvfrom()`。被 RTP 队列接管的缓冲区会导致下一轮重新分配。解密、`RtpvAddPacket()`、FEC 和重组在接收线程中执行；启用 `CAPABILITY_DIRECT_SUBMIT` 时，解码提交也在该线程执行。

`MediaCodecDecoderRenderer.fetchNextInputBuffer()` 的同步路径以 5ms 超时轮询；异步路径等待输入缓冲回调。实际发生回压时，直接提交可能拖延下一次收包，但必须测量后才能决定是否拆线程。

缓冲区不能直接改成循环覆盖的接收数组：RTP、FEC、NAL 和解码完成路径拥有不同的释放时机，`RtpVideoQueue.c` 等处会释放 `entry->packet`。复用必须覆盖整个所有权链路。

### 精确同步的等待需要重新检查条件

旧逻辑仅调用一次 `parkNanos()`，随后无条件忙等到目标时刻。`parkNanos()` 可以提前或虚假返回，因此忙等时长可能远大于注释中的 0.5ms；忙等内部也未检查停止条件。

本轮保留 0.5ms 精度预算，改为每次醒来重新检查剩余时间、停止标记和中断状态。剩余时间超过预算时继续休眠。时钟、等待器采用返回原始类型的接口，避免自旋循环中装箱 `Long`。

### 现有抖动图不能单独作为真实呈现验收

`FramePacingController` 的部分统计来自计划呈现时间；`JitterMonitor` 还会过滤超长间隔。本轮保留现有图表，另加独立的、可用于 Release 构建的诊断，记录 MediaCodec 报告的渲染时间戳间隔，并保留长停顿。

注意：MediaCodec 回调可以延后、成批发送；Android 14 之前还可能缺失回调。因此 `codec_render_interval` 是**观测到的渲染时间戳间隔**，不能单独换算为完整屏幕掉帧率。需要与 SurfaceFlinger/Perfetto、主机帧率和画面内容交叉检查。

### 当前请求包长已是 1392

`Game.buildStreamConfiguration()` 当前使用 `setMaxPacketSize(1392)`。报告中的 IP 分片计数覆盖整机，且 APK 与当前源码版本不同，不能由这些计数断定当前 Moonlight 的视频 socket 正在发送超大数据报。本轮不调整 MTU、包长、socket 缓冲或 FEC 比例。

## 3. 本轮实现

设置 → 性能与流畅度（展开更多选项）：

| 设置 | 默认 | 行为 |
| --- | --- | --- |
| 视频收包避开 CPU0（实验） | 关闭 | 在 VideoRecv 内读取当前允许 CPU 集合，只去掉 CPU0；SHIELD 的集合为 0–3 时，结果为 1–3 |
| 串流时序诊断 | 关闭 | 约每 10 秒按指标汇总分位数上界、最大值和样本数；连接退出时补记尾部窗口 |

两个开关独立，修改后重新连接。选项通过 `PreferenceConfiguration → StreamConfiguration → NvConnection → MoonBridge/JNI → STREAM_CONFIGURATION` 按会话传递，不写入 RTSP/SDP，不要求更改 Sunshine。

亲和性处理规则：

1. 默认模式不设置任何 CPU 掩码。
2. 只修改调用线程，不修改音频、解码、呈现线程，也不修改 IRQ/RPS。
3. 从实际允许集合中去掉 CPU0，不假定所有设备都具有 CPU1–CPU3，不扩大系统允许范围。
4. CPU0 已不在集合中、仅允许 CPU0、查询或设置失败时，保留原来的调度。
5. 设置成功后重新读取有效集合；验证失败会尝试恢复原掩码，分别记录验证错误和恢复错误。若恢复也失败，日志明确标记，不能宣称已经恢复。
6. 诊断开启时约每 10 秒再观察有效集合和当前运行核心，用于发现系统后续改变限制。观察不会反复强制改写掩码。

`SD[affinity]` 的 `status` 包括 `default`、`applied`、`already_excluded`、`no_alternative_cpu`、`get_failed`、`set_failed`、`verify_failed`、`unsupported`。`allowed_cpus=-1`、`cpu0_allowed=-1` 表示尚未观察，不表示没有核心。

### 诊断字段与解释

| 指标 | 测量内容 | 解读限制 |
| --- | --- | --- |
| `loop_gap` | 相邻收包循环入口的墙钟间隔 | 包含等待数据、处理和调度，不能直接称为调度延迟 |
| `receive_wait` | `recvUdpSocket()` 调用耗时，含超时返回 | 主机没有数据时正常增长，不等于丢包 |
| `packet_process` | 收到包后到 RTP/FEC/重组处理返回 | 包括解密、可能的直接解码提交及诊断开销；早期丢弃的包不进入此项 |
| `fec_decode` | Reed-Solomon 解码调用耗时 | 不含其前后的分配/整理；无 FEC 解码时没有样本 |
| `decode_submit` | 完整原生解码提交回调耗时 | 包括 JNI 复制和 Java 提交；可能与 `packet_process` 重叠，不能相加 |
| `decoder_input_acquire` | Java 获取输入缓冲的等待和获取耗时 | 不包括随后的 codec recovery；已有缓存输入缓冲时不采样 |
| `codec_render_interval` | MediaCodec 报告的相邻有效渲染时间戳差值 | 回调可能缺失；不是面板光学测量，插帧时还可能只是中间 Surface |

日志示例，数值仅用于说明格式：

```text
SD[affinity] thread=VideoRecv requested=1 status=applied errno=0 restore_errno=0 allowed_cpus=3 cpu0_allowed=0 running_cpu=2
SD[native] metric=packet_process n=180000 span_ms=10000 p95_us_le=50 p99_us_le=250 max_us=4210
SD[java] metric=codec_render_interval n=600 span_ms=10000 p95_us_le=20000 p99_us_le=33000 max_us=100000
```

`n` 是该窗口的有效样本数；`span_ms` 是窗口首末样本的时间跨度；`p95_us_le`、`p99_us_le` 是固定直方图桶的分位数上界，`max_us` 是实际记录的最大微秒值。超过 1 秒的样本也保留。各指标独立开窗，不应将不同窗口的分位数平均成整个会话的 p99。异常附近仍应保留带时间戳的原始日志。

诊断关闭时不额外读取逐包时钟、不分配直方图样本、不打印周期时序日志；若单独开启 CPU0 实验，连接开始时仍会打印一次亲和性结果和会话配置。开启诊断后原生指标各由单线程写入，使用固定存储，无逐包锁或逐包日志；Java 以每帧频率同步输入与回调采样。诊断存在观察开销，A/B 两组必须使用相同诊断状态。

显式暂停/恢复视频时会重新开始 Java 的渲染间隔序列，避免把主动暂停算成卡顿；正常解码器恢复仍可保留长间隔。连接结束关闭采集，晚到回调不再写入。

## 4. SHIELD 上的对照步骤

**采用同一个构建进行 A/B，不要直接比较旧版 12.12 APK 与新分支来归因 CPU 亲和性的效果。**

1. 固定码率、分辨率、60fps、HEVC Main10、HDR、主机编码选项、游戏场景、音频配置和有线网络。记录实际 APK 版本、代码提交和设置。
2. 使用同一 Release 构建。现有 common-c 的 Debug 构建在 `LC_DEBUG && !LC_FUZZING` 下会启用额外 FEC 校验，不能作为与 Release 相同的性能基线。`SD[session]` 会记录 `fec_validation`。
3. A 组：仅开启“串流时序诊断”，CPU0 实验关闭，重新连接并运行约 30 分钟。
4. B 组：保持诊断开启，只开启 CPU0 实验，重新连接并运行相同场景。首先确认 `status=applied` 或 `already_excluded`，然后检查后续 `SD[cpu]`。
5. 最好再跑 A 组，降低场景变化、温度和主机后台任务造成的混淆。
6. 比较无法恢复的视频帧、音频序号缺口、异常窗口的各阶段 p95/p99 上界与最大值；同时收集 SurfaceFlinger/Perfetto、各核占用、UDP/网卡错误及主机发送时序。
7. 在相同诊断状态下，如果 B 的尾部延迟或卡顿更差，就关闭 CPU0 实验并重连；先保留可复现实验数据。CPU0 占用下降本身不是通过条件。

ADB 已连接时，可从串流开始前采集进程日志；包名取实际安装的构建，不要混用 Release 和 Debug：

```bash
adb shell pidof com.limelight.qiin
adb logcat --pid <上一步得到的PID> -v threadtime > shield-A.log
```

结束串流后再停止日志采集，以保留最后不足 10 秒的统计。若应用重启导致 PID 改变，需要重新开始采集。日志中搜索 `SD[`、`Unrecoverable frame`、音频缺口及 codec recovery 相关内容。不要清空设备全部日志以开始本实验。

## 5. 后续优化的触发条件

| 优先级 | 方向 | 进入条件与实施边界 |
| --- | --- | --- |
| 下一阶段 | 批量收包 | 证据支持 syscall/逐包处理是热点时，做独立开关；有界小批次，只读已到达包，不等待凑批，保持顺序、超时和中断语义 |
| 下一阶段 | 缓冲区复用 | 分配/释放确有成本后，覆盖 RTP/FEC/NAL/解码完成及错误释放路径；有界容量、耗尽回退，不能覆盖仍在队列中的包 |
| 条件性 | 拆分收包与处理 | `packet_process`、`fec_decode` 或 `decode_submit` 显著阻塞接收时再做；队列必须有界，并检查积压延迟、丢弃策略和停止顺序 |
| 条件性 | 调整精确同步自旋窗口 | 本轮仅修复提前唤醒和停止行为；缩短 0.5ms 预算须另做实验，比较唤醒迟到和最差帧间隔 |
| 系统/主机侧 | IRQ/RPS、主机发送节奏、分片来源 | 应用线程亲和性不能直接改写网卡中断分配；只有跨端数据支持时才单独调查，不据当前报告强改系统网络参数 |

`recvmmsg()` 可以减少 syscall，但普通阻塞调用可能等待更多消息；`MSG_WAITFORONE` 只在首个消息之后转为非阻塞；其 `timeout` 有手册记载的检查时机限制。因此下一阶段不能简单地把 `recvfrom()` 换成带超时的阻塞 `recvmmsg()`，需要明确首包等待、非阻塞批量排空和取消边界。

## 6. 验证与复现

已在本次工作环境执行的验证：

| 检查 | 结果 |
| --- | --- |
| 原生 Debug 静态库构建及 CTest | 13 / 13 通过，保留断言 |
| Android Release 原生 JNI 构建 | `arm64-v8a`、`armeabi-v7a` 编译和链接通过；NDK 28.2.13676358，最低 API 22 |
| 应用主代码、资源及完整 JVM 单元测试 | 1,010 项通过，0 失败、0 错误、0 跳过；包含 11 项新增时序/等待测试 |
| 精确等待热循环字节码检查 | 时钟返回原始 `long`，循环中没有 `Long` 装箱 |
| SHIELD 真机性能 | 尚未运行，需要按第 4 节验收 |

原生构建与测试：

```bash
cmake -S app/src/main/jni/moonlight-core/moonlight-common-c \
  -B /tmp/moonlight-native-tests -DBUILD_SHARED_LIBS=OFF -DCMAKE_BUILD_TYPE=Debug \
  -DHAVE_CLOCK_GETTIME=ON
cmake --build /tmp/moonlight-native-tests -j4
ctest --test-dir /tmp/moonlight-native-tests --output-on-failure
```

新增测试覆盖：亲和性默认关闭、稀疏 CPU 集合、仅 CPU0 可用、系统裁剪、查询/设置/验证/恢复失败；直方图边界和超长停顿；原生解码回调返回值、关闭诊断时不读取时钟、窗口刷新和跨会话重置；时钟/日志改变系统错误码后，真实收包循环仍传递原始错误；提前唤醒后二次休眠、过期截止时间、自旋中停止、中断标记保留；Java 回调乱序/重复、暂停恢复、两个生产线程及尾部刷新。

为了运行已有原生测试，补上了 CMake 对 `libm` 的依赖（`ControlStream` 已使用 `llround`），并让已有 `PyrowaveDecodeUnitTest` 在销毁队列前按生产生命周期调用停止函数；没有关闭断言。

Android CI 已加入上述原生构建和 CTest 步骤，应用侧新测试由现有单元测试步骤自动运行。

Android 使用仓库当前 CI 固定的依赖和音频 SDK。定向测试命令：

```bash
bash gradlew :app:testNonRootDebugUnitTest \
  --tests '*PreciseFrameWaiterTest' \
  --tests '*StreamTimingDiagnosticsTest' \
  --tests '*SettingsResourceHygieneTest' --no-daemon
```

本轮没有运行 SHIELD 真机测试，也没有测得 CPU 占用、卡顿次数或帧间隔的改善幅度。代码验证与设备性能验收应分别记录。

## 7. 一手资料

- [Linux：线程亲和性及 cpuset 限制](https://man7.org/linux/man-pages/man2/sched_setaffinity.2.html)
- [Linux：网络 IRQ、RSS、RPS、RFS](https://docs.kernel.org/networking/scaling.html)
- [Linux：recvmmsg 等待语义和超时限制](https://man7.org/linux/man-pages/man2/recvmmsg.2.html)
- [Java：LockSupport 允许提前和虚假唤醒](https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/locks/LockSupport.html)
- [Android：MediaCodec 渲染回调时间戳、批量回调及缺失回调限制](https://developer.android.com/reference/android/media/MediaCodec.OnFrameRenderedListener)
