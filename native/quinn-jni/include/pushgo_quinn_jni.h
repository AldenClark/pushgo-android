#ifndef PUSHGO_QUINN_JNI_H
#define PUSHGO_QUINN_JNI_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct PgBuffer {
  uint8_t *ptr;
  uint32_t len;
} PgBuffer;

uint64_t pg_quinn_connect(const char *host, uint16_t port, const char *alpn);
int32_t pg_quinn_write_frame(uint64_t handle, uint8_t frame_type, const uint8_t *payload, uint32_t payload_len);
PgBuffer pg_quinn_read_frame(uint64_t handle, uint32_t timeout_ms);
void pg_quinn_buffer_free(uint8_t *ptr, uint32_t len);
void pg_quinn_close(uint64_t handle);

#ifdef __cplusplus
}
#endif

#endif
