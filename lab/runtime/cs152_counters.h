#ifndef CS152_COUNTERS_H
#define CS152_COUNTERS_H

/* Must match CS152CounterBase in CS152Params.scala.  Deliberately not
   0x10000000: spike parks a byte-only ns16550 UART there, and a 32-bit read of
   it faults.  Read CS152_CTR_MAGIC to confirm the two agree. */
#define CS152_CTR_BASE 0x20000000

#define CS152_CTR_CYCLES     0x00
#define CS152_CTR_LOADS      0x04
#define CS152_CTR_STORES     0x08
#define CS152_CTR_HITS       0x0c
#define CS152_CTR_MISSES     0x10
#define CS152_CTR_WRITEBACKS 0x14
#define CS152_CTR_INSTRET    0x18   /* not implemented on Sodor; reads 0 */
#define CS152_CTR_CONTROL    0x1c   /* write-only */
#define CS152_CTR_SNOOP_ACC  0x20
#define CS152_CTR_SNOOP_HIT  0x24
#define CS152_CTR_MAGIC      0x28   /* reads CS152_CTR_MAGIC_VALUE */

/* Also defined as CacheCounters.magic in CacheCounters.scala. */
#define CS152_CTR_MAGIC_VALUE 0xC5152001u
#define CS152_CTR_MAGIC_MYSTERY 0xC5152002u

/* CS152_CTR_CONTROL write bits.  ZERO starts a region, STOP ends one. */
#define CS152_CTL_ZERO   1u   /* zero every counter, and resume counting   */
#define CS152_CTL_FLUSH  2u   /* write back dirty lines, then invalidate   */
#define CS152_CTL_CLEAN  4u   /* write back dirty lines, keep them resident*/
#define CS152_CTL_STOP   8u   /* freeze every counter                      */

static inline unsigned int cs152_ctr_rd(unsigned int off)
{
  return *(volatile unsigned int *)(CS152_CTR_BASE + off);
}

static inline void cs152_ctr_wr(unsigned int off, unsigned int val)
{
  *(volatile unsigned int *)(CS152_CTR_BASE + off) = val;
}

#endif /* CS152_COUNTERS_H */
