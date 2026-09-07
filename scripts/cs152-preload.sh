#!/bin/bash
# CS152 Lab 2 -- make a scratchpad pre-load image from a RISC-V ELF.
#
#   cs152-preload.sh <prog.riscv> [outfile]      default outfile: ./preload.hex
#
# The Sodor scratchpad is pre-loaded with $readmemh instead of being written a
# word at a time over fesvr/TSI, which is most of an RTL run's wall time.  Use
# it together with +loadmem=<elf> (LOADMEM=1 in the make flow), so fesvr skips
# the program write:
#
#   cd $(SIMDIR)
#   ../../scripts/cs152-preload.sh prog.riscv
#   make CONFIG=CS152Lab2Config run-binary-fast BINARY=prog.riscv LOADMEM=1
#
# Two traps this script exists to hide -- see cs152/CS152Params.scala:
#
#   * elf2hex's base argument must be DECIMAL.  "0x80000000" silently parses as
#     0 and elf2hex aborts inside htif_hexwriter.cc.
#   * every word must be BYTE-SWAPPED.  The scratchpad is Mem(_, Vec(4, UInt(8.W)))
#     and Chisel's Cat/splitWord reverse the lanes, so Memory[i] holds the word
#     with its bytes in the opposite order to the value at that address.  Get
#     this wrong and the core executes nonsense without any error.

set -euo pipefail

BASE_ADDR=2147483648      # 0x80000000, decimal on purpose
WORD_BYTES=4
DEPTH=524288              # 2 MiB scratchpad / 4 B -- matches AsyncScratchPadMemory

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
