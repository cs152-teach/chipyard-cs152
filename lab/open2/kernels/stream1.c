/* stream1 -- one unit-stride read stream */
#include "bench.h"

#define N 4096          /* 16 KiB = 4x the cache, so nothing is reused */
#define WPL 8

static unsigned int a[N];

int main(void)
{
  unsigned int i, acc = 0;
  for (i = 0; i < N; i++) a[i] = i;

  setStats(1);
  for (i = 0; i < N; i++) {
    unsigned int t = a[i];
    t = (t ^ (t << 1)) + i;
    acc += t;
  }
  setStats(0);
  bench_report("stream1", N, N / WPL);
  printf("  sink=%u\n", acc);
  return 0;
}
