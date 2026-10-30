/*
 * Test double for librtlsdr (NOT shipped). Models the part of the RTL2832U + USB that matters for sample loss:
 * the dongle produces 2.4 MS/s (4.8 MB/s) in real time; a queued USB transfer absorbs data as it arrives; with no
 * transfer queued, only the dongle's small FIFO (MOCK_FIFO bytes, default 16384) holds data and the rest is lost.
 * read_sync queues one transfer per call; read_async keeps buf_num transfers queued.
 * Samples come from MOCK_RTL_FILE (.cu8, looped). Lost bytes are counted and printed at close.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

typedef struct { int idx; uint32_t freq, rate; int gain, gain_mode, ppm; volatile int cancel; } rtlsdr_dev_t;
typedef void (*rtlsdr_read_async_cb_t)(unsigned char *buf, uint32_t len, void *ctx);

static unsigned char *data; static long dlen;
static double t0 = -1; static long long host_pos, lost;
static long fifo = 16384;
static double now(void) { struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return ts.tv_sec + ts.tv_nsec * 1e-9; }
static long long produced(void) { return (long long) ((now() - t0) * 4800000.0) & ~1LL; }
static void load(void) {
    if (data) return;
    const char *f = getenv("MOCK_RTL_FILE"); if (!f) { fprintf(stderr, "mock: MOCK_RTL_FILE not set\n"); exit(1); }
    FILE *fp = fopen(f, "rb"); fseek(fp, 0, SEEK_END); dlen = ftell(fp); fseek(fp, 0, SEEK_SET);
    data = malloc(dlen); if (fread(data, 1, dlen, fp) != (size_t) dlen) exit(2); fclose(fp);
    if (getenv("MOCK_FIFO")) fifo = atol(getenv("MOCK_FIFO"));
}
static void copy_out(unsigned char *buf, long n) { for (long k = 0; k < n; k++) buf[k] = data[(host_pos + k) % dlen]; host_pos += n; }
/* data that arrived while `capacity` bytes of queued transfers + the FIFO could hold it; the excess is lost */
static void account(long long capacity) {
    long long avail = produced() - host_pos;
    if (avail > capacity + fifo) { long long d = (avail - capacity - fifo) & ~1LL; lost += d; host_pos += d; }
}
static void wait_for(long long n) { while (produced() - host_pos < n) usleep(200); }

uint32_t rtlsdr_get_device_count(void) { const char *c = getenv("MOCK_RTL_COUNT"); return c ? atoi(c) : 1; }
const char *rtlsdr_get_device_name(uint32_t i) { return "Mock RTL2832U"; }
int rtlsdr_get_device_usb_strings(uint32_t i, char *m, char *p, char *s) { strcpy(m, "Mock"); strcpy(p, "RTL2838"); sprintf(s, "MOCK%u", i); return 0; }
int rtlsdr_open(rtlsdr_dev_t **d, uint32_t i) { load(); *d = calloc(1, sizeof(rtlsdr_dev_t)); (*d)->idx = i; return 0; }
int rtlsdr_close(rtlsdr_dev_t *d) { fprintf(stderr, "mock: closed, %lld bytes received, %lld bytes LOST (%.3f%%)\n", host_pos, lost, 100.0 * lost / (host_pos + 1)); free(d); return 0; }
int rtlsdr_set_center_freq(rtlsdr_dev_t *d, uint32_t f) { d->freq = f; return 0; }
uint32_t rtlsdr_get_center_freq(rtlsdr_dev_t *d) { return d->freq; }
int rtlsdr_set_sample_rate(rtlsdr_dev_t *d, uint32_t r) { d->rate = r; return 0; }
int rtlsdr_set_freq_correction(rtlsdr_dev_t *d, int p) { d->ppm = p; return 0; }
int rtlsdr_set_tuner_gain_mode(rtlsdr_dev_t *d, int m) { d->gain_mode = m; return 0; }
int rtlsdr_set_tuner_gain(rtlsdr_dev_t *d, int g) { d->gain = g; return 0; }
int rtlsdr_get_tuner_gain(rtlsdr_dev_t *d) { return d->gain; }
static const int gains[] = {0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254, 280, 297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496};
int rtlsdr_get_tuner_gains(rtlsdr_dev_t *d, int *g) { if (g) memcpy(g, gains, sizeof gains); return 29; }
int rtlsdr_set_agc_mode(rtlsdr_dev_t *d, int on) { return 0; }
int rtlsdr_reset_buffer(rtlsdr_dev_t *d) { if (t0 < 0) t0 = now(); host_pos = produced(); return 0; }
int rtlsdr_get_tuner_type(rtlsdr_dev_t *d) { return 5; }
int rtlsdr_set_bias_tee(rtlsdr_dev_t *d, int on) { return 0; }
int rtlsdr_set_direct_sampling(rtlsdr_dev_t *d, int on) { return 0; }
int rtlsdr_read_sync(rtlsdr_dev_t *d, void *buf, int len, int *n) {
    account(0);            /* nothing was queued since the last call returned */
    wait_for(len);         /* now one transfer is queued: it absorbs data until full */
    copy_out(buf, len); *n = len; return 0;
}
int rtlsdr_read_async(rtlsdr_dev_t *d, rtlsdr_read_async_cb_t cb, void *ctx, uint32_t buf_num, uint32_t buf_len) {
    if (!buf_num) buf_num = 15; if (!buf_len) buf_len = 262144;
    unsigned char *buf = malloc(buf_len); d->cancel = 0;
    while (!d->cancel) {
        account((long long) buf_num * buf_len);   /* buf_num transfers stay queued while the callback runs */
        wait_for(buf_len);
        copy_out(buf, buf_len);
        cb(buf, buf_len, ctx);
    }
    free(buf); return 0;
}
int rtlsdr_cancel_async(rtlsdr_dev_t *d) { d->cancel = 1; return 0; }
