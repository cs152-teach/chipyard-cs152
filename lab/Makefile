########################################################################
# CS152 Lab 4
#
#   make              build every benchmark: mt-*.riscv and mt-*.riscv.dump
#   make zip-vvadd    package your vvadd_opt for submission
#   make zip-matmul   package your matmul_opt for submission
#   make clean
#
# Then run one:
#   cd $(SIMDIR)
#   make CONFIG=CS152Lab4MSIConfig run-binary-fast BINARY=$(TESTDIR)/mt-vvadd-naive.riscv
#
# The bare-metal runtime -- crt.S, syscalls.c, link.ld and the shared
# headers -- lives in runtime/.  It provides barrier(), setStats(),
# printStats() and finish(); see runtime/util.h.
########################################################################

srcdir := $(dir $(realpath $(firstword $(MAKEFILE_LIST))))
srcdir := $(srcdir:/=)

RUNTIME_DIR = $(srcdir)/runtime
include $(RUNTIME_DIR)/runtime.mk

OBJDUMP = riscv32-unknown-elf-objdump

bmarks := \
	mt-vvadd-naive \
	mt-vvadd-opt \
	mt-matmul-naive \
	mt-matmul-opt

.PHONY: all
all: $(addsuffix .riscv.dump,$(bmarks))

define compile_template
$(1).riscv: CFLAGS += -I$(srcdir)/$(1)
$(1).riscv: $(wildcard $(srcdir)/$(1)/*) $(RUNTIME_DEPS)
	$$(BUILD_KERNEL)
endef
$(foreach b,$(bmarks),$(eval $(call compile_template,$(b))))

%.riscv.dump: %.riscv
	$(OBJDUMP) -D $< > $@

.PHONY: clean
clean:
	rm -f -- $(addsuffix .riscv,$(bmarks)) $(addsuffix .riscv.dump,$(bmarks)) *.zip

#------------------------------------------------------------
# Submit

define zip_template
.PHONY: $(1)
$(1): $(addprefix $(srcdir)/,$(2))
	rm -f -- $(1).zip
	zip -j $(1).zip $$^
endef

$(eval $(call zip_template,zip-vvadd,mt-vvadd-opt/mt-vvadd_opt.c))
$(eval $(call zip_template,zip-matmul,mt-matmul-opt/mt-matmul_opt.c))

.SUFFIXES:
