########################################################################
# CS152 Lab 2 -- open-ended 2 benchmark suite
#
#   make            build every kernel
#   make clean
#
# Each kernel spans a different access pattern on purpose; see the comment at
# the top of each .c for what it is meant to separate.  Run them with
# ../run-suite, which reports baseline and prefetch side by side.
########################################################################

RUNTIME_DIR = ../../runtime
include $(RUNTIME_DIR)/runtime.mk

CFLAGS += -I.
# Lets a single kernel be rebuilt with an overridden tunable, e.g.
#   make spatial.riscv EXTRA_CFLAGS=-DWORK=8
# Appended, so it wins over the defaults above without discarding them.
CFLAGS += $(EXTRA_CFLAGS)

ifneq ($(MAKECMDGOALS),clean)
ifeq ($(shell command -v $(CC) >/dev/null 2>&1 && echo yes),)
$(error $(CC) not on PATH -- the lab environment is not active. Run: source ~/cs152-env.sh cs152-lab2-fa26)
endif
endif

# THE kernel list.
KERNELS = $(shell cat KERNELS)
BINS    = $(addsuffix .riscv,$(KERNELS))

.PHONY: all
all: $(BINS)

%.riscv: %.c bench.h $(RUNTIME_DEPS)
	$(BUILD_KERNEL)
	@$(CHECK_ONE)

.PHONY: clean
clean:
	rm -f *.riscv *.dump

.SUFFIXES:

# Fail at build time on an over-budget kernel.  See bench.h: the routed
# scratchpad is 256 KiB and crt.S puts the stack at _end + 128 KiB, so _end must
# stay under 128 KiB or the run dies on a TileLink assertion ~1.5 M cycles in.
# Derive from CC rather than restating the triple runtime.mk owns.
NM ?= $(CC:gcc=nm)
LOAD_ADDR  ?= 0x80000000
BUDGET_KIB ?= 128
MARGIN_KIB ?= 32

# Run on every kernel as it is linked, so building a single kernel is checked
# too, not only `make all`.
CHECK_ONE = e=$$($(NM) $@ | awk '/ _end$$/{print $$1}'); \
	k=$$(( (0x$$e - $(LOAD_ADDR)) / 1024 )); \
	if [ $$k -gt $(BUDGET_KIB) ]; then \
	  echo "$@: _end is $$k KiB, over the $(BUDGET_KIB) KiB budget (see bench.h)"; \
	  exit 1; \
	elif [ $$k -gt $$(( $(BUDGET_KIB) - $(MARGIN_KIB) )) ]; then \
	  echo "$@: _end is $$k KiB -- under the limit, under $(MARGIN_KIB) KiB of margin"; \
	fi

.PHONY: check-size
check-size: $(BINS)
	@echo "all kernels within the footprint budget"
