########################################################################
# CS152 Lab 2 -- shared bare-metal runtime
#
# This is the compile harness every kernel in lab 2 goes through: the
# directed benchmark and all three open-ended problems link against it.
# Include it from a problem directory:
#
#   RUNTIME_DIR = ../runtime
#   include $(RUNTIME_DIR)/runtime.mk
#
#   my_kernel.riscv: my_kernel.c $(RUNTIME_DEPS)
#   	$(BUILD_KERNEL)
#
# List the kernel FIRST in the prerequisites: BUILD_KERNEL compiles $<.
#
# Sodor has no multiplier (mulDiv = None) and this toolchain ships no rv32
# libgcc, so softmul.c supplies the routines the compiler emits calls to --
# array index arithmetic alone is enough to need them.  zicsr because crt.S
# touches mstatus / mtvec / mhartid.
########################################################################

RUNTIME_DIR ?= ../runtime
RISCV       ?= $(abspath $(RUNTIME_DIR)/../../.conda-env/riscv-tools)
CC           = $(RISCV)/bin/riscv64-unknown-elf-gcc

ARCH    = -march=rv32i_zicsr -mabi=ilp32
CFLAGS  = $(ARCH) -O2 -std=gnu99 -nostdlib -nostartfiles -ffreestanding \
          -I. -I$(RUNTIME_DIR)
LDFLAGS = -Wl,-T,$(RUNTIME_DIR)/link.ld

RUNTIME_SRCS = $(RUNTIME_DIR)/crt.S $(RUNTIME_DIR)/syscalls.c \
               $(RUNTIME_DIR)/softmul.c
RUNTIME_HDRS = $(RUNTIME_DIR)/encoding.h $(RUNTIME_DIR)/util.h \
               $(RUNTIME_DIR)/cs152_counters.h $(RUNTIME_DIR)/link.ld
RUNTIME_DEPS = $(RUNTIME_SRCS) $(RUNTIME_HDRS)

BUILD_KERNEL = $(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(RUNTIME_SRCS) $<
