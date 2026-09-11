/* CS152 Lab 2, open-ended 2 -- shared harness for the kernel suite. */

#ifndef CS152_BENCH_H
#define CS152_BENCH_H

#include "util.h"
#include "cs152_counters.h"

#define RUNTIME_SLACK 4     /* generous upper bound on those stray accesses */

static void bench_report(const char *name,
                         unsigned int expect_loads,
                         unsigned int coverable)
{
  unsigned int cycles = cs152_ctr_rd(CS152_CTR_CYCLES);
  unsigned int loads  = cs152_ctr_rd(CS152_CTR_LOADS);
  unsigned int stores = cs152_ctr_rd(CS152_CTR_STORES);
  unsigned int hits   = cs152_ctr_rd(CS152_CTR_HITS);
  unsigned int misses = cs152_ctr_rd(CS152_CTR_MISSES);
  unsigned int acc    = loads + stores;
  int ok = 1;

  if (loads != expect_loads) {
    printf("  !! %s: loads %u, expected %u -- the access pattern is not what "
           "this kernel claims\n", name, loads, expect_loads);
    ok = 0;
  }
  if (stores > RUNTIME_SLACK) {
    printf("  !! %s: %u stores in a read-only kernel\n", name, stores);
    ok = 0;
  }
  if (hits + misses != acc) {
    printf("  !! %s: hits+misses %u != loads+stores %u -- counters disagree\n",
           name, hits + misses, acc);
    ok = 0;
  }

  printf("%s %s cycles=%u loads=%u stores=%u hits=%u misses=%u (compulsory ~%u)\n",
         ok ? "  ok" : "  BAD", name, cycles, loads, stores, hits, misses,
         coverable);
}

static void bench_shuffle(unsigned int *order, unsigned int n, unsigned int seed)
{
  unsigned int i, rng = seed;
  for (i = 0; i < n; i++) order[i] = i;
  for (i = n - 1; i > 0; i--) {
    unsigned int j, t;
    rng = rng * 1103515245u + 12345u;
    j = (rng >> 16) % (i + 1);
    t = order[i]; order[i] = order[j]; order[j] = t;
  }
}

static void bench_build_ring(unsigned int *buf, const unsigned int *order,
                             unsigned int n, unsigned int stride)
{
  unsigned int i;
  for (i = 0; i < n; i++)
    buf[order[i] * stride] = order[(i + 1) % n] * stride;
}

#endif /* CS152_BENCH_H */
