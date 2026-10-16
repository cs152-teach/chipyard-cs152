########################################################################
# CS152 Lab 4 -- runtime directory
#
#   make KERNEL=my_probe.c    build my_probe.riscv against the runtime
#   make clean                remove build outputs
#
# The runtime is not a standalone program; it is linked into a kernel.
# A problem directory includes runtime.mk rather than using this Makefile.
#
# Provenance: four files come from ucb-bar/esp-tests branch lab2 @ d8fe3bc:
#
#   crt.S util.h    <- benchmarks/common/
#   link.ld         <- benchmarks/common/test.ld
#   encoding.h      <- env/            (a nested submodule of that repo)
#
# link.ld and encoding.h are unmodified.  crt.S reads the core count from the
# counter block and parks extra harts; util.h declares a barrier() built from
# plain loads and stores (Sodor has no atomics).  syscalls.c is upstream plus
# the CS152 counter and barrier code, and cs152_counters.h is lab-specific.
# Keep these changes when re-syncing from upstream.
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
