//**************************************************************************
// CS152 Lab 4 -- blocking, coherent L1 data cache
//--------------------------------------------------------------------------
//
//   sIdle      decode the request: a hit with enough permission completes
//              here (hit_lat = 1); anything else needs the bus
//   sHitWait   count out hit_lat, then perform the access and respond; also
//              used after a bus fill, with the bus still held
//   sBus       hold a request on the CoherentBus until the line is in place,
//              then keep the bus through the access itself
//   sFlush     the flush/clean walk; sFlushBus writes one dirty line back
//
// Every movement of data goes through the bus, which serializes them, so the
// FSM here only decides WHAT to ask for; the bus does the moving.  See
// CoherentBus.scala for the transaction.
//
// Permissions: a load needs any valid state; a store needs M or E (E -> M is
// silent) and the line must not be write-blocked by an intervention.  A store
// to a line in S issues an upgrade; what a miss asks for, and every other
// protocol decision, comes from CoherencePolicy.scala.

package cs152

import chisel3._
import chisel3.util._
import chisel3.util.random.LFSR
import sodor.common.{MemPortIo, MemoryModule, SodorCoreParams}
import sodor.common.Constants._

class L1DStats extends Bundle {
  val load         = Bool()
  val store        = Bool()
  val hit          = Bool()   // accepted with enough permission: no bus needed
  val miss         = Bool()   // a GetS or GetM
  val loadMiss     = Bool()   //   ... for a load  (split by the access, not the command:
  val storeMiss    = Bool()   //   ... for a store  under MI a load miss sends GetM)
  val upgrade      = Bool()   // an Upg: store to a line held in S
  val writeback    = Bool()   // a dirty line this cache wrote back (eviction or flush/clean)
  val inval        = Bool()   // a remote request took a valid line away
  val intervention = Bool()   // a remote request made this cache supply a dirty line
  val busWait      = Bool()   // this cycle the cache wanted the bus and did not have it
}

