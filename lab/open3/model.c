/* CS152 Lab 2, 2.OE.3 -- native cache model.
 *
 * Counts exactly the hits, misses and writebacks the RTL L1D would report for
 * the same kernel and the same geometry.  It models no timing at all, which is
 * why it is thousands of times faster than the simulator and still gives the
 * same miss count: a miss depends only on the address stream and the geometry.
 *
 * Replacement matches L1DCache.scala: an invalid way is taken first (lowest
 * index), otherwise the way whose age is ways-1, and every access ages the set
 * the same way `touch()` does in the RTL.  Write-allocate, write-back.
 *
 * Build:  make model
 * Run:    ./model --sets 64 --ways 2 --line 32
 *         ./model --sweep            (every legal geometry, CSV)
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

#include "mem.h"   /* CACHE_MODEL comes from the Makefile */

static int SETS = 64, WAYS = 2, LINE = 32;

#define ALIGN_UP(x, a) (((x) + (a) - 1u) / (a) * (a))
static const uint32_t A_BASE = 0x80000000u;
static const uint32_t B_BASE = 0x80000000u + ALIGN_UP((uint32_t)(N * N * 4), MATRIX_ALIGN);

static int32_t Adata[N * N];
static int32_t Bdata[N * N];

typedef struct { uint32_t tag; int valid, dirty, age; } line_t;
static line_t *L;   /* SETS * WAYS */

static long hits, misses, wbs, loads, stores;

static line_t *W(int set, int way) { return &L[set * WAYS + way]; }

static void cache_reset(void)
{
  for (int s = 0; s < SETS; s++)
    for (int w = 0; w < WAYS; w++) {
      line_t *l = W(s, w);
      l->tag = 0; l->valid = 0; l->dirty = 0;
      l->age = w;          /* Seq.tabulate(ways)(i => i.U) in the RTL */
    }
  hits = misses = wbs = loads = stores = 0;
}

/* The RTL's touch(): the touched way becomes age 0, and every way that was
   more recent than it ages by one. */
static void touch(int set, int way)
{
  int cur = W(set, way)->age;
  for (int w = 0; w < WAYS; w++) {
    if (w == way)                    W(set, w)->age = 0;
    else if (W(set, w)->age < cur)   W(set, w)->age += 1;
  }
}

static void access(uint32_t addr, int is_store)
{
  uint32_t block = addr / (uint32_t)LINE;
  int set        = (int)(block % (uint32_t)SETS);
  uint32_t tag   = block / (uint32_t)SETS;

  if (is_store) stores++; else loads++;

  for (int w = 0; w < WAYS; w++) {
    line_t *l = W(set, w);
    if (l->valid && l->tag == tag) {
      hits++;
      touch(set, w);
      if (is_store) l->dirty = 1;
      return;
    }
  }

  misses++;

  int victim = -1;
  for (int w = 0; w < WAYS; w++)               /* first invalid way */
    if (!W(set, w)->valid) { victim = w; break; }
  if (victim < 0)
    for (int w = 0; w < WAYS; w++)             /* else the oldest */
      if (W(set, w)->age == WAYS - 1) { victim = w; break; }

  line_t *v = W(set, victim);
  if (v->valid && v->dirty) wbs++;

  /* The fill installs the line clean; the access that caused the miss then
     dirties it if it was a store.  Same net effect as the RTL. */
  v->tag = tag; v->valid = 1; v->dirty = is_store;
  touch(set, victim);
}

int32_t ld_A(int idx)            { access(A_BASE + 4u * (uint32_t)idx, 0); return Adata[idx]; }
void    st_B(int idx, int32_t v) { access(B_BASE + 4u * (uint32_t)idx, 1); Bdata[idx] = v; }

/* ------------------------------------------------------------- harness */

