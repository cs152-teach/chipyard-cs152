########################################################################
# CS152 Lab 2 -- Open-Ended Problem 1: mystery memory subsystem
#
#   make            build probe.riscv
#   make clean      remove build outputs
#
# Then run it on the mystery machine:
#   /home/ff/cs152/fa26/lab2/run-mystery probe.riscv
########################################################################

RUNTIME_DIR = ../runtime
include $(RUNTIME_DIR)/runtime.mk

.PHONY: all
all: probe.riscv

probe.riscv: probe.c $(RUNTIME_DEPS)
	$(BUILD_KERNEL)

.PHONY: clean
clean:
	rm -f *.riscv *.dump

.SUFFIXES:
