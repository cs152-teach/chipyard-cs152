########################################################################
# CS152 Lab 2 -- shared bare-metal runtime
#
#   RUNTIME_DIR = ../runtime
#   include $(RUNTIME_DIR)/runtime.mk
#
#   my_kernel.riscv: my_kernel.c $(RUNTIME_DEPS)
#   	$(BUILD_KERNEL)
#
# List the kernel FIRST in the prerequisites: BUILD_KERNEL compiles $<.
#
# The compiler comes from PATH, the same convention Sodor's own
# test/custom-bmarks and test/custom-tests use.  ~/cs152-env.sh adds it:
#
#   export PATH="$PATH:/home/ff/cs152/bin/riscv-gnu-toolchain/riscv32/bin"
#
# Three flags are less than obvious:
#
#   zicsr    crt.S touches mstatus / mtvec / mhartid.
#   -lgcc    Sodor has no multiplier (mulDiv = None), so the compiler emits
#            calls to the libgcc soft mul/div routines -- printf and setStats
#            in syscalls.c need __udivdi3, __umoddi3, __udivsi3 and __umodsi3,
#            and array index arithmetic alone would need them too.  It has to
#            be named, because -nostdlib drops libgcc along with libc.  That
#            toolchain is built --disable-multilib --with-arch=rv32i
#            --with-abi=ilp32, so its libgcc is the rv32i one we want.
#   --no-warn-rwx-segments
#            link.ld puts .text, .rodata, .data and .bss in one segment, so
#            the linker marks it RWX and warns.  That warning is about page
#            protection; there is no MMU here.
########################################################################

RUNTIME_DIR ?= ../runtime
CC           = riscv32-unknown-elf-gcc

ARCH    = -march=rv32i_zicsr -mabi=ilp32
CFLAGS  = $(ARCH) -O2 -std=gnu99 -nostdlib -nostartfiles -ffreestanding \
          -I. -I$(RUNTIME_DIR)
LDFLAGS = -Wl,-T,$(RUNTIME_DIR)/link.ld -Wl,--no-warn-rwx-segments

RUNTIME_SRCS = $(RUNTIME_DIR)/crt.S $(RUNTIME_DIR)/syscalls.c
RUNTIME_HDRS = $(RUNTIME_DIR)/encoding.h $(RUNTIME_DIR)/util.h \
               $(RUNTIME_DIR)/cs152_counters.h $(RUNTIME_DIR)/link.ld
RUNTIME_DEPS = $(RUNTIME_SRCS) $(RUNTIME_HDRS)

BUILD_KERNEL = $(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(RUNTIME_SRCS) $< -lgcc
