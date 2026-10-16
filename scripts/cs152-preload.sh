#!/bin/bash
# CS152 -- make a memory pre-load image from a RISC-V ELF.
#
#   cs152-preload.sh <prog.riscv> [outfile]      default outfile: ./preload.hex
#
# The CS152 configs pre-load the simulated memory with $readmemh rather than
# having fesvr write the program a word at a time over TSI, which dominates an
# RTL run's wall time.  The pre-load works together with +loadmem=<elf>, which
# makes fesvr skip the program write.  For CS152 configs, make sets LOADMEM=1 by
# default and runs this script automatically, so you rarely need to call it:
#
#   make CONFIG=CS152Lab4MSIConfig run-binary-fast BINARY=prog.riscv
#
# Two details matter (see generators/chipyard/src/main/scala/cs152/):
#
#   * elf2hex's base argument must be decimal; "0x80000000" parses as 0 and
#     elf2hex aborts.
#   * every word must be byte-swapped, because the memory's byte lanes are
#     stored in the opposite order to the value at that address.  An unswapped
#     image makes the core execute garbage without any error.

set -euo pipefail

BASE_ADDR=2147483648      # 0x80000000, decimal on purpose
WORD_BYTES=4
DEPTH=524288              # 2 MiB / 4 B -- matches WithCS152MemoryBytes (CS152SodorConfig)

if [ $# -lt 1 ]; then
  echo "usage: $(basename "$0") <prog.riscv> [outfile]" >&2
  exit 2
fi

elf=$1
out=${2:-preload.hex}

[ -r "$elf" ] || { echo "$(basename "$0"): cannot read $elf" >&2; exit 1; }

elf2hex=$(command -v elf2hex || true)
if [ -z "$elf2hex" ]; then
  # fall back to the shared toolchain the lab environment points at
  elf2hex=${RISCV:-}/bin/elf2hex
  [ -x "$elf2hex" ] || {
    echo "$(basename "$0"): elf2hex not found; source ~/cs152-env.sh first" >&2
    exit 1
  }
fi

tmp=$(mktemp "$(dirname -- "$out")/.$(basename -- "$out").XXXXXX")
trap 'rm -f -- "$tmp"' EXIT

"$elf2hex" "$WORD_BYTES" "$DEPTH" "$elf" "$BASE_ADDR" \
  | sed -E 's/^(..)(..)(..)(..)$/\4\3\2\1/' > "$tmp"

lines=$(wc -l < "$tmp")
if [ "$lines" -ne "$DEPTH" ]; then
  echo "$(basename "$0"): expected $DEPTH lines, got $lines -- refusing to write $out" >&2
  exit 1
fi

mv -f -- "$tmp" "$out"
trap - EXIT
echo "$(basename "$0"): wrote $out ($lines words, index 0 = 0x80000000)"
