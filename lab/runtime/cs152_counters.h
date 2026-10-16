#ifndef CS152_COUNTERS_H
#define CS152_COUNTERS_H

/* Must match CS152CounterBase in CS152Params.scala; CS152_CTR_MAGIC reads
   back CS152_CTR_MAGIC_VALUE when they agree.  (0x10000000 is avoided because
   spike maps a byte-only UART there.)

   Every core has its own counter block at this address, and sees only its
   own L1D's events. */
#define CS152_CTR_BASE 0x20000000

/* The L1D line size.  Must match lineBytes in CS152CacheParams (CS152Params.scala).
   The benchmarks start every array on a line with
   __attribute__((aligned(CS152_LINE_BYTES))). */
#define CS152_LINE_BYTES 32

/* CS152SystemParams caps the hardware at 4 cores. */
#define CS152_MAX_CORES 4

#define CS152_CTR_CYCLES     0x00
#define CS152_CTR_LOADS      0x04
#define CS152_CTR_STORES     0x08
#define CS152_CTR_HITS       0x0c   /* completed without the bus            */
#define CS152_CTR_MISSES     0x10   /* GetS + GetM issued                   */
#define CS152_CTR_WRITEBACKS 0x14   /* dirty lines written back             */
#define CS152_CTR_UPGRADES   0x18   /* store to a line held in S            */
#define CS152_CTR_CONTROL    0x1c   /* write-only                           */
#define CS152_CTR_INVALS     0x20   /* lines taken away by another core     */
#define CS152_CTR_INTERVENE  0x24   /* dirty lines supplied to another core */
#define CS152_CTR_MAGIC      0x28   /* reads CS152_CTR_MAGIC_VALUE          */
#define CS152_CTR_NCORES     0x2c   /* number of cores; crt.S reads it      */
#define CS152_CTR_BUSWAIT    0x30   /* cycles spent waiting for the bus     */
#define CS152_CTR_LOADMISS   0x34   /* misses caused by a load              */
#define CS152_CTR_STOREMISS  0x38   /* misses caused by a store             */

/* Must match CacheCounters.magic in CacheCounters.scala.  crt.S includes
   this header, so all constants here must be assembler-safe (no integer
   suffixes). */
#define CS152_CTR_MAGIC_VALUE 0xC5152004

/* CS152_CTR_CONTROL write bits.  ZERO starts a region, STOP ends one.
   All of them act on the calling core's own L1D and counters only. */
#define CS152_CTL_ZERO   1    /* zero every counter, and resume counting   */
#define CS152_CTL_FLUSH  2    /* write back dirty lines, then invalidate   */
#define CS152_CTL_CLEAN  4    /* write back dirty lines, keep them resident*/
#define CS152_CTL_STOP   8    /* freeze every counter                      */

#ifndef __ASSEMBLER__

static inline unsigned int cs152_ctr_rd(unsigned int off)
{
  return *(volatile unsigned int *)(CS152_CTR_BASE + off);
}

static inline void cs152_ctr_wr(unsigned int off, unsigned int val)
{
  *(volatile unsigned int *)(CS152_CTR_BASE + off) = val;
}

#endif /* __ASSEMBLER__ */
#endif /* CS152_COUNTERS_H */
