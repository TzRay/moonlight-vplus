#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
#include <stdlib.h>

typedef struct PacketPool PacketPool;
PacketPool *pool_create(size_t size, size_t count);
void *pool_take(PacketPool *pool, size_t size);
bool pool_return(PacketPool *pool, void *packet);
bool pool_finish(PacketPool *pool, uint64_t stats[4]);
extern PacketPool *session_pool;

static inline void *take_packet(size_t size) {
    void *packet = pool_take(session_pool, size);
    return packet ? packet : malloc(size);
}
static inline void release_packet(void *ptr) {
    if (!pool_return(session_pool, ptr)) free(ptr);
}
