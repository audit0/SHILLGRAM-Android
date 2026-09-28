#include <stdbool.h>

#ifdef NDEBUG
#define LOG_DISABLED
#endif
// SHILLGRAM: our package and our release certificate (DER size and CRC-32).
#define PACKAGE_NAME "io.github.audit0.shillgram"_iobfs.c_str()
#define CERT_HASH 0x324f061b
#define CERT_SIZE 0x4f3

#ifdef __cplusplus
extern "C" {
#endif

bool check_signature();

#ifdef __cplusplus
}
#endif