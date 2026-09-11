//**************************************************************************
// Blocking L1 data cache
//--------------------------------------------------------------------------
//   sIdle      decode the request; hit -> respond, miss -> evict and refill
//   sHitWait   count out hit_lat, then perform the access and respond
//   sWriteback stream a dirty victim out to DRAM, one word per beat
//   sFill      stream the new line in from DRAM, one word per beat
//

package cs152

import chisel3._
import chisel3.util._
import chisel3.util.random.LFSR
import freechips.rocketchip.util.PlusArg
import sodor.common.{MemPortIo, MemoryModule, SodorCoreParams}
import sodor.common.Constants._

class L1DStats extends Bundle {
  val load        = Bool()
  val store       = Bool()
  val hit         = Bool()
  val miss        = Bool()
  val writeback   = Bool()
  val snoopAccess = Bool()
  val snoopHit    = Bool()
}

/** A snooping agent's view of the cache: combinational lookup, and on a write
  * hit the line absorbs the new data. */
class L1DSnoopIO extends Bundle {
  val valid = Input(Bool())
  val addr  = Input(UInt(32.W))
  val write = Input(Bool())
  val wdata = Input(UInt(32.W))
  val typ   = Input(UInt(3.W))
  val hit   = Output(Bool())
  val rdata = Output(UInt(32.W))
}

class L1DPrefetchIO extends Bundle {
  val req     = Flipped(Valid(UInt(32.W)))      // byte address; masked to a line here
  val busy    = Output(Bool())                  // a prefetch is in flight
  val dropped = Output(Bool())                  // the last request that fired was refused

  val accValid = Output(Bool())
  val accAddr  = Output(UInt(32.W))
  val accWrite = Output(Bool())
  val accMiss  = Output(Bool())

  val dramKill = Output(Bool())          // to FixedLatencyMem, not the model
}

class L1DCache(cfg: CS152CacheParams)(implicit conf: SodorCoreParams) extends Module {
  val io = IO(new Bundle {
    val core     = Flipped(new MemPortIo(data_width = 32))
    val dram     = new DramPortIo
    val stats    = Output(new L1DStats)
    val flushReq = Input(Bool())    // pulse: write back every dirty line, then INVALIDATE
    val cleanReq = Input(Bool())    // pulse: write back every dirty line, keep it VALID
    val snoop    = new L1DSnoopIO
    val pf       = if (cfg.prefetch) Some(new L1DPrefetchIO) else None
  })

  println("    " + cfg.summary)

  import cfg._
  val wayIdxBits   = math.max(wayBits, 1)
  val beatBits     = math.max(log2Ceil(wordsPerLine), 1)
  val dataAddrBits = log2Ceil(sets * ways * lineBytes)

  // Default comes from the Scala config, so the plain make flow needs no flags.
  val hitLat = cfg.hitLat.U(8.W)

  // ---------------- address decomposition ----------------
  def offOf(a: UInt): UInt = a(offsetBits - 1, 0)
  def idxOf(a: UInt): UInt = if (indexBits == 0) 0.U(1.W) else a(offsetBits + indexBits - 1, offsetBits)
  def tagOf(a: UInt): UInt = a(31, offsetBits + indexBits)

  // Shifts, not multiplies: sets/ways/lineBytes are all powers of two.
  def dataAddr(way: UInt, idx: UInt, off: UInt): UInt = {
    val w = if (ways == 1) 0.U else (way << (indexBits + offsetBits)).asUInt
    val i = if (sets == 1) 0.U else (idx << offsetBits).asUInt
    (w | i | off)(dataAddrBits - 1, 0)
  }
  def lineAddr(tag: UInt, idx: UInt, off: UInt): UInt = {
    val t = (tag << (indexBits + offsetBits)).asUInt
    val i = if (sets == 1) 0.U else (idx << offsetBits).asUInt
    (t | i | off)(31, 0)
  }

  // Mirror of MemReq.getTLSize / getTLSigned, which we cannot call on a raw typ.
  def tlSize(typ: UInt): UInt    = (typ - 1.U)(1, 0)
  def tlSigned(typ: UInt): Bool  = !((typ - 1.U)(2).asBool)

  // ---------------- state ----------------
  val tags   = Reg(Vec(ways, Vec(sets, UInt(tagBits.W))))
  val states = RegInit(VecInit(Seq.fill(ways)(VecInit(Seq.fill(sets)(L1State.I)))))
  val dirtys = RegInit(VecInit(Seq.fill(ways)(VecInit(Seq.fill(sets)(false.B)))))

