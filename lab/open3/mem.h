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
