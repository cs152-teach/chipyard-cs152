########################################################################
# CS152 Lab 2 -- Directed Portion
#
#   make            fetch-check, copy the vendored runtime, build rsort.riscv
#   make clean      remove build outputs
#   make distclean  also remove the copied upstream files
#
# Then run it:
#   cd $(CHIPYARD_ROOT)/sims/verilator
#   make CONFIG=CS152Lab2Config run-binary-fast BINARY=path-to/rsort.riscv
########################################################################

RISCV ?= $(abspath ../../.conda-env/riscv-tools)
CC     = $(RISCV)/bin/riscv64-unknown-elf-gcc
RT     = ../../toolchains/riscv-tools/riscv-tests

# Copied from the submodule, not tracked (see .gitignore).
VENDORED = crt.S util.h encoding.h link.ld rsort.c dataset1.h

# rv32i has no M extension (Sodor has no multiplier) and this toolchain has no
# rv32 libgcc, so softmul.c supplies the routines the compiler emits calls to.
# zicsr because crt.S touches mstatus / mtvec / mhartid.
ARCH    = -march=rv32i_zicsr -mabi=ilp32
CFLAGS  = $(ARCH) -O2 -std=gnu99 -nostdlib -nostartfiles -ffreestanding -I.
LDFLAGS = -Wl,-T,link.ld
RUNTIME = crt.S syscalls.c softmul.c

.PHONY: all
all: rsort.riscv

rsort.riscv: $(VENDORED) syscalls.c softmul.c cs152_counters.h
	$(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(RUNTIME) rsort.c

# --- vendored copies -------------------------------------------------------
# The riscv-tests submodule needs TWO fetches: the nested env/ is where
# encoding.h lives, and forgetting it is the failure this check exists for.
$(RT)/benchmarks/common/crt.S $(RT)/env/encoding.h:
	@echo "riscv-tests is not fetched.  From the Chipyard root, run:"; \
	 echo "  git submodule update --init --depth 1 toolchains/riscv-tools/riscv-tests"; \
	 echo "  cd toolchains/riscv-tools/riscv-tests && \"; \
	 echo "      git submodule update --init --depth 1 env"; \
	 exit 1

crt.S util.h: %: $(RT)/benchmarks/common/%
	cp $< $@
rsort.c dataset1.h: %: $(RT)/benchmarks/rsort/%
	cp $< $@
encoding.h: $(RT)/env/encoding.h
	cp $< $@
link.ld: $(RT)/benchmarks/common/test.ld
	cp $< $@

.PHONY: clean distclean
clean:
	rm -f *.riscv *.dump
distclean: clean
	rm -f $(VENDORED)

.SUFFIXES:
