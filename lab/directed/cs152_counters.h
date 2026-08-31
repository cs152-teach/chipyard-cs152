#ifndef CS152_COUNTERS_H
#define CS152_COUNTERS_H

#define CS152_CTR_BASE 0x10000000

#define CS152_CTR_CYCLES     0x00
#define CS152_CTR_LOADS      0x04
#define CS152_CTR_STORES     0x08
#define CS152_CTR_HITS       0x0c
#define CS152_CTR_MISSES     0x10
#define CS152_CTR_WRITEBACKS 0x14
#define CS152_CTR_INSTRET    0x18   /* not implemented on Sodor; reads 0 */
#define CS152_CTR_CONTROL    0x1c
#define CS152_CTR_SNOOP_ACC  0x20
#define CS152_CTR_SNOOP_HIT  0x24

/* CS152_CTR_CONTROL write bits */
#define CS152_CTL_ZERO   1u   /* zero every counter                        */
#define CS152_CTL_FLUSH  2u   /* write back dirty lines, then invalidate   */
#define CS152_CTL_CLEAN  4u   /* write back dirty lines, keep them resident*/

struct cs152_counters {
  unsigned int cycles, loads, stores, hits, misses, writebacks;
  unsigned int snoop_acc, snoop_hit;
};

static inline unsigned int cs152_ctr_rd(unsigned int off)
{
  return *(volatile unsigned int *)(CS152_CTR_BASE + off);
}

static inline void cs152_ctr_wr(unsigned int off, unsigned int val)
{
  *(volatile unsigned int *)(CS152_CTR_BASE + off) = val;
}

static inline void cs152_counters_read(struct cs152_counters *c)
{
  unsigned int cyc = cs152_ctr_rd(CS152_CTR_CYCLES);
  unsigned int ld  = cs152_ctr_rd(CS152_CTR_LOADS);
  unsigned int st  = cs152_ctr_rd(CS152_CTR_STORES);
  unsigned int hi  = cs152_ctr_rd(CS152_CTR_HITS);
  unsigned int mi  = cs152_ctr_rd(CS152_CTR_MISSES);
  unsigned int wb  = cs152_ctr_rd(CS152_CTR_WRITEBACKS);
  unsigned int sa  = cs152_ctr_rd(CS152_CTR_SNOOP_ACC);
  unsigned int sh  = cs152_ctr_rd(CS152_CTR_SNOOP_HIT);

  c->cycles = cyc; c->loads = ld; c->stores = st; c->hits = hi;
  c->misses = mi;  c->writebacks = wb; c->snoop_acc = sa; c->snoop_hit = sh;
}

static inline void cs152_counters_begin(volatile unsigned int *scratch)
{
  cs152_ctr_wr(CS152_CTR_CONTROL, CS152_CTL_FLUSH);
  (void)*scratch;                       /* stalls until the walk finishes */
  cs152_ctr_wr(CS152_CTR_CONTROL, CS152_CTL_ZERO);
}

static inline void cs152_counters_end(struct cs152_counters *c)
{
  cs152_counters_read(c);
}

#endif /* CS152_COUNTERS_H */
