########################################################################
# CS152 -- shared bare-metal runtime
#
#   RUNTIME_DIR = ../runtime
#   include $(RUNTIME_DIR)/runtime.mk
#
#   my_kernel.riscv: my_kernel.c $(RUNTIME_DEPS)
#   	$(BUILD_KERNEL)
#
# BUILD_KERNEL compiles every .c prerequisite outside the runtime itself, so a
# kernel may be one file or several.
#
# The compiler must be on PATH; ~/cs152-env.sh adds it:
#
#   export PATH="$PATH:/home/ff/cs152/bin/riscv-gnu-toolchain/riscv32/bin"
#
# Notable flags:
#
#   zicsr    crt.S and syscalls.c access CSRs (mstatus, mtvec, mhartid, ...).
#   -lgcc    Sodor has no multiplier (RV32I only), so multiplies and divides
#            compile to libgcc calls (__mulsi3, __udivsi3, __udivdi3, ...).
#            -nostdlib drops libgcc along with libc, so it is named here.
#            The toolchain's libgcc is built for rv32i/ilp32.
#   --no-warn-rwx-segments
#            link.ld puts .text, .rodata, .data and .bss in one segment, which
#            the linker would warn is RWX.  There is no MMU, so the warning
#            does not apply.
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

BUILD_KERNEL = $(CC) $(CFLAGS) $(LDFLAGS) -o $@ $(RUNTIME_SRCS) \
               $(filter-out $(abspath $(RUNTIME_DEPS)),$(abspath $(filter %.c,$^))) -lgcc
