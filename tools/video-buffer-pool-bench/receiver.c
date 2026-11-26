// Run the production receive loop, FEC queue and HEVC depacketizer. Its normal
// direct-submit callback marks a complete frame without starting a decoder.
#include "Limelight-internal.h"
#include "EndpointRefresh.h"
#include "pool_api.h"
#include <arpa/inet.h>
#include <stdarg.h>
#include <stdio.h>
#include <time.h>
#include <zlib.h>

#define REQUIRE(x) do { if (!(x)) { fprintf(stderr, "line %d: %s (errno %d)\n", __LINE__, #x, errno); exit(2); } } while (0)
static int observed_recv(LI_ENDPOINT_REFRESH *, SOCKET *, char *, int, bool);
#define LiErClientRecv observed_recv
#include "VideoStream.c"
#undef LiErClientRecv

PacketPool *session_pool;
bool VideoFecInterleavingEnabled; // Normally negotiated in RTSP, fixed by both benchmark peers.
typedef struct {
    uint64_t first, cpu, process_cpu, ready;
    unsigned packets, length, crc, video_crc, repairs;
} Sample;
static Sample *samples;
static unsigned total, verify, inject, injected, delivered, requests;
static uint8_t *scratch;
#ifdef REPLAY_TAPE
static unsigned char *tape;
static uint32_t tape_position, tape_count;
static void read_tape(void) {
    FILE *f = fopen(getenv("POOL_TAPE"), "rb");
    REQUIRE(f && fread(&tape_count, sizeof(tape_count), 1, f) == 1);
    REQUIRE(tape_count && tape_count <= total*1024);
    tape = malloc((size_t)tape_count*1408);
    REQUIRE(tape && fread(tape,1408,tape_count,f)==tape_count && fgetc(f)==EOF);
    fclose(f);
}
#endif
static uint64_t ns(clockid_t clock) {
    struct timespec t;
    REQUIRE(clock_gettime(clock, &t) == 0);
    return (uint64_t)t.tv_sec * 1000000000ULL + t.tv_nsec;
}

// Control feedback is recorded locally. The fixture is a pre-encoded video sender.
void connectionDetectedFrameLoss(uint32_t start, uint32_t end) { (void)start; (void)end; }
void connectionSawFrame(uint32_t frame) { (void)frame; }
void connectionReceivedCompleteFrame(uint32_t frame, bool ltr) { (void)frame; (void)ltr; }
void connectionSendFrameFecStatus(PSS_FRAME_FEC_STATUS status) { (void)status; }
void connectionRecordFecBlock(uint32_t f, uint8_t b, uint16_t data, uint16_t received) {
    (void)b; if (f <= total) samples[f].repairs += data - received;
}
void LiRequestIdrFrame(void) { requests++; }
bool LiGetCurrentHostDisplayHdrMode(void) { return true; }
static void terminated(int code) { fprintf(stderr, "receive terminated %d\n", code); receiveThread.cancelled = true; }

static int observed_recv(LI_ENDPOINT_REFRESH *ep, SOCKET *fd, char *packet, int size, bool select) {
#ifdef REPLAY_TAPE
    (void)ep; (void)fd; (void)select;
    if (tape_position == tape_count) {
        receiveThread.cancelled = true;
        return LI_ER_RECV_HANDLED;
    }
    REQUIRE(size >= 1408);
    int n = 1408;
    memcpy(packet,tape + (size_t)tape_position++*1408,1408);
#else
    int n = LiErClientRecv(ep, fd, packet, size, select);
#endif
    if (n == 4 && !memcmp(packet, "DONE", 4)) {
        receiveThread.cancelled = true;
        return LI_ER_RECV_HANDLED;
    }
    if (n >= MAX_RTP_HEADER_SIZE + (int)sizeof(NV_VIDEO_PACKET)) {
        const NV_VIDEO_PACKET *nv = (const void *)(packet + MAX_RTP_HEADER_SIZE);
        unsigned f = nv->frameIndex;
        REQUIRE(f >= 1 && f <= total);
        Sample *s = &samples[f];
        if (!s->packets) {
            s->first = ns(CLOCK_MONOTONIC);
            s->cpu = ns(CLOCK_THREAD_CPUTIME_ID);
            s->process_cpu = ns(CLOCK_PROCESS_CPUTIME_ID);
        }
        s->packets++;
        if (inject && f % 10 == 4 && ((nv->multiFecBlocks >> 4) & 3) == 0 &&
                ((nv->fecInfo >> 12) & 1023) == 6) {
            injected++;
            return LI_ER_RECV_HANDLED;
        }
    }
    return n;
}

