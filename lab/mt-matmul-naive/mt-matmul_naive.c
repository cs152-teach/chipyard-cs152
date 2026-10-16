#include "mt-matmul.h"

// C = A (.) B, a matrix product with AND in place of multiply:
//   C[i][j] = sum over k of (A[i][k] & B[k][j])
void matmul_naive(unsigned int coreid, unsigned int ncores, const size_t lda,  const data_t* A, const data_t* B, data_t* C)
{
    size_t i, j, k;

    for (i = 0; i < lda; i++) {
        // Interleave columns: core c computes j = c, c + ncores, c + 2*ncores, ...
        for (j = coreid; j < lda; j += ncores) {
            C[i*lda + j] = 0;
            for (k = 0; k < lda; k++) {
                C[i*lda + j] += A[i*lda + k] & B[k*lda + j];
            }
        }
    }
}