class L1DCache(cfg: CS152CacheParams, policy: CoherencePolicy)
    (implicit conf: SodorCoreParams) extends Module {
  val io = IO(new Bundle {
    val core     = Flipped(new MemPortIo(data_width = 32))
    val bus      = new L1BusIO(cfg)
    val stats    = Output(new L1DStats)
    val flushReq = Input(Bool())    // pulse: write back every dirty line, INVALIDATE every line
    val cleanReq = Input(Bool())    // pulse: write back every dirty line, keep it VALID
  })

  import cfg._
  val dataAddrBits = log2Ceil(sets * ways * lineBytes)

  val hitLat = cfg.hitLat.U(8.W)

  // ---------------- address decomposition ----------------
  def offOf(a: UInt): UInt = a(offsetBits - 1, 0)
  def tagOf(a: UInt): UInt = a(31, offsetBits + indexBits)

  def dataAddr(way: UInt, idx: UInt, off: UInt): UInt = {
    val w = if (ways == 1) 0.U else (way << (indexBits + offsetBits)).asUInt
    val i = if (sets == 1) 0.U else (idx << offsetBits).asUInt
    (w | i | off)(dataAddrBits - 1, 0)
  }
  def lineAddr(tag: UInt, idx: UInt): UInt = {
    val t = (tag << (indexBits + offsetBits)).asUInt
    val i = if (sets == 1) 0.U else (idx << offsetBits).asUInt
    (t | i)(31, 0)
  }

  def tlSize(typ: UInt): UInt   = (typ - 1.U)(1, 0)
  def tlSigned(typ: UInt): Bool = !((typ - 1.U)(2).asBool)

  // ---------------- state ----------------
  // Registers, not SRAM: the core and the bus snoop look the tags up in the
  // same cycle, and neither arbitrates for a port.
  val tags   = Reg(Vec(ways, Vec(sets, UInt(tagBits.W))))
  val states = RegInit(VecInit(Seq.fill(ways)(VecInit(Seq.fill(sets)(L1State.I)))))
  val dirtys = RegInit(VecInit(Seq.fill(ways)(VecInit(Seq.fill(sets)(false.B)))))

  // True LRU as an age matrix: 0 = most recently used, ways-1 = victim.
  val ages = if (ways > 1)
    Some(RegInit(VecInit(Seq.fill(sets)(VecInit(Seq.tabulate(ways)(i => i.U(wayBits.W)))))))
  else None

  val data = new MemoryModule(sets * ways * lineBytes, useAsync = true)

  val sIdle :: sHitWait :: sBus :: sFlush :: sFlushBus :: Nil = Enum(5)
  val state = RegInit(sIdle)

  val flushPending = RegInit(false.B)
  val flushInval   = RegInit(false.B)
  val flushIdx = RegInit(0.U(idxW.W))
  val flushWay = RegInit(0.U(wayW.W))
  when (io.flushReq) { flushPending := true.B; flushInval := true.B  }
  when (io.cleanReq) { flushPending := true.B; flushInval := false.B }

  val rAddr   = Reg(UInt(32.W))
  val rData   = Reg(UInt(32.W))
  val rFcn    = Reg(UInt(1.W))
  val rTyp    = Reg(UInt(3.W))
  val rWay    = Reg(UInt(wayW.W))
  val hitCnt  = Reg(UInt(8.W))
  val holdBus = RegInit(false.B)    // this cache holds the bus until the current access completes

  val req    = io.core.req
  val isIdle = state === sIdle
  val rIdx   = idxOf(rAddr)

  // Owner side of a bus intervention: the line streaming out may not be written.
  def blocked(way: UInt, idx: UInt): Bool =
    io.bus.block.valid && io.bus.block.way === way && io.bus.block.idx === idx

  def lookup(a: UInt): (Bool, UInt) = {
    val i = idxOf(a); val t = tagOf(a)
    val vec = VecInit((0 until ways).map(w => L1State.isValid(states(w)(i)) && tags(w)(i) === t))
    (vec.asUInt.orR, if (ways == 1) 0.U(1.W) else OHToUInt(vec.asUInt))
  }

  def victimOf(idx: UInt): UInt = if (ways == 1) 0.U(1.W) else {
    val invalidVec   = VecInit((0 until ways).map(w => !L1State.isValid(states(w)(idx))))
    val replaceWay = if (replacement == "rand") LFSR(16)(wayBits - 1, 0)
                     else OHToUInt(VecInit((0 until ways).map(w => ages.get(idx)(w) === (ways - 1).U)).asUInt)
    Mux(invalidVec.asUInt.orR, PriorityEncoder(invalidVec.asUInt), replaceWay)
  }

  def touch(idx: UInt, way: UInt): Unit = ages.foreach { a =>
    val cur = a(idx)(way)
    for (w <- 0 until ways) {
      when (way === w.U)            { a(idx)(w) := 0.U }
        .elsewhen (a(idx)(w) < cur) { a(idx)(w) := a(idx)(w) + 1.U }
    }
  }

  // Does the line at (way, idx) let this access through without the bus?
  def permits(way: UInt, idx: UInt, fcn: UInt): Bool = {
    val s = states(way)(idx)
    Mux(fcn === M_XWR, L1State.canWrite(s) && !blocked(way, idx), L1State.canRead(s))
  }

  // ---------------- the core's access ----------------
  val (isHit, hitWay) = lookup(req.bits.addr)
  val reqIdx    = idxOf(req.bits.addr)
  val canAccess = isHit && permits(hitWay, reqIdx, req.bits.fcn)
  val accepted  = isIdle && req.valid && !flushPending

  // Re-check at the end of a hit_lat wait: a snoop may have taken the line.
  // Skipped while this cache holds the bus (holdBus): nothing can take it then.
  val (rHit, rHitWay) = lookup(rAddr)
  val rStillOk = rHit && rHitWay === rWay && permits(rWay, rIdx, rFcn)

  val idleHitNow  = accepted && canAccess && (hitLat <= 1.U)
  val waitDone    = (state === sHitWait) && (hitCnt === 0.U)
  val waitHitNow  = waitDone && (holdBus || rStillOk)
  val accessNow   = idleHitNow || waitHitNow

  val accessWay  = Mux(isIdle, hitWay,        rWay)
  val accessAddr = Mux(isIdle, req.bits.addr, rAddr)
  val accessFcn  = Mux(isIdle, req.bits.fcn,  rFcn)
  val accessTyp  = Mux(isIdle, req.bits.typ,  rTyp)
  val accessData = Mux(isIdle, req.bits.data, rData)
  val accessIdx  = idxOf(accessAddr)
  val storeNow   = accessNow && accessFcn === M_XWR

  // ---------------- data array ----------------
  // Four ports, all combinational: core read, bus read; core write, bus fill.
  // The bus never fills a line the core is accessing.
  val dReadData = data.read(dataAddr(accessWay, accessIdx, offOf(accessAddr)),
                            tlSize(accessTyp), tlSigned(accessTyp))
  data.write(dataAddr(accessWay, accessIdx, offOf(accessAddr)), accessData,
             tlSize(accessTyp), storeNow)

  io.bus.rdData := data.read(dataAddr(io.bus.rd.way, io.bus.rd.idx, beatOff(io.bus.rd.beat)), 2.U, false.B)
  data.write(dataAddr(io.bus.wr.way, io.bus.wr.idx, beatOff(io.bus.wr.beat)), io.bus.wr.data,
             2.U, io.bus.wr.en)

  // ---------------- core-side state updates ----------------
  when (accessNow) { touch(accessIdx, accessWay) }
  when (storeNow) {
    dirtys(accessWay)(accessIdx) := true.B
    states(accessWay)(accessIdx) := L1State.M     // E -> M is silent
  }

  // ---------------- bus request ----------------
  // Recomputed every cycle until the bus samples it: if a snoop takes this
  // cache's S copy while it waits, the Upg becomes a miss request.
  val victim      = victimOf(rIdx)
  val victimValid = L1State.isValid(states(victim)(rIdx))
  val victimDirty = victimValid && dirtys(victim)(rIdx)
  val wantWrite   = rFcn === M_XWR

  val flushSlotValid = L1State.isValid(states(flushWay)(flushIdx))
  val flushSlotDirty = flushSlotValid && dirtys(flushWay)(flushIdx)
  val flushLine      = lineAddr(tags(flushWay)(flushIdx), flushIdx)

  val inFlushBus = state === sFlushBus
  io.bus.req.valid  := state === sBus || inFlushBus || holdBus
  io.bus.req.cmd    := Mux(inFlushBus, BusCmd.PutM,
                       Mux(rHit, BusCmd.Upg,
                       policy.missCmd(wantWrite)))
  io.bus.req.addr   := Mux(inFlushBus, flushLine, lineAddr(tagOf(rAddr), rIdx))
  io.bus.req.way    := Mux(inFlushBus, flushWay, Mux(rHit, rHitWay, victim))
  io.bus.req.wb     := Mux(inFlushBus, flushSlotDirty, !rHit && victimDirty)
  io.bus.req.wbAddr := Mux(inFlushBus, flushLine, lineAddr(tags(victim)(rIdx), rIdx))
  io.bus.req.fill   := true.B

  // The bus names the slot from here on; the request fields above are only
  // meaningful until it samples them.
  val slotWay = io.bus.wr.way
  val slotIdx = io.bus.wr.idx

  when (io.bus.victimInval) {
    states(slotWay)(slotIdx) := L1State.I
    dirtys(slotWay)(slotIdx) := false.B
  }

  // ---------------- snoop (someone else's tenure) ----------------
  val sn = io.bus.snoop
  val (snHit, snWay) = lookup(sn.addr)
  val snIdx   = idxOf(sn.addr)
  // A store landing this very cycle makes the line dirty even though the
  // register still says clean -- the snoop is ordered after that store.
  val snDirty = dirtys(snWay)(snIdx) || (storeNow && accessWay === snWay && accessIdx === snIdx)
  io.bus.snHit   := sn.valid && snHit
  io.bus.snOwner := sn.valid && snHit && snDirty
  io.bus.snWay   := snWay

  // A clean holder changes state now.  A dirty one waits for the last beat
  // of its intervention.
  val snCleanHit = sn.valid && snHit && !snDirty
  val snNext = policy.snoopNext(sn.cmd)
  when (snCleanHit) { states(snWay)(snIdx) := snNext }

  val blk = io.bus.block
  val downgrade = blk.valid && blk.last
  when (downgrade) {
    states(blk.way)(blk.idx) := blk.next
    dirtys(blk.way)(blk.idx) := false.B
  }

  // ---------------- FSM ----------------
  req.ready := true.B     // the core holds its request until resp.valid

  def flushAdvance(): Unit = {
    when (flushInval) { states(flushWay)(flushIdx) := L1State.I }
    dirtys(flushWay)(flushIdx) := false.B
    when (flushWay === (ways - 1).U) {
      flushWay := 0.U
      when (flushIdx === (sets - 1).U) {
        flushIdx     := 0.U
        flushPending := false.B
        state        := sIdle
      } .otherwise {
        flushIdx := flushIdx + 1.U
        state    := sFlush
      }
    } .otherwise {
      flushWay := flushWay + 1.U
      state    := sFlush
    }
  }

  switch (state) {
    is (sIdle) {
      when (flushPending) {
        state := sFlush
      } .elsewhen (req.valid) {
        rAddr := req.bits.addr; rData := req.bits.data
        rFcn  := req.bits.fcn;  rTyp  := req.bits.typ
        when (canAccess) {
          when (hitLat > 1.U) {
            rWay   := hitWay
            hitCnt := hitLat - 2.U
            state  := sHitWait
          }
        } .otherwise {
          state := sBus
        }
      }
    }

    is (sHitWait) {
      when (hitCnt === 0.U) {
        holdBus := false.B
        state   := sIdle        // on a failed re-check the core retries
      } .otherwise {
        hitCnt := hitCnt - 1.U
      }
    }

    is (sBus) {
      when (io.bus.done) {
        tags(slotWay)(slotIdx)   := tagOf(rAddr)
        states(slotWay)(slotIdx) := io.bus.doneState
        dirtys(slotWay)(slotIdx) := false.B
        rWay    := slotWay
        hitCnt  := hitLat - 1.U
        holdBus := true.B
        state   := sHitWait
      }
    }

    is (sFlush) {
      when (flushSlotDirty) { state := sFlushBus }
        .otherwise          { flushAdvance() }
    }

    is (sFlushBus) {
      when (io.bus.done) { flushAdvance() }
    }
  }

  assert(!(io.bus.done && state =/= sBus && state =/= sFlushBus),
         "CS152 L1D: bus completed a transaction this cache was not waiting for")
  assert(!(io.bus.wr.en && accessNow && io.bus.wr.way === accessWay && io.bus.wr.idx === accessIdx),
         "CS152 L1D: fill and core access to the same line in one cycle")

  // ---------------- responses and counters ----------------
  io.core.resp.valid     := accessNow
  io.core.resp.bits.data := dReadData

  io.stats.load         := accepted && (req.bits.fcn === M_XRD)
  io.stats.store        := accepted && (req.bits.fcn === M_XWR)
  io.stats.hit          := accepted && canAccess
  io.stats.miss         := io.bus.start && BusCmd.isMiss(io.bus.req.cmd)
  io.stats.loadMiss     := io.stats.miss && !wantWrite
  io.stats.storeMiss    := io.stats.miss && wantWrite
  io.stats.upgrade      := io.bus.start && io.bus.req.cmd === BusCmd.Upg
  io.stats.writeback    := io.bus.start && io.bus.req.wb
  io.stats.inval        := (snCleanHit && snNext === L1State.I) ||
                           (downgrade && blk.next === L1State.I)
  io.stats.intervention := downgrade
  io.stats.busWait      := (state === sBus || inFlushBus) && !io.bus.grant
}
