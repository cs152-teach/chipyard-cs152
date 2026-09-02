//**************************************************************************
// CS152 Lab 2 — Sodor Tile with Memory Subsystem
//--------------------------------------------------------------------------
//
//   imem ---------------------------------------> scratchpad port 1  (direct)
//   dmem --> CS152Router --+-> L1DCache --> FixedLatencyMem --> port 0
//                          +-> CacheCounters (MMIO)
//                          +-> SodorMasterAdapter -> TL -> HTIF
//
//   fesvr --> HostSnoopPort --> L1D snoop port / scratchpad debug port

package cs152

import chisel3._
import chisel3.util._
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy.AddressSet
import sodor.common._
import sodor.common.Constants._

class CS152SodorInternalTile(range: AddressSet, coreCtor: SodorCoreFactory)
    (implicit p: Parameters, conf: SodorCoreParams)
  extends AbstractInternalTile(coreCtor.nMemPorts)
{
  require(coreCtor.nMemPorts == 2, "CS152: the lab-2 tile needs separate I and D ports")

  val cfg = p(CS152CacheKey)

  val core   = Module(coreCtor.instantiate)
  core.io := DontCare
  val memory = Module(new AsyncScratchPadMemory(num_core_ports = 2))

  // MMIO perf counters address range
  val counterRange = AddressSet(p(CS152CounterBase), 0x3f)
  // CS152Router decodes the counter window BEFORE the scratchpad, so an overlap
  // would silently steal 4 KiB of memory rather than fail.
  require(!range.overlaps(counterRange),
    f"CS152: counter window 0x${counterRange.base}%x overlaps the scratchpad at 0x${range.base}%x")
  println(f"    CS152 counter MMIO:    0x${counterRange.base}%x")

  // ---------------- I-port: unchanged ----------------
  val irouter = Module(new SodorRequestRouter(range))
  irouter.io.corePort    <> core.mem_ports(IPORT)
  irouter.io.scratchPort <> memory.io.core_ports(IPORT)
  irouter.io.masterPort  <> io.master_port(IPORT)
  irouter.io.respAddress := core.mem_ports(IPORT).req.bits.addr

  // ---------------- D-port: the new hierarchy ----------------
  val drouter  = Module(new CS152Router(range, counterRange))
  val cache    = Module(new L1DCache(cfg))
  val fmem     = Module(new FixedLatencyMem(cfg))
  val counters = Module(new CacheCounters)

  drouter.io.corePort   <> core.mem_ports(DPORT)
  drouter.io.masterPort <> io.master_port(DPORT)

  cache.io.core     <> drouter.io.cachePort
  counters.io.port  <> drouter.io.counterPort
  counters.io.stats := cache.io.stats
  cache.io.flushReq := counters.io.flush
  cache.io.cleanReq := counters.io.clean

  fmem.io.cache <> cache.io.dram
  fmem.io.mem   <> memory.io.core_ports(DPORT)

  // Host checks cache snoop bus, return on hit, or get data from memory
  val hostPort = Module(new HostSnoopPort)
  hostPort.io.host  <> io.debug_port
  hostPort.io.mem   <> memory.io.debug_port
  hostPort.io.snoop <> cache.io.snoop

  core.interrupt    <> io.interrupt
  core.hartid       := io.hartid
  core.reset_vector := io.reset_vector
}

case object CS152Stage5Factory extends SodorInternalTileFactory {
  def nMemPorts = Stage5Factory.nMemPorts
  def instantiate(range: AddressSet)(implicit p: Parameters, conf: SodorCoreParams) =
    new CS152SodorInternalTile(range, Stage5Factory.Stage5CoreFactory)
}