  // True LRU as an age matrix: 0 = most recently used, ways-1 = victim.
  val ages = if (ways > 1)
    Some(RegInit(VecInit(Seq.fill(sets)(VecInit(Seq.tabulate(ways)(i => i.U(wayBits.W)))))))
  else None

  val data = new MemoryModule(sets * ways * lineBytes, useAsync = true)

  val sIdle :: sHitWait :: sWriteback :: sFill :: sFlush :: sFlushWb :: Nil = Enum(6)
  val state = RegInit(sIdle)

  // Whole-cache walk, two modes
  val flushPending = RegInit(false.B)
  val flushInval   = RegInit(false.B)   // true = invalidate as we go; false = clean only
  val flushIdx = RegInit(0.U(math.max(indexBits, 1).W))
  val flushWay = RegInit(0.U(wayIdxBits.W))
  when (io.flushReq) { flushPending := true.B; flushInval := true.B  }
  when (io.cleanReq) { flushPending := true.B; flushInval := false.B }

  val rAddr  = Reg(UInt(32.W))
  val rData  = Reg(UInt(32.W))
  val rFcn   = Reg(UInt(1.W))
  val rTyp   = Reg(UInt(3.W))
  val rWay   = Reg(UInt(wayIdxBits.W))
  val beat   = RegInit(0.U(beatBits.W))
  val hitCnt = Reg(UInt(8.W))

  val req    = io.core.req
  val isIdle = state === sIdle
  val rIdx   = idxOf(rAddr)

  // Writeback addressing is shared by the miss path and the flush walk.
  val flushingWb = state === sFlushWb
  val wbWay = Mux(flushingWb, flushWay, rWay)
  val wbIdx = Mux(flushingWb, flushIdx, rIdx)

  // ---------------- tag lookup (combinational, so a hit can be same-cycle) --
  val reqIdx = idxOf(req.bits.addr)
  val reqTag = tagOf(req.bits.addr)
  val hitVec = VecInit((0 until ways).map(w =>
    L1State.isValid(states(w)(reqIdx)) && tags(w)(reqIdx) === reqTag))
  val isHit  = hitVec.asUInt.orR
  val hitWay = if (ways == 1) 0.U(1.W) else OHToUInt(hitVec.asUInt)

  // ---------------- victim selection ----------------
  val victim = if (ways == 1) 0.U(1.W) else {
    val invalidVec   = VecInit((0 until ways).map(w => !L1State.isValid(states(w)(reqIdx))))
    val hasInvalid   = invalidVec.asUInt.orR
    val firstInvalid = PriorityEncoder(invalidVec.asUInt)
    val replaceWay = if (replacement == "rand") LFSR(16)(wayBits - 1, 0)
                     else OHToUInt(VecInit((0 until ways).map(w => ages.get(reqIdx)(w) === (ways - 1).U)).asUInt)
    Mux(hasInvalid, firstInvalid, replaceWay)
  }
  val victimDirty = L1State.isValid(states(victim)(reqIdx)) && dirtys(victim)(reqIdx)

  def touch(idx: UInt, way: UInt): Unit = ages.foreach { a =>
    val cur = a(idx)(way)
    for (w <- 0 until ways) {
      when (way === w.U)            { a(idx)(w) := 0.U }
        .elsewhen (a(idx)(w) < cur) { a(idx)(w) := a(idx)(w) + 1.U }
    }
  }

  // ---------------- prefetch forward declarations ----------------
  val pfMergeStall = WireDefault(false.B)   // demand access waiting on an in-flight prefetch of ITS line
  val pfKill       = WireDefault(false.B)   // that prefetch dies this cycle
  val pfArrWen     = WireDefault(false.B)   // a prefetch beat lands in the data array
  val pfArrAddr    = WireDefault(0.U(dataAddrBits.W))
  val pfArrData    = WireDefault(0.U(32.W))
  val pfDramVal    = WireDefault(false.B)   // the prefetch wants the DRAM port
  val pfDramAddr   = WireDefault(0.U(32.W))
  val pfDramBurst  = WireDefault(false.B)
  val pfOwnsDram   = WireDefault(false.B)

  // ---------------- the cycle the core's access actually happens ----------
  val idleHitNow  = isIdle && req.valid && !flushPending && !pfMergeStall && isHit && (hitLat <= 1.U)
  val waitDoneNow = (state === sHitWait) && (hitCnt === 0.U)
  val accessNow   = idleHitNow || waitDoneNow

