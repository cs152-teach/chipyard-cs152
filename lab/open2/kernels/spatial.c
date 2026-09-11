/* spatial -- the same OFFSETS inside every region, at unpredictable region bases. */
#include "bench.h"

#define REGION_W  64                    /* 64 words = 256 B = 8 lines */
#define NREG      256                   /* 64 KiB -- see bench.h on the budget */
#define N         (REGION_W * NREG)
#define NOFF      4

#ifndef WORK
#define WORK      4
#endif

static unsigned int a[N] __attribute__((aligned(256)));

static unsigned int bitrev8(unsigned int v)
{
  v = ((v & 0x55u) << 1) | ((v >> 1) & 0x55u);
  v = ((v & 0x33u) << 2) | ((v >> 2) & 0x33u);
  v = ((v & 0x0fu) << 4) | ((v >> 4) & 0x0fu);
  return v & 0xffu;
}

static const unsigned int off[NOFF] = { 0, 12, 40, 52 };

int main(void)
{
  unsigned int i, r, acc = 0;

  for (i = 0; i < N; i++) a[i] = i;

  setStats(1);
  for (r = 0; r < NREG; r++) {
    unsigned int base = bitrev8(r) * REGION_W;   /* constant multiply: a shift */
    unsigned int t, w;
    t = a[base +  0]; for (w = 0; w < WORK; w++) t = (t ^ (t << 1)) + r; acc += t;
    t = a[base + 12]; for (w = 0; w < WORK; w++) t = (t ^ (t << 1)) + r; acc += t;
    t = a[base + 40]; for (w = 0; w < WORK; w++) t = (t ^ (t << 1)) + r; acc += t;
    t = a[base + 52]; for (w = 0; w < WORK; w++) t = (t ^ (t << 1)) + r; acc += t;
  }
  setStats(0);
  bench_report("spatial", NREG * NOFF, NREG * NOFF);
  printf("  sink=%u\n", acc);
  return 0;
}
