#!/usr/bin/env python3
# Regenerate dataset.h:  ./mt-vvadd_gendata.py > dataset.h
#
# Computes z[i] = x[i] + y[i].  Elements are ints because Sodor (RV32I) has
# no floating-point unit.  The random seed is fixed, so the dataset is
# reproducible.  Every array is aligned to a cache line.

import random

size = 1000
random.seed(152)

x = [random.randrange(100) for _ in range(size)]
y = [random.randrange(100) for _ in range(size)]
result = [a + b for a, b in zip(x, y)]

def print_array(name, data, data_type='data_t', fold=10):
    print('static {} {}[DATA_SIZE] __attribute__((aligned(CS152_LINE_BYTES))) = {{'.format(data_type, name))
    for i in range(0, len(data), fold):
        print('  ', ', '.join(str(v) for v in data[i:i+fold]), ',', sep='')
    print('};')

print('''\
#ifndef _DATASET_H
#define _DATASET_H

#include "cs152_counters.h"   /* CS152_LINE_BYTES */
''')
print('#define DATA_SIZE {}'.format(size))
print('''
typedef int data_t;

#ifdef __GNUC__
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-variable"
#endif
''')

print_array('input1_data', x)
print_array('input2_data', y)
print_array('verify_data', result)

print('''
#ifdef __GNUC__
#pragma GCC diagnostic pop
#endif

#endif /* _DATASET_H */''')
