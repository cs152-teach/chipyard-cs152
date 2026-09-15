//**************************************************************************
// CS152 Lab 2 -- Harness for probing a mystery memory subsystem
//--------------------------------------------------------------------------

#include "util.h"
#include "cs152_counters.h"

/* Data memory is 256 KiB at 0x80000000.  The program ends well below
   0x80010000, so everything above that is free scratch that costs nothing to
   load.  128 KiB of it is available from BUF. */
#define BUF   ((volatile unsigned int *)0x80010000u)
#define BUF_BYTES (128 * 1024)

/* A cacheable location used to wait for a flush to finish */
static volatile unsigned int sink;

//--------------------------------------------------------------------------
// harness

/* Write back and invalidate every line, then wait for the walk to finish.
   The load cannot be served until the cache is idle again, so it is the wait. */
static void flush(void)
{
  cs152_ctr_wr(CS152_CTR_CONTROL, CS152_CTL_FLUSH);
  (void)sink;
}

/* Lay out a cyclic pointer chase of `n` nodes, `strideBytes` apart, at BUF.
   Node k lives at byte offset k*strideBytes and holds the WORD index of the
   next node; the last node points back at the first. */
static void build_chase(unsigned int n, unsigned int strideBytes)
{
  unsigned int step = strideBytes >> 2;
  unsigned int i, cur = 0, next;

  /* The ring has to fit in BUF.  Past the end of the cacheable range the
     stores leave the tile and trip a TileLink monitor assertion deep inside
     Sodor, which says nothing about the call that caused it -- so check here
     and name the numbers instead. */
  if (strideBytes < 4 || (strideBytes & (strideBytes - 1)) != 0) {
    printf("build_chase: stride %u B must be a power of two, at least 4\n",
           strideBytes);
    exit(1);
  }
  if (n == 0 || n > BUF_BYTES / strideBytes) {
    printf("build_chase: %u nodes x %u B = %u KiB does not fit the %u KiB buffer"
           " (max %u nodes at this stride)\n",
           n, strideBytes, (n * strideBytes) >> 10, (unsigned)(BUF_BYTES >> 10),
           (unsigned)(BUF_BYTES / strideBytes));
    exit(1);
  }
  for (i = 0; i < n; i++) {
    next = cur + step;
    if (i == n - 1) next = 0;
    BUF[cur] = next;
    cur = next;
  }
}

/* Walk the chase built by build_chase(): `warm` untimed accesses, then `reps`
   timed ones.  Returns the cycles of the timed part.  With `cold` non-zero the
   cache is flushed first, so the timed pass starts from an empty cache.

   One dependent load per access: the address of the next load is not known
   until the previous one returns, so the cache can never overlap two of
   them, and the cycle count is the sum of the individual access costs. */
static unsigned int chase(unsigned int reps, unsigned int warm, int cold)
{
  unsigned int i = 0, r, c;
  if (cold) flush();
  for (r = warm; r != 0; r--) i = BUF[i];
  cs152_ctr_wr(CS152_CTR_CONTROL, CS152_CTL_ZERO);
  for (r = reps; r != 0; r--) i = BUF[i];
  cs152_ctr_wr(CS152_CTR_CONTROL, CS152_CTL_STOP);
  c = cs152_ctr_rd(CS152_CTR_CYCLES);
  sink = i;                    /* keep the chase from being optimised away */
  return c;
}

//--------------------------------------------------------------------------
// results: record now, print once at the end

struct row { const char *tag; unsigned int p1, p2, cyc, reps; };
#define ROWS      ((struct row *)0x80008000u)
#define MAX_ROWS  256

static unsigned int nrows, ndropped;

/* `tag`, `p1` and `p2` are yours to use: label the experiment and record
   whichever two parameters it varied. */
static void record(const char *tag, unsigned int p1, unsigned int p2,
                   unsigned int cycles, unsigned int reps)
{
  if (nrows >= MAX_ROWS) { ndropped++; return; }
  ROWS[nrows].tag = tag;   ROWS[nrows].p1  = p1;  ROWS[nrows].p2   = p2;
  ROWS[nrows].cyc = cycles; ROWS[nrows].reps = reps;
  nrows++;
}

static void report(void)
{
  unsigned int k;
  printf("tag        p1     p2   accesses    cycles   cyc/access\n");
  for (k = 0; k < nrows; k++) {
    unsigned int cpa100 = (ROWS[k].cyc * 100u) / ROWS[k].reps;
    printf("%-6s %6d %6d %10d %9d   %3d.%02d\n",
           ROWS[k].tag, ROWS[k].p1, ROWS[k].p2, ROWS[k].reps, ROWS[k].cyc,
           cpa100 / 100u, cpa100 % 100u);
  }
  if (ndropped)
    printf("*** %d rows DROPPED: raise MAX_ROWS and run again ***\n", ndropped);
}

//--------------------------------------------------------------------------

int main(void)
{
  if (cs152_ctr_rd(CS152_CTR_MAGIC) == CS152_CTR_MAGIC_MYSTERY)
    printf("mystery build: cycles are the only counter.\n");

  /* Harness check, not an experiment: 64 accesses around a 4-node ring, warm.
     It shows how build_chase(), chase() and record() fit together, and that
     your toolchain and the simulator agree.  Delete it and write your own. */
  build_chase(4, 64);
  record("smoke", 64, 4, chase(64, 4, 0), 64);

  /* Your experiments go here. */

  report();
  exit(0);
}
