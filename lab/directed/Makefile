########################################################################
# CS152 Lab 2 -- Directed Portion
#
#   make            build rsort.riscv
#   make clean      remove build outputs
#
# Then run it:
#   cd $(CHIPYARD_ROOT)/sims/verilator
#   make CONFIG=CS152Lab2Config run-binary-fast BINARY=path-to/rsort.riscv
#
# The bare-metal runtime -- crt.S, syscalls.c, softmul.c, link.ld and the
# shared headers -- lives in ../runtime and is shared with the open-ended
# problems.  See ../runtime/Makefile for its provenance.
#
# rsort.c and dataset1.h here are unmodified copies of upstream, taken from
# ucb-bar/esp-tests branch lab2 @ d8fe3bc, benchmarks/rsort/.
########################################################################

RUNTIME_DIR = ../runtime
include $(RUNTIME_DIR)/runtime.mk

.PHONY: all
all: rsort.riscv

rsort.riscv: rsort.c dataset1.h $(RUNTIME_DEPS)
	$(BUILD_KERNEL)

.PHONY: clean
clean:
	rm -f *.riscv *.dump

.SUFFIXES:
