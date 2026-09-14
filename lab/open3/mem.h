/* CS152 Lab 2, 2.OE.3 -- how the kernels reach the matrices.
 *
 * Every matrix access in kernels.c goes through ld_A() and st_B().  On the
 * RISC-V build these inline to a plain array access and cost nothing.  On the
 * native cache-model build they are the hook the model watches.
 *
 * This is the one constraint the model puts on your code: reach the matrices
 * through ld_A/st_B, not through raw pointers.  A pointer walk the model
 * cannot see will silently under-count your misses.
 */
#ifndef CS152_MEM_H
#define CS152_MEM_H

#include <stdint.h>

#ifndef N
#define N 128
#endif
#ifndef TILE
#define TILE 16
#endif
#ifndef CO_BASE
#define CO_BASE 8
#endif

/* Alignment given to A and B in the RISC-V build.  It only has to be a
   multiple of the largest line size: the model synthesises addresses as
   base + 4*idx, so a base sitting partway into a line makes every element
   straddle lines differently than the model assumes -- the fault when .bss
   put them at 0x...378.  Nothing stronger is needed, because shifting every
   address by a whole number of lines only permutes set labels and leaves miss
   counts identical, so absolute position and A/B order do not matter.  64 B
   would do; 8192 is margin, at a cost of ~7 KB of .bss. */
#define MATRIX_ALIGN 8192
#define B_POISON     ((int32_t)0xDEADBEEF) // reset value before each kernel, so a kernel that does no work fails the check

#ifdef CACHE_MODEL
int32_t ld_A(int idx);
void    st_B(int idx, int32_t v);
#else
extern int32_t A[N * N];
extern int32_t B[N * N];
static inline int32_t ld_A(int idx)          { return A[idx]; }
static inline void    st_B(int idx, int32_t v) { B[idx] = v; }
#endif

void transpose_naive(void);
void transpose_blocked(void);
void transpose_oblivious(void);

#endif
