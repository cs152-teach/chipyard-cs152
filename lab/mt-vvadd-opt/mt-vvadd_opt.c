#include "mt-vvadd.h"

// z = x + y, element-wise:
//   z[i] = x[i] + y[i]  for 0 <= i < n
// Every core calls this function; together they must compute all of z.
void vvadd_opt(int coreid, int ncores, size_t n, const data_t* x, const data_t* y, data_t* z)
{
    /* TODO */
}
