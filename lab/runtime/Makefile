########################################################################
# CS152 Lab 2 -- runtime directory
#
#   make KERNEL=my_probe.c    build my_probe.riscv against the runtime
#   make clean                remove build outputs
#
# The runtime is not a standalone program; it is linked into a kernel.
# A problem directory includes runtime.mk rather than using this Makefile.
#
# Provenance.  Four files here are unmodified copies of upstream, taken
# from ucb-bar/esp-tests branch lab2 @ d8fe3bc:
#
#   crt.S util.h    <- benchmarks/common/
#   link.ld         <- benchmarks/common/test.ld
#   encoding.h      <- env/            (a nested submodule of that repo)
#
# To re-sync them, fetch that submodule and copy the files above.  The
# other three are ours: syscalls.c is upstream patched for the CS152 MMIO
# counters, and softmul.c and cs152_counters.h are written for this lab.
########################################################################

RUNTIME_DIR = .
include runtime.mk

.PHONY: all
ifeq ($(KERNEL),)
all:
	@echo "The runtime is a library, not a program."
	@echo "Usage: make KERNEL=my_probe.c   ->  my_probe.riscv"
	@exit 1
else
TARGET = $(basename $(notdir $(KERNEL))).riscv
all: $(TARGET)
$(TARGET): $(KERNEL) $(RUNTIME_DEPS)
	$(BUILD_KERNEL)
endif

.PHONY: clean
clean:
	rm -f *.riscv *.dump

.SUFFIXES:
