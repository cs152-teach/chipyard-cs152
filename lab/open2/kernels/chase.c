/* chase -- a cyclic pointer chase with a stride past the line size.
 *
 * Nothing predicts this, and that is the test.  The right answer is TO NOT
 * PREFETCH: every request is a wrong guess that evicts a live line, so an
 * aggressive model must lose here.  If a model that prefetches on every access
 * does NOT lose on this kernel, the accuracy lesson has no teeth and the
 * hardware needs a bandwidth cost added.
 *
 * The chase is also dependent -- the address of the next load is not known
 * until the current one returns -- so the core cannot hide any of the latency
 * itself.  That makes the miss stall the whole run and any eviction expensive. */
#include "bench.h"

/* ONE lap.  Four laps would make the
   sequence REPEAT, and a temporal (Markov) model would then predict laps 2-4
   perfectly -- which is a real and interesting mechanism, but it is what
   `temporal.c` is for.  Here the sequence is seen exactly once, so no mechanism
   of any kind can predict it and the only right answer is to issue nothing. */
#define NODES   384
/* 256 bytes: ONE NODE PER 256-BYTE REGION, and that is the point.
   With nodes packed closer than a region, a footprint/spatial prefetcher learns
   the other nodes sharing each region and extracts real coverage even though the
   ORDER is random.  That is a true property of a chase over a densely packed
   arena, but it stops this kernel being the "nothing can predict it" case.
   One node per
   region leaves a footprint with a single entry, so there is nothing to learn by
   any mechanism. */
#define STRIDE  64
#define N       (NODES * STRIDE)

static unsigned int buf[N];
static unsigned int order[NODES];

int main(void)
{
  unsigned int i, cur, acc = 0;

  /* Build the ring in a RANDOMISED order.
     Walking `cur + STRIDE` would be a constant stride and therefore trivially
     predictable: it would test dependent-load latency but not unpredictability.
     Here the
     visiting order is a shuffled permutation of the nodes, so the next address
     genuinely cannot be computed from the current one.
     A 32-bit LCG is plenty and keeps the kernel self-contained. */
  bench_shuffle(order, NODES, 22695477u);
  bench_build_ring(buf, order, NODES, STRIDE);

  setStats(1);
  /* Start at word 0.  Node 0 is one of the shuffled nodes and every node is on
     the single cycle, so entering the ring here traverses all of it. */
  cur = 0;
  for (i = 0; i < NODES; i++) cur = buf[cur];       /* exactly one lap */
  setStats(0);
  acc = cur;
  /* Every node is its own line, and at 64 KiB the working set is 16x the
     4 KiB cache, so nothing is reused even within the single lap. */
  bench_report("chase", NODES, NODES);
  printf("  sink=%u\n", acc);
  return 0;
}