  val accessWay  = Mux(isIdle, hitWay,           rWay)
  val accessAddr = Mux(isIdle, req.bits.addr,    rAddr)
  val accessFcn  = Mux(isIdle, req.bits.fcn,     rFcn)
  val accessTyp  = Mux(isIdle, req.bits.typ,     rTyp)
  val accessData = Mux(isIdle, req.bits.data,    rData)
  val accessIdx  = idxOf(accessAddr)

  // ---------------- one read port, one write port on the data array -------
  val beatOff = if (wordsPerLine == 1) 0.U(offsetBits.W)
                else Cat(beat(log2Ceil(wordsPerLine) - 1, 0), 0.U(2.W))

  val dReadAddr   = Wire(UInt(dataAddrBits.W))
  val dReadSize   = Wire(UInt(2.W))
  val dReadSigned = Wire(Bool())
  when (state === sWriteback || flushingWb) {
    dReadAddr   := dataAddr(wbWay, wbIdx, beatOff)
    dReadSize   := 2.U
    dReadSigned := false.B
  } .otherwise {
    dReadAddr   := dataAddr(accessWay, accessIdx, offOf(accessAddr))
    dReadSize   := tlSize(accessTyp)
    dReadSigned := tlSigned(accessTyp)
  }
  val dReadData = data.read(dReadAddr, dReadSize, dReadSigned)

  val fillBeatNow = (state === sFill) && io.dram.resp.valid && !pfOwnsDram
  val dWriteAddr = Wire(UInt(dataAddrBits.W))
  val dWriteData = Wire(UInt(32.W))
  val dWriteSize = Wire(UInt(2.W))
  val dWriteEn   = Wire(Bool())
  when (fillBeatNow) {
    dWriteAddr := dataAddr(rWay, rIdx, beatOff)
    dWriteData := io.dram.resp.bits
    dWriteSize := 2.U
    dWriteEn   := true.B
  } .otherwise {
    dWriteAddr := dataAddr(accessWay, accessIdx, offOf(accessAddr))
    dWriteData := accessData
    dWriteSize := tlSize(accessTyp)
    dWriteEn   := accessNow && (accessFcn === M_XWR)
  }
  data.write(dWriteAddr, dWriteData, dWriteSize, dWriteEn)

  if (cfg.prefetch) data.write(pfArrAddr, pfArrData, 2.U, pfArrWen)

  // ---------------- snoop port ----------------
  val snIdx = idxOf(io.snoop.addr)
  val snTag = tagOf(io.snoop.addr)
  val snHitVec = VecInit((0 until ways).map(w =>
    L1State.isValid(states(w)(snIdx)) && tags(w)(snIdx) === snTag))
  val snHit  = snHitVec.asUInt.orR
  val snWay  = if (ways == 1) 0.U(1.W) else OHToUInt(snHitVec.asUInt)
  val snAddr = dataAddr(snWay, snIdx, offOf(io.snoop.addr))

  io.snoop.hit   := io.snoop.valid && snHit
  io.snoop.rdata := data.read(snAddr, tlSize(io.snoop.typ), tlSigned(io.snoop.typ))
  data.write(snAddr, io.snoop.wdata, tlSize(io.snoop.typ),
             io.snoop.valid && io.snoop.write && snHit)

  when (accessNow && (accessFcn === M_XWR)) { dirtys(accessWay)(accessIdx) := true.B }

  // ---------------- DRAM side ----------------
  val dramBusy = RegInit(false.B)
  when (io.dram.req.fire)     { dramBusy := true.B  }
  when (io.dram.resp.valid)   { dramBusy := false.B }

  io.dram.req.valid      := (state === sWriteback || state === sFill || flushingWb) && !dramBusy
  io.dram.req.bits.burst := beat =/= 0.U
  io.dram.req.bits.typ   := MT_W
  when (state === sWriteback || flushingWb) {
    io.dram.req.bits.addr := lineAddr(tags(wbWay)(wbIdx), wbIdx, beatOff)
    io.dram.req.bits.fcn  := M_XWR
    io.dram.req.bits.data := dReadData
  } .otherwise {
    io.dram.req.bits.addr := lineAddr(tagOf(rAddr), rIdx, beatOff)
    io.dram.req.bits.fcn  := M_XRD
    io.dram.req.bits.data := 0.U
  }