/* Not routed through the model: the RTL zeroes its counters after the fill. */
static void fill(void)
{
  uint32_t s = 0x152Cu;
  for (int k = 0; k < N * N; k++) {
    s ^= s << 13; s ^= s >> 17; s ^= s << 5;
    Adata[k] = (int32_t)s;
  }
}

/* See transpose.c.  Not routed through access(), the same way fill() is not. */
static void poison_B(void)
{
  for (int k = 0; k < N * N; k++) Bdata[k] = B_POISON;
}

static int check(void)
{
  for (int i = 0; i < N; i++)
    for (int j = 0; j < N; j++)
      if (Bdata[j * N + i] != Adata[i * N + j]) return 0;
  return 1;
}

static int run(const char *name, void (*kernel)(void), int csv)
{
  poison_B();
  cache_reset();
  kernel();
  int ok = check();
  if (csv)
    printf("%d,%d,%d,%d,%d,%s,%ld,%ld,%ld,%s\n",
           N, TILE, SETS, WAYS, LINE, name, hits, misses, wbs,
           ok ? "ok" : "FAILED");
  else
    printf("%-10s loads %7ld  stores %7ld  hits %7ld  misses %7ld  wb %7ld  %s\n",
           name, loads, stores, hits, misses, wbs, ok ? "" : " FAILED");
  return ok ? 0 : 1;
}

static int alloc_cache(void)
{
  if (SETS <= 0 || WAYS <= 0 || LINE < 4) return 0;
  if ((SETS & (SETS - 1)) || (WAYS & (WAYS - 1)) || (LINE & (LINE - 1))) {
    fprintf(stderr, "sets, ways and line must be powers of two\n");
    return 0;
  }
  free(L);
  L = calloc((size_t)SETS * WAYS, sizeof(line_t));
  return L != NULL;
}

static int run_all(int csv)
{
  int bad = 0;
  bad |= run("naive",     transpose_naive,     csv);
  bad |= run("blocked",   transpose_blocked,   csv);
  bad |= run("oblivious", transpose_oblivious, csv);
  return bad;
}

int main(int argc, char **argv)
{
  int csv = 0, sweep = 0;

  for (int i = 1; i < argc; i++) {
    if      (!strcmp(argv[i], "--sets")  && i + 1 < argc) SETS = atoi(argv[++i]);
    else if (!strcmp(argv[i], "--ways")  && i + 1 < argc) WAYS = atoi(argv[++i]);
    else if (!strcmp(argv[i], "--line")  && i + 1 < argc) LINE = atoi(argv[++i]);
    else if (!strcmp(argv[i], "--sweep")) { sweep = 1; csv = 1; }
    else { fprintf(stderr, "unknown option %s\n", argv[i]); return 2; }
  }

  fill();

  if (!sweep) {
    if (!alloc_cache()) return 2;
    if (!csv)
      printf("model: N=%d TILE=%d  cache %d sets x %d ways x %dB = %d B\n",
             N, TILE, SETS, WAYS, LINE, SETS * WAYS * LINE);
    else
      printf("n,tile,sets,ways,line,kernel,hits,misses,writebacks,status\n");
    return run_all(csv);
  }

  int bad = 0;
  printf("n,tile,sets,ways,line,kernel,hits,misses,writebacks,status\n");
  int caps[]  = {1024, 2048, 4096, MATRIX_ALIGN};
  int wayv[]  = {1, 2, 4, 8};
  int linev[] = {16, 32, 64};
  for (unsigned c = 0; c < sizeof caps / sizeof *caps; c++)
    for (unsigned w = 0; w < sizeof wayv / sizeof *wayv; w++)
      for (unsigned l = 0; l < sizeof linev / sizeof *linev; l++) {
        int sets = caps[c] / (wayv[w] * linev[l]);
        if (sets < 1) continue;
        SETS = sets; WAYS = wayv[w]; LINE = linev[l];
        if (!alloc_cache()) continue;
        bad |= run_all(1);
      }
  return bad;
}
