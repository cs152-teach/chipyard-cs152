/* stride_big -- a constant stride of 64 bytes (two lines), then the same walk
 * descending. */
#include "bench.h"

#define STRIDE_W 16     /* 16 words = 64 bytes = two 32-byte lines */
#define NSTEP    512
#define N        (STRIDE_W * NSTEP)     /* 8 K words = 32 KiB */
#define WORK     4                      /* ALU ops per access; see setStats block */

static unsigned int a[N];

int main(void)
{
  unsigned int i, w, acc = 0;
  int j;
  for (i = 0; i < N; i++) a[i] = i;

  setStats(1);
  for (i = 0; i < N; i += STRIDE_W) {                          /* ascending  */
    unsigned int t = a[i];
    for (w = 0; w < WORK; w++) t = (t ^ (t << 1)) + i;
    acc += t;
  }
  for (j = (int)N - STRIDE_W; j >= 0; j -= STRIDE_W) {         /* descending */
    unsigned int t = a[j];
    for (w = 0; w < WORK; w++) t = (t ^ (t << 1)) + (unsigned int)j;
    acc += t;
  }
  setStats(0);
  bench_report("stride_big", 2 * NSTEP, 2 * NSTEP);
  printf("  sink=%u\n", acc);
  return 0;
}
