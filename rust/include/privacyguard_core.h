#pragma once
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/// Classify a raw packet. Returns bitmask: 0x01=block 0x02=tls 0x04=dns
int32_t pg_classify_packet(const uint8_t *data, int32_t len);

/// JA3 fingerprint of a TLS ClientHello. Free result with pg_free_string.
char   *pg_compute_ja3(const uint8_t *data, int32_t len);

/// Free a string returned by pg_*.
void    pg_free_string(char *s);

/// Bloom filter check (1 = probable hit, 0 = definite miss).
int32_t pg_bloom_check(const char *domain);

/// Rebuild bloom filter from NULL-terminated array of C strings.
void    pg_bloom_rebuild(const char **domains);

/// Shannon entropy of a byte buffer.
double  pg_shannon_entropy(const uint8_t *data, int32_t len);

#ifdef __cplusplus
}
#endif
