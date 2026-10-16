#include "mt-matmul.h"

// C = A (.) B, a matrix product with AND in place of multiply:
//   C[i][j] = sum over k of (A[i][k] & B[k][j])
// Every core calls this function; together they must compute all of C.
void matmul_opt(unsigned int coreid, unsigned int ncores, const size_t lda,  const data_t* A, const data_t* B, data_t* C)
{
    /* TODO */
}
