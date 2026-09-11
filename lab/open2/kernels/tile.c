/* tile -- a blocked 2-D traversal: short unit-stride runs, then a jump. */
#include "bench.h"

#define ROWW  256       /* words per row = 1 KiB = 32 lines */
#define NROW  64        /* 64 KiB total */
#define TILEW 32        /* words per tile row = 4 lines */
#define WPL   8

static unsigned int a[ROWW * NROW];

int main(void)
{
  unsigned int r, t, i, acc = 0;
  unsigned int n = ROWW * NROW;
  for (i = 0; i < n; i++) a[i] = i;

  setStats(1);
  /* Walk tile-column by tile-column: TILEW words of one row, then jump a row. */
  for (t = 0; t < ROWW; t += TILEW)
    for (r = 0; r < NROW; r++)
      for (i = 0; i < TILEW; i++)
        acc += a[r * ROWW + t + i];
  setStats(0);
  bench_report("tile", n, n / WPL);
  printf("  sink=%u\n", acc);
  return 0;
}
