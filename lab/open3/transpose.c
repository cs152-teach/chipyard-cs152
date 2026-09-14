/* CS152 Lab 2, 2.OE.3 -- RISC-V harness.
 *
 * Runs each kernel from a cold cache and reports the L1D counters for that
 * kernel alone.  Correctness is checked outside the measured region: a kernel
 * that fails the check has no valid miss count.
 *
 * The kernels themselves are in kernels.c, shared with the native model.
 *
 * Build:  make
 * Run:    cd ${SIMDIR}
 *         make CONFIG=<budget config> run-binary-fast \
 *              BINARY=${TESTDIR}/open3/transpose.riscv
 */
#include <stdint.h>
#include "cs152_counters.h"
#include "mem.h"

extern int printf(const char *, ...);

int32_t A[N * N] __attribute__((aligned(MATRIX_ALIGN)));
int32_t B[N * N] __attribute__((aligned(MATRIX_ALIGN)));

static volatile int32_t sink;

/* Fills A only.  B is set by poison_B() before every kernel. */
static void fill(void)
{
  uint32_t s = 0x152Cu;
  for (int k = 0; k < N * N; k++) {
    s ^= s << 13; s ^= s >> 17; s ^= s << 5;
    A[k] = (int32_t)s;
  }
}

static void poison_B(void)
{
  for (int k = 0; k < N * N; k++) B[k] = B_POISON;
}

static int check(void)
{
  for (int i = 0; i < N; i++)
    for (int j = 0; j < N; j++)
      if (B[j * N + i] != A[i * N + j]) return 0;
  return 1;
}

/* A flush is asynchronous.  Touching cacheable memory cannot be served until
   the walk finishes, so this read is the wait. */
static void flush_and_wait(void)
{
  cs152_ctr_wr(CS152_CTR_CONTROL, CS152_CTL_FLUSH);
  sink = A[0];
}

static int run(const char *name, void (*kernel)(void))
{
  poison_B();
  flush_and_wait();
  cs152_ctr_wr(CS152_CTR_CONTROL, CS152_CTL_ZERO);
  kernel();
  cs152_ctr_wr(CS152_CTR_CONTROL, CS152_CTL_STOP);

  printf("%-10s loads %7u  stores %7u  hits %7u  misses %7u  wb %7u  cycles %9u\n",
         name,
         cs152_ctr_rd(CS152_CTR_LOADS),   cs152_ctr_rd(CS152_CTR_STORES),
         cs152_ctr_rd(CS152_CTR_HITS),    cs152_ctr_rd(CS152_CTR_MISSES),
         cs152_ctr_rd(CS152_CTR_WRITEBACKS), cs152_ctr_rd(CS152_CTR_CYCLES));

  if (!check()) { printf("           FAILED -- this miss count does not count\n"); return 1; }
  return 0;
}

int main(void)
{
  unsigned int magic = cs152_ctr_rd(CS152_CTR_MAGIC);
  if (magic != CS152_CTR_MAGIC_VALUE) {
    printf("counter block not found (read %x)\n", magic);
    return 1;
  }

  printf("transpose: N=%d TILE=%d CO_BASE=%d\n", N, TILE, CO_BASE);
  fill();

  int bad = 0;
  bad |= run("naive",     transpose_naive);
  bad |= run("blocked",   transpose_blocked);
  bad |= run("oblivious", transpose_oblivious);
  return bad;
}
