########################################################################
# CS152 Lab 2 -- Open-Ended Problem 3 (cache / kernel co-design)
#
#   make                 build transpose.riscv for the simulator
#   make model           build ./model, the native cache model
#   make TILE=32         rebuild both with a different tile size
#   make clean
#
# The fast loop is the model.  Use it to search tile sizes and cache
# geometries; miss counts from it are exact.  Confirm your final choice for
# each budget on the RTL:
#
#   cd $(SIMDIR)
#   make CONFIG=<budget config> run-binary-fast \
#        BINARY=$(TESTDIR)/open3/transpose.riscv
#
# Only TILE is yours to tune.  N is fixed by the problem statement.
########################################################################

RUNTIME_DIR = ../runtime
include $(RUNTIME_DIR)/runtime.mk

N       ?= 64
TILE    ?= 16
CO_BASE ?= 8
DIMS     = -DN=$(N) -DTILE=$(TILE) -DCO_BASE=$(CO_BASE)

CFLAGS  += $(DIMS)

HOSTCC      ?= cc
HOSTCFLAGS  ?= -O2 -std=gnu99 -Wall -I.

.PHONY: all
all: transpose.riscv

# -lgcc for the same reason runtime.mk names it: Sodor has no multiplier, so
# printf and setStats pull in the libgcc soft div/mod routines, and -nostdlib
# drops libgcc along with libc.
transpose.riscv: transpose.c kernels.c mem.h $(RUNTIME_DEPS)
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(RUNTIME_SRCS) transpose.c kernels.c -lgcc

.PHONY: model
model: model.c kernels.c mem.h
	$(HOSTCC) $(HOSTCFLAGS) -DCACHE_MODEL $(DIMS) -o model model.c kernels.c

.PHONY: clean
clean:
	rm -f *.riscv *.dump model

.SUFFIXES:
