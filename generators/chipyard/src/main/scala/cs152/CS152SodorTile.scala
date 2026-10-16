//**************************************************************************
// CS152 Lab 4 -- N Sodor cores with coherent L1 data caches
//--------------------------------------------------------------------------
//
//   core i imem --------------------------------------------> memory I-port i
//   core i dmem --> CS152Router --+-> L1D i --+
//                                 |           +--> CoherentBus --> FixedLatencyMem --> memory D-port
//                                 |    host --+
//                                 +-> counters i (MMIO, private to core i)
//                                 +-> master arbiter --> TL --> HTIF, CLINT, ...
//
//   fesvr --> HostAgent: a bus tenure (the "host" client above), then one word
//                        through the memory debug port
//
// Chipyard sees ONE tile and ONE hart.  Every core lives inside it, so they
// can share one memory and one bus (a stock Sodor tile owns a private
// scratchpad).
//
// Boot.  Core 0 is the hart chipyard knows about: it boots through the
// bootrom and waits for the host's MSIP exactly as a single Sodor does.  Cores
// 1..n-1 are held in reset until core 0 fetches its first instruction from
// memory -- by then the program is loaded -- and then start at the base of
// memory, i.e. at _start, with mhartid = i.  They never see the bootrom, and
// they get no interrupts.

package cs152

import chisel3._
import chisel3.util._
import chisel3.util.experimental.loadMemoryFromFileInline
import org.chipsalliance.cde.config.Parameters
import freechips.rocketchip.diplomacy.AddressSet
import freechips.rocketchip.rocket.CoreInterrupts
import sodor.common._
import sodor.common.Constants._

/** The shared memory: one read-only port per core for instruction fetch, one
  * port for the bus (via FixedLatencyMem), one for the host.  Sized to the tile's address range (see
  * WithCS152MemoryBytes), which scripts/cs152-preload.sh's DEPTH must match. */
class CS152Memory(nIPorts: Int, numBytes: Int)(implicit conf: SodorCoreParams) extends Module {
  val io = IO(new Bundle {
    val iports     = Vec(nIPorts, Flipped(new MemPortIo(data_width = 32)))
    val dport      = Flipped(new MemPortIo(data_width = 32))
    val debug_port = Flipped(new MemPortIo(data_width = 32))
  })
  println(s"    CS152 shared memory:   ${numBytes / 1024} KiB, $nIPorts fetch port(s)")

  val async_data = new MemoryModule(numBytes, useAsync = true)

  val rwPorts = Seq(io.dport, io.debug_port)
  for (p <- io.iports ++ rwPorts) {
    p.req.ready      := true.B
    p.resp.valid     := p.req.valid
    p.resp.bits.data := async_data.read(p.req.bits.addr, p.req.bits.getTLSize, p.req.bits.getTLSigned)
  }
  for (p <- rwPorts) {
    async_data.write(p.req.bits.addr, p.req.bits.data, p.req.bits.getTLSize,
                     p.req.valid && p.req.bits.fcn === M_XWR)
  }
}

/** n cores, one TileLink master port.  A Sodor core holds its request steady
  * until the response, and the master adapter handles one request at a time,
  * so the arbiter locks onto a core from its request to its response. */
class CS152MasterArbiter(n: Int)(implicit conf: SodorCoreParams) extends Module {
  val io = IO(new Bundle {
    val in  = Vec(n, Flipped(new MemPortIo(data_width = conf.xprlen)))
    val out = new MemPortIo(data_width = conf.xprlen)
  })
  val busy  = RegInit(false.B)
  val owner = Reg(UInt(log2Up(n).W))
  val pick  = PriorityEncoder(io.in.map(_.req.valid))
  val sel   = Mux(busy, owner, pick)

  io.out.req.valid := io.in(sel).req.valid
  io.out.req.bits  := io.in(sel).req.bits
  when (io.out.req.fire)   { busy := true.B; owner := sel }
  when (io.out.resp.valid) { busy := false.B }

  for (i <- 0 until n) {
    io.in(i).req.ready  := !busy && sel === i.U && io.out.req.ready
    io.in(i).resp.valid := busy && owner === i.U && io.out.resp.valid
    io.in(i).resp.bits  := io.out.resp.bits
  }
}

class CS152SodorInternalTile(range: AddressSet, coreCtor: SodorCoreFactory)
    (implicit p: Parameters, conf: SodorCoreParams)
  extends AbstractInternalTile(coreCtor.nMemPorts)
{
  require(coreCtor.nMemPorts == 2, "CS152: the lab tile needs separate I and D ports")

  val cfg = p(CS152CacheKey)
  val sys = p(CS152SystemKey)
  val n   = sys.nCores

  println(s"    CS152 cores:           $n x Sodor 5-stage, ${sys.protocol} coherence")
  println("    " + cfg.summary + (if (n > 1) " -- per core" else ""))

  val memory = Module(new CS152Memory(n, (range.mask + 1).toInt))
  p(CS152PreloadHex).foreach { hex =>
    loadMemoryFromFileInline(memory.async_data.mem, hex)
    println(s"    CS152 memory preload:  $hex ($$readmemh, fesvr load bypassed)")
  }

  val counterRange = AddressSet(p(CS152CounterBase), 0x3f)
  require(!range.overlaps(counterRange),
    f"CS152: counter window 0x${counterRange.base}%x overlaps memory at 0x${range.base}%x")
  println(f"    CS152 counter MMIO:    0x${counterRange.base}%x (per core)")

  // ---------------- secondary-core release ----------------
  val released = RegInit(false.B)

  val cores = (0 until n).map { i =>
    val c = if (i == 0) Module(coreCtor.instantiate)
            else withReset(reset.asBool || !released) { Module(coreCtor.instantiate) }
    c.io := DontCare
    c
  }

  // Core 0 fetching from memory means it has left the bootrom: the program is
  // in place.
  val fetch0 = cores(0).mem_ports(IPORT).req
  when (fetch0.valid && range.contains(fetch0.bits.addr)) { released := true.B }

  // ---------------- per-core plumbing ----------------
  val policy   = new CoherencePolicy(sys.protocol)
  val bus      = Module(new CoherentBus(n + 1, cfg, policy))   // clients 0..n-1: L1Ds; client n: host
  val fmem     = Module(new FixedLatencyMem(cfg))
  val masterD  = Module(new CS152MasterArbiter(n))
  val host     = Module(new HostAgent(cfg, policy))

  val caches = (0 until n).map { i =>
    val core = cores(i)

    // I-port: memory, or (core 0 only) the bootrom over TileLink
    val irouter = Module(new SodorRequestRouter(range))
    irouter.io.corePort    <> core.mem_ports(IPORT)
    irouter.io.scratchPort <> memory.io.iports(i)
    irouter.io.respAddress := core.mem_ports(IPORT).req.bits.addr
    if (i == 0) {
      irouter.io.masterPort <> io.master_port(IPORT)
    } else {
      // A secondary core runs only from memory; a fetch from anywhere else
      // stalls forever and trips the assertion below.
      irouter.io.masterPort.req.ready := false.B
      irouter.io.masterPort.resp.valid := false.B
      irouter.io.masterPort.resp.bits := DontCare
      assert(!irouter.io.masterPort.req.valid, s"CS152: core $i fetched from outside memory")
    }

    // D-port: cache, counters, or the shared master port
    val drouter  = Module(new CS152Router(range, counterRange))
    val cache    = Module(new L1DCache(cfg, policy))
    val counters = Module(new CacheCounters(n))

    drouter.io.corePort   <> core.mem_ports(DPORT)
    drouter.io.masterPort <> masterD.io.in(i)

    cache.io.core     <> drouter.io.cachePort
    counters.io.port  <> drouter.io.counterPort
    counters.io.stats := cache.io.stats
    cache.io.flushReq := counters.io.flush
    cache.io.cleanReq := counters.io.clean

    bus.io.clients(i) <> cache.io.bus

    if (i == 0) {
      core.interrupt    <> io.interrupt
      core.reset_vector := io.reset_vector
    } else {
      core.interrupt    := 0.U.asTypeOf(new CoreInterrupts(false))
      core.reset_vector := range.base.U
    }
    core.hartid := io.hartid + i.U
    cache
  }

  masterD.io.out <> io.master_port(DPORT)

  fmem.io.cache <> bus.io.dram
  fmem.io.mem   <> memory.io.dport

  bus.io.clients(n) <> host.io.bus
  host.io.host <> io.debug_port
  host.io.mem  <> memory.io.debug_port
}

case object CS152Stage5Factory extends SodorInternalTileFactory {
  def nMemPorts = Stage5Factory.nMemPorts
  def instantiate(range: AddressSet)(implicit p: Parameters, conf: SodorCoreParams) =
    new CS152SodorInternalTile(range, Stage5Factory.Stage5CoreFactory)
}
