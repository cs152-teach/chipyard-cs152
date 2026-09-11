/* temporal -- an unpredictable sequence, walked repeatedly. */
#include "bench.h"

#define NODES   1024
#define STRIDE  16      /* 64 bytes: two lines apart, so no spatial locality */
#define N       (NODES * STRIDE)
#define LAPS    4

static unsigned int buf[N];
static unsigned int order[NODES];

int main(void)
{
  unsigned int i, lap, cur, acc = 0;
  unsigned int rng = 747796405u;        /* a different seed from chase */

  bench_shuffle(order, NODES, rng);
  bench_build_ring(buf, order, NODES, STRIDE);

  setStats(1);
  cur = 0;
  for (lap = 0; lap < LAPS; lap++)
    for (i = 0; i < NODES; i++) cur = buf[cur];
  setStats(0);
  acc = cur;
  bench_report("temporal", NODES * LAPS, NODES * LAPS);
  printf("  sink=%u\n", acc);
  return 0;
}
