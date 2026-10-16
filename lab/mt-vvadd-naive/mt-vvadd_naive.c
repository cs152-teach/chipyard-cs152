#include "mt-vvadd.h"

// z = x + y, element-wise:
//   z[i] = x[i] + y[i]  for 0 <= i < n
void vvadd_naive(int coreid, int ncores, size_t n, const data_t* x, const data_t* y, data_t* z)
{
    size_t i;
    // Interleave elements: core c computes i = c, c + ncores, c + 2*ncores, ...
    for (i = coreid; i < n; i += ncores) {
        z[i] = x[i] + y[i];
    }
}