  def demandDram = state === sWriteback || state === sFill || flushingWb
  // prefetcher to dram ios
  if (cfg.prefetch) {
    when (!demandDram && pfDramVal) {
      io.dram.req.valid      := !dramBusy
      io.dram.req.bits.addr  := pfDramAddr
      io.dram.req.bits.fcn   := M_XRD
      io.dram.req.bits.data  := 0.U
      io.dram.req.bits.burst := pfDramBurst
    }
    when (pfKill && pfOwnsDram) { dramBusy := false.B }
  }

  val lastBeat = beat === (wordsPerLine - 1).U

  // ---------------- FSM ----------------
  req.ready := true.B     // the core does not look at this; see the header note

  // Walk (way, set); returns to sIdle when the last line is done.
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
      } .elsewhen (req.valid && !pfMergeStall) {
        when (isHit) {
          when (hitLat <= 1.U) {
            touch(reqIdx, hitWay)
          } .otherwise {
            rAddr := req.bits.addr; rData := req.bits.data
            rFcn  := req.bits.fcn;  rTyp  := req.bits.typ
            rWay  := hitWay
            hitCnt := hitLat - 2.U
            state := sHitWait
          }
        } .otherwise {
          rAddr := req.bits.addr; rData := req.bits.data
          rFcn  := req.bits.fcn;  rTyp  := req.bits.typ
          rWay  := victim
          beat  := 0.U
          state := Mux(victimDirty, sWriteback, sFill)
        }
      }
    }

    is (sHitWait) {
      when (hitCnt === 0.U) {
        touch(rIdx, rWay)
        state := sIdle
      } .otherwise {
        hitCnt := hitCnt - 1.U
      }
    }

    is (sWriteback) {
      when (io.dram.resp.valid && !pfOwnsDram) {
        when (lastBeat) {
          beat := 0.U
          dirtys(rWay)(rIdx) := false.B
          state := sFill
        } .otherwise {
          beat := beat + 1.U
        }
      }
    }

    is (sFill) {
      when (io.dram.resp.valid && !pfOwnsDram) {
        when (lastBeat) {
          beat := 0.U
          tags(rWay)(rIdx)   := tagOf(rAddr)
          states(rWay)(rIdx) := L1State.M
          dirtys(rWay)(rIdx) := false.B
          hitCnt := hitLat - 1.U
          state  := sHitWait
        } .otherwise {
          beat := beat + 1.U
        }
      }
    }

    is (sFlush) {
      when (L1State.isValid(states(flushWay)(flushIdx)) && dirtys(flushWay)(flushIdx)) {
        beat  := 0.U
        state := sFlushWb
      } .otherwise {
        flushAdvance()
      }
    }

    is (sFlushWb) {
      when (io.dram.resp.valid && !pfOwnsDram) {
        when (lastBeat) {
          beat := 0.U
          flushAdvance()
        } .otherwise {
          beat := beat + 1.U
        }
      }
    }
  }

  // ---------------- responses and counters ----------------
  io.core.resp.valid     := accessNow
  io.core.resp.bits.data := dReadData

  val accepted = isIdle && req.valid && !flushPending && !pfMergeStall
  io.stats.load      := accepted && (req.bits.fcn === M_XRD)
  io.stats.store     := accepted && (req.bits.fcn === M_XWR)
  io.stats.hit       := accepted &&  isHit
  io.stats.miss      := accepted && !isHit
  io.stats.writeback := (accepted && !isHit && victimDirty) ||
                        ((state === sFlush) && L1State.isValid(states(flushWay)(flushIdx)) && dirtys(flushWay)(flushIdx))
  io.stats.snoopAccess := io.snoop.valid
  io.stats.snoopHit    := io.snoop.valid && snHit

  // ================= prefetch context =====================================
  if (cfg.prefetch) {
    val pfio = io.pf.get

    val pfIdle :: pfFill :: Nil = Enum(2)
    val pfState = RegInit(pfIdle)
    val pfAddr  = Reg(UInt(32.W))
    val pfWay   = Reg(UInt(wayIdxBits.W))
    val pfBeat  = RegInit(0.U(beatBits.W))
    val pfDrop  = RegInit(false.B)

    val pfBusy = pfState === pfFill
    val pfIdxR = idxOf(pfAddr)

    // ---- the incoming request, line-aligned in hardware ----
    val pfrAddr = Cat(pfio.req.bits(31, offsetBits), 0.U(offsetBits.W))
    val pfrIdx  = idxOf(pfrAddr)
    val pfrTag  = tagOf(pfrAddr)

    // Tags are Reg(Vec), so this lookup is free and costs the core nothing.
    val pfrResident = VecInit((0 until ways).map(w =>
      L1State.isValid(states(w)(pfrIdx)) && tags(w)(pfrIdx) === pfrTag)).asUInt.orR

    // ---- victim ----
    val pfrInvalidVec = VecInit((0 until ways).map(w => !L1State.isValid(states(w)(pfrIdx))))
    val pfrHasInvalid = pfrInvalidVec.asUInt.orR
    val pfrVictim = if (ways == 1) 0.U(1.W) else {
      val replaceWay = if (replacement == "rand") LFSR(16)(wayBits - 1, 0)
                       else OHToUInt(VecInit((0 until ways).map(w =>
                              ages.get(pfrIdx)(w) === (ways - 1).U)).asUInt)
      Mux(pfrHasInvalid, PriorityEncoder(pfrInvalidVec.asUInt), replaceWay)
    }
    val pfrVictimDirty = L1State.isValid(states(pfrVictim)(pfrIdx)) && dirtys(pfrVictim)(pfrIdx)

    // ---- launch ----
    val pfReady  = (pfState === pfIdle) && !flushPending && !demandDram && !dramBusy
    val pfFire   = pfio.req.valid && pfReady
    val pfAccept = pfFire && !pfrResident && !pfrVictimDirty

    when (pfAccept) {
      pfAddr  := pfrAddr
      pfWay   := pfrVictim
      pfBeat  := 0.U
      pfState := pfFill
      states(pfrVictim)(pfrIdx) := L1State.I
      dirtys(pfrVictim)(pfrIdx) := false.B
    }
    pfDrop := pfFire && !pfAccept

    // ---- merge ----
    val reqLine = req.bits.addr(31, offsetBits)
    val pfLine  = pfAddr(31, offsetBits)
    pfMergeStall := pfBusy && req.valid && (reqLine === pfLine)

    // ---- kill ----
    val demandStarting = isIdle && req.valid && !flushPending && !pfMergeStall && !isHit
    val snoopWrite = io.snoop.valid && io.snoop.write
    /* Gate on the state, not on the transitions into it: enumerating the
       entries into a demand DRAM op means missing one breaks the "one owner"
       invariant silently. */
    pfKill := pfBusy && (demandDram || demandStarting || snoopWrite ||
                         io.flushReq || io.cleanReq || flushPending)

    // ---- beats ----
    val pfOwner = RegInit(false.B)
    when (io.dram.req.fire) { pfOwner := !demandDram && pfDramVal }
    pfOwnsDram := pfOwner

    val pfRespNow = pfBusy && !pfKill && pfOwner && io.dram.resp.valid
    val pfBeatOff = if (wordsPerLine == 1) 0.U(offsetBits.W)
                    else Cat(pfBeat(log2Ceil(wordsPerLine) - 1, 0), 0.U(2.W))
    val pfLastBeat = pfBeat === (wordsPerLine - 1).U

    pfDramVal   := pfBusy && !pfKill
    pfDramAddr  := lineAddr(tagOf(pfAddr), pfIdxR, pfBeatOff)
    pfDramBurst := pfBeat =/= 0.U

    pfArrWen  := pfRespNow
    pfArrAddr := dataAddr(pfWay, pfIdxR, pfBeatOff)
    pfArrData := io.dram.resp.bits

    when (pfRespNow) {
      when (pfLastBeat) {
        // Commit.  pfBeat needs no reset: every launch zeroes it, and it is
        // only ever read while pfState === pfFill.  A fill always lands in M: single core, no coherence.
        tags(pfWay)(pfIdxR)   := tagOf(pfAddr)
        states(pfWay)(pfIdxR) := L1State.M
        dirtys(pfWay)(pfIdxR) := false.B
        pfState := pfIdle
      } .otherwise {
        pfBeat := pfBeat + 1.U
      }
    }

    when (pfKill) { pfState := pfIdle }

    // ---- outputs ----
    pfio.busy      := pfBusy
    pfio.dropped   := pfDrop

    pfio.accValid := accepted
    pfio.accAddr  := req.bits.addr
    pfio.accWrite := req.bits.fcn === M_XWR
    pfio.accMiss  := accepted && !isHit

    pfio.dramKill := pfKill && pfOwner

    // The two invariants the whole design rests on.
    assert(!(demandDram && pfDramVal),
           "CS152 L1D: demand and prefetch both driving the DRAM port")
    assert(!(pfAccept && demandDram),
           "CS152 L1D: prefetch launched while the demand owns the DRAM port")
  }
}
