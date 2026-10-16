#!/usr/bin/env python3
# Regenerate dataset.h:  ./mt-matmul_gendata.py > dataset.h
#
# Computes C[i][j] = sum over k of (A[i][k] & B[k][j]): a matrix product with
# AND in place of multiply, because Sodor (RV32I) has no hardware multiplier.
# Elements are ints.  The random seed is fixed, so the dataset is
# reproducible.  Every array is aligned to a cache line.

import random

size = 32
random.seed(152)

A = [[random.randrange(100) for _ in range(size)] for _ in range(size)]
B = [[random.randrange(100) for _ in range(size)] for _ in range(size)]
C = [[sum(A[i][k] & B[k][j] for k in range(size)) for j in range(size)] for i in range(size)]

def flat(m):
    return [v for row in m for v in row]

def print_array(name, data, data_size, data_type='data_t', fold=10):
    print('static {} {}[{}] __attribute__((aligned(CS152_LINE_BYTES))) = {{'.format(data_type, name, data_size))
    for i in range(0, len(data), fold):
        print('  ', ', '.join(str(v) for v in data[i:i+fold]), ',', sep='')
    print('};')

print('''\
#ifndef _DATASET_H
#define _DATASET_H

#include "cs152_counters.h"   /* CS152_LINE_BYTES */
''')
print('#define DIM_SIZE {}'.format(size))
print('''#define ARRAY_SIZE (DIM_SIZE * DIM_SIZE)

typedef int data_t;

#ifdef __GNUC__
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wunused-variable"
#endif
''')

print_array('input1_data', flat(A), 'ARRAY_SIZE')
print_array('input2_data', flat(B), 'ARRAY_SIZE')
print_array('verify_data', flat(C), 'ARRAY_SIZE')

print('''
#ifdef __GNUC__
#pragma GCC diagnostic pop
#endif

#endif /* _DATASET_H */''')
