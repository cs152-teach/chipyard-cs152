// See LICENSE for license details.

//**************************************************************************
// Multithreaded vector-vector add benchmark
//--------------------------------------------------------------------------
//
// Every core runs thread_entry(), with cid = its mhartid and nc = the number
// of cores.  Use cid to decide which part of the work is yours.

#include "util.h"
#include "mt-vvadd.h"

void thread_entry(int cid, int nc)
{
    static data_t output_data[DATA_SIZE] __attribute__((aligned(CS152_LINE_BYTES)));

    barrier(nc);
    setStats(1);                 // flush this core's L1D, start its counters
    barrier(nc);

    VVADD_FUNC(cid, nc, DATA_SIZE, input1_data, input2_data, output_data);

    barrier(nc);                 // the measured region ends when the last core finishes
    setStats(0);
    printStats(nc);

    if (cid == 0)
        finish(verify(DATA_SIZE, output_data, verify_data));
    for (;;);
}
