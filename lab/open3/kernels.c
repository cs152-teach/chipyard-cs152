/* CS152 Lab 2, 2.OE.3 -- the kernels.
 *
 * This is the file you edit.  It is compiled twice from the same source: once
 * for the RISC-V simulator and once for the native cache model, so a change
 * here shows up in both.
 *
 *   transpose_naive      the obvious loop.  Do not change it; it is the
 *                        reference point your speedup is measured against.
 *   transpose_blocked    yours to tune.  TILE comes from the Makefile.
 *   transpose_oblivious  the cache-oblivious reference.  It is told nothing
 *                        about the cache.  Do not change it.
 */
#include "mem.h"

/* Reads A along rows, writes B down columns: every store lands on a
   different line once N * lineBytes exceeds the capacity. */
void transpose_naive(void)
{
  for (int i = 0; i < N; i++)
    for (int j = 0; j < N; j++)
      st_B(j * N + i, ld_A(i * N + j));
}

/* Same work, TILE x TILE at a time. */
void transpose_blocked(void)
{
  for (int ii = 0; ii < N; ii += TILE)
    for (int jj = 0; jj < N; jj += TILE)
      for (int i = ii; i < ii + TILE; i++)
        for (int j = jj; j < jj + TILE; j++)
          st_B(j * N + i, ld_A(i * N + j));
}

/* Split the longer side and recurse.  No cache parameter appears anywhere:
   the same code is near-optimal at every capacity. */
static void co_rec(int r0, int r1, int c0, int c1)
{
  int nr = r1 - r0;
  int nc = c1 - c0;

  if (nr <= CO_BASE && nc <= CO_BASE) {
    for (int i = r0; i < r1; i++)
      for (int j = c0; j < c1; j++)
        st_B(j * N + i, ld_A(i * N + j));
    return;
  }

  if (nr >= nc) {
    int m = (r0 + r1) >> 1;
    co_rec(r0, m, c0, c1);
    co_rec(m, r1, c0, c1);
  } else {
    int m = (c0 + c1) >> 1;
    co_rec(r0, r1, c0, m);
    co_rec(r0, r1, m, c1);
  }
}

void transpose_oblivious(void) { co_rec(0, N, 0, N); }
