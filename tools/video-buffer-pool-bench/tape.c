// Record actual incoming datagrams once. Both local replays consume this same
// ordering after preloading the tape, excluding file I/O and network spacing.
#include <arpa/inet.h>
#include <netinet/in.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc != 5) return 1;
    const unsigned frames = atoi(argv[3]), width = 1408;
    if (!frames || frames > 240) return 2;
    const size_t limit = (size_t)frames * 1024;
    unsigned char *data = malloc(limit * width);
    if (!data) return 3;
    struct sockaddr_in addr = {.sin_family=AF_INET, .sin_port=htons(atoi(argv[2]))};
    int fd = socket(AF_INET, SOCK_DGRAM, 0), buffer = 6*1024*1024;
    const struct timeval timeout = {.tv_sec=12};
    if (fd < 0 || setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &buffer, sizeof(buffer)) ||
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout)) ||
        inet_pton(AF_INET, argv[1], &addr.sin_addr) != 1 || bind(fd, (struct sockaddr *)&addr, sizeof(addr))) return 4;
    puts("READY"); fflush(stdout);
    uint32_t count = 0;
    while (count < limit) {
        uint8_t *slot = data + (size_t)count * width;
        ssize_t n = recv(fd, slot, width, 0);
        if (n == 4 && !memcmp(slot, "DONE", 4)) break;
        if (n != width) return 5;
        count++;
    }
    close(fd);
    FILE *f = fopen(argv[4], "wb");
    if (count == limit || !f || fwrite(&count, sizeof(count), 1, f) != 1 || fwrite(data, width, count, f) != count || fclose(f)) return 6;
    free(data);
    printf("%u packets\n", count);
    return 0;
}