// Ignore Annex B padding and optional non-picture NALs for comparison to
// the encoder input. Additionally retain the CRC of every delivered byte.
static size_t next_start(const uint8_t *data, size_t len, size_t offset) {
    for (size_t i = offset; i + 3 <= len; i++)
        if (data[i] == 0 && data[i+1] == 0 && data[i+2] == 1) return i;
    return len;
}
static unsigned picture_crc(const uint8_t *data, size_t length) {
    uint32_t crc = 0;
    size_t start = next_start(data, length, 0);
    while (start < length) {
        size_t payload = start + 3;
        size_t next = next_start(data, length, payload);
        size_t end = next;
        while (end > payload && data[end-1] == 0) end--;
        if (end > payload && ((data[payload] >> 1) & 63) <= 34) {
            uint32_t bytes = (uint32_t)(end - payload);
            crc = crc32(crc, (const Bytef *)&bytes, sizeof(bytes));
            crc = crc32(crc, data + payload, bytes);
        }
        start = next;
    }
    return crc;
}
static int completed(PDECODE_UNIT unit) {
    uint64_t now = ns(CLOCK_MONOTONIC);
    REQUIRE(unit->frameNumber > 0 && (unsigned)unit->frameNumber <= total);
    Sample *sample = &samples[unit->frameNumber];
    REQUIRE(sample->first && !sample->ready && now >= sample->first);
    sample->ready = now;
    sample->length = (unsigned)unit->fullLength;
    delivered++;
    if (verify) {
        unsigned offset = 0;
        for (PLENTRY part = unit->bufferList; part; part = part->next) {
            REQUIRE(part->length >= 0 && offset + (unsigned)part->length <= 4*1024*1024);
            memcpy(scratch + offset, part->data, part->length);
            offset += part->length;
        }
        REQUIRE(offset == sample->length);
        sample->crc = crc32(0, scratch, offset);
        sample->video_crc = picture_crc(scratch, offset);
    }
    return DR_OK;
}

int main(int argc, char **argv) {
    REQUIRE(argc == 9);
    const char *local = argv[1];
    int port = atoi(argv[2]);
    total = (unsigned)atoi(argv[3]);
    VideoFecInterleavingEnabled = atoi(argv[4]) != 0;
    verify = (unsigned)atoi(argv[5]); inject = (unsigned)atoi(argv[6]);
    size_t slots = (size_t)atoi(argv[7]);
    const char *output = argv[8];
    REQUIRE(total >= 60 && total <= 100000 && port > 0 && port < 65536);
    samples = calloc(total + 1, sizeof(*samples));
    REQUIRE(samples);
#ifdef REPLAY_TAPE
    read_tape();
#endif
    if (verify) { scratch = malloc(4*1024*1024); REQUIRE(scratch); }
    REQUIRE(initializePlatform() == 0);
    StreamConfig.packetSize = 1392;
    StreamConfig.fps = 60;
    StreamConfig.height = 2160;
    StreamConfig.width = 3840;
    AppVersionQuad[0] = 7; AppVersionQuad[1] = 1; AppVersionQuad[2] = 431; AppVersionQuad[3] = -1;
    NegotiatedVideoFormat = VIDEO_FORMAT_H265_MAIN10;
    VideoCallbacks.capabilities = CAPABILITY_DIRECT_SUBMIT | CAPABILITY_REFERENCE_FRAME_INVALIDATION_HEVC;
    VideoCallbacks.submitDecodeUnit = completed;
    ReferenceFrameInvalidationSupported = true;
    ListenerCallbacks.connectionTerminated = terminated;
#if USE_RUST_POOL
    session_pool = pool_create(StreamConfig.packetSize + MAX_RTP_HEADER_SIZE + sizeof(RTPV_QUEUE_ENTRY), slots);
    REQUIRE(session_pool);
#else
    (void)slots;
#endif
    initializeVideoStream();
    rtpSocket = socket(AF_INET, SOCK_DGRAM, 0);
    REQUIRE(rtpSocket >= 0);
    int rcvbuf = 4096 * (StreamConfig.packetSize + MAX_RTP_HEADER_SIZE);
    REQUIRE(setsockopt(rtpSocket, SOL_SOCKET, SO_RCVBUF, &rcvbuf, sizeof(rcvbuf)) == 0);
    socklen_t len = sizeof(rcvbuf);
    REQUIRE(getsockopt(rtpSocket, SOL_SOCKET, SO_RCVBUF, &rcvbuf, &len) == 0);
    struct sockaddr_in addr = {.sin_family=AF_INET, .sin_port=htons(port)};
    REQUIRE(inet_pton(AF_INET, local, &addr.sin_addr) == 1);
    REQUIRE(bind(rtpSocket, (struct sockaddr *)&addr, sizeof(addr)) == 0);
    puts("READY"); fflush(stdout);
    VideoReceiveThreadProc(NULL);
    close(rtpSocket);
    destroyVideoStream();
    uint64_t pool_stats[4] = {0};
#if USE_RUST_POOL
    REQUIRE(pool_finish(session_pool, pool_stats));
#endif
    FILE *f = fopen(output, "w");
    REQUIRE(f);
    fprintf(f, "META,%u,%u,%u,%u,%d,%llu,%llu,%llu,%llu\n", total, delivered, injected, requests, rcvbuf,
            (unsigned long long)pool_stats[0], (unsigned long long)pool_stats[1],
            (unsigned long long)pool_stats[2], (unsigned long long)pool_stats[3]);
    fputs("frame,first_ns,ready_ns,thread_cpu_ns,process_cpu_ns,packets,length,crc,video_crc,repairs\n", f);
    for (unsigned i = 1; i <= total; i++) {
        Sample *s = samples+i;
        fprintf(f, "%u,%llu,%llu,%llu,%llu,%u,%u,%u,%u,%u\n", i, (unsigned long long)s->first,
                (unsigned long long)s->ready, (unsigned long long)s->cpu, (unsigned long long)s->process_cpu,
                s->packets, s->length, s->crc, s->video_crc, s->repairs);
    }
    REQUIRE(fclose(f) == 0);
    cleanupPlatform(); free(scratch); free(samples);
#ifdef REPLAY_TAPE
    free(tape);
#endif
    return delivered ? 0 : 3;
}
