//**************************************************************************
// CS152 Lab 4 -- atomic snooping bus
//--------------------------------------------------------------------------
//
//   L1D 0 --+
//   L1D 1 --+--> CoherentBus --> FixedLatencyMem --> shared memory
//   ...   --+
//   host  --+    (HostAgent.scala: a client that holds no lines)
//
// One transaction at a time, and it runs to completion: a client that wins
// arbitration holds the bus until its own access is done.  Arbitration is
// round-robin.  A transaction ("tenure") is
//
//   sSnoop   one cycle.  The command is broadcast to every other cache, which
//            looks the line up in its (register) tags.  A holder with a clean
//            copy changes state right here.  A holder with a DIRTY copy -- the
//            owner -- keeps its state for now; see sInt.  PutM is not snooped.
//   sWb      the requester's dirty victim (for PutM, the line being written
//            back) is streamed to memory.
//   sInt     the owner's dirty line is streamed to memory and, in the same
//            beats, into the requester.  For this phase the owner may still
//            read the line but may not write it.  Only when the last beat is
//            in memory does the owner downgrade (M -> S for GetS, -> I for
//            GetM).
//   sFill    no owner: the line is read from memory into the requester.
//            Skipped when the request has fill = 0 (the host, which holds no
//            lines).
//   sDone    one cycle: the requester installs the line (the host performs
//            its memory access).
//   sHold    the requester keeps the bus until it drops req.valid -- an L1D
//            after its core access completes.
//
// Upg goes straight from sSnoop to sDone; PutM runs sSnoop, sWb, sDone.
//
// The commands (BusCmd) and every protocol decision -- which state a request
// installs, and what a snooped copy becomes -- are in CoherencePolicy.scala.

package cs152

import chisel3._
import chisel3.util._
import sodor.common.Constants._

/** One client's connection to the bus, from the CLIENT's point of view. */
class L1BusIO(cfg: CS152CacheParams) extends Bundle {
  import cfg.{idxW, wayW, beatW}

  // ---- request: held from the first cycle the client wants the bus until
  //      it is done with its access.  The fields are sampled in sSnoop. ----
  val req = Output(new Bundle {
    val valid  = Bool()
    val cmd    = UInt(BusCmd.width.W)
    val addr   = UInt(32.W)          // line address of the target (PutM: the line written back)
    val way    = UInt(wayW.W)        // way to fill, upgrade or write back
    val wb     = Bool()              // the way holds a dirty line that must go first
    val wbAddr = UInt(32.W)          // its line address
    val fill   = Bool()              // move the line in (false: the host, which only needs it in memory)
  })
  val start     = Input(Bool())      // pulse: sSnoop of this client's tenure
  val grant     = Input(Bool())      // this client holds the bus (sSnoop .. sHold)
  val done      = Input(Bool())      // pulse (sDone): the line is in place
  val doneState = Input(UInt(L1State.width.W))   // state to install on done
  val victimInval = Input(Bool())    // pulse: invalidate the slot at (wr.way, wr.idx) --
                                     // a clean victim in sSnoop, a dirty one after sWb

  // ---- data movement, driven by the bus ----
  // rd: the bus reads one word of the client's data array (sWb, sInt).
  // wr: the bus writes one word of a fill into it (sInt, sFill).
  val rd = Input(new Bundle {
    val way  = UInt(wayW.W)
    val idx  = UInt(idxW.W)
    val beat = UInt(beatW.W)
  })
  val rdData = Output(UInt(32.W))
  val wr = Input(new Bundle {        // way/idx are valid for the whole tenure
    val en   = Bool()
    val way  = UInt(wayW.W)
    val idx  = UInt(idxW.W)
    val beat = UInt(beatW.W)
    val data = UInt(32.W)
  })

  // ---- snoop: another cache's tenure ----
  val snoop = Input(new Bundle {
    val valid = Bool()               // sSnoop of someone else's GetS/GetM/Upg
    val cmd   = UInt(BusCmd.width.W)
    val addr  = UInt(32.W)
  })
  val snHit   = Output(Bool())       // holds the line in any valid state
  val snOwner = Output(Bool())       // holds it DIRTY -- will supply it in sInt
  val snWay   = Output(UInt(wayW.W))

  // Owner side of sInt: no stores to (way, idx) while it streams out, and on
  // the last beat it downgrades to `next`.
  val block = Input(new Bundle {
    val valid = Bool()
    val way   = UInt(wayW.W)
    val idx   = UInt(idxW.W)
    val last  = Bool()
    val next  = UInt(L1State.width.W)
  })
}

class CoherentBus(n: Int, cfg: CS152CacheParams, policy: CoherencePolicy) extends Module {
  val io = IO(new Bundle {
    val clients = Vec(n, Flipped(new L1BusIO(cfg)))
    val dram    = new DramPortIo
  })

  import cfg.wayW
  val nW = log2Up(n)

  val sIdle :: sSnoop :: sWb :: sInt :: sFill :: sDone :: sHold :: Nil = Enum(7)
  val state = RegInit(sIdle)

  // ---------------- arbitration: round-robin ----------------
  val r      = RegInit((n - 1).U(nW.W))   // requester (the last one, between tenures)
  val valids = VecInit(io.clients.map(_.req.valid)).asUInt
  val pick = if (n == 1) 0.U(nW.W) else {
    val above = valids & ~((2.U(n.W) << r) - 1.U)(n - 1, 0)
    Mux(above.orR, PriorityEncoder(above), PriorityEncoder(valids))
  }

  val cmd      = Reg(UInt(BusCmd.width.W))
  val addr     = Reg(UInt(32.W))
  val idx      = cfg.idxOf(addr)
  val way      = Reg(UInt(wayW.W))
  val wbAddr   = Reg(UInt(32.W))
  val owner    = Reg(UInt(nW.W))
  val ownerWay = Reg(UInt(wayW.W))
  val hasOwner = Reg(Bool())
  val shared   = Reg(Bool())

  val req = io.clients(r).req

  // ---------------- snoop (sSnoop, combinational in every other cache) ----
  val snooping = state === sSnoop && req.cmd =/= BusCmd.PutM
  for (i <- 0 until n) {
    val c = io.clients(i)
    c.snoop.valid := snooping && r =/= i.U
    c.snoop.cmd   := req.cmd
    c.snoop.addr  := req.addr
  }
  // Only snooped caches answer: the requester's snoop.valid is low, and the
  // host never reports a hit.
  val hitVec   = VecInit(io.clients.map(_.snHit)).asUInt
  val ownerVec = VecInit(io.clients.map(_.snOwner)).asUInt
  assert(!(snooping && PopCount(ownerVec) > 1.U),
         "CS152 bus: more than one cache claims a dirty copy of the same line")

  // ---------------- DRAM ----------------
  // One word per response.  Every data phase ends on its last beat, so the
  // counter is back at 0 when the next phase starts.
  val beatDone = io.dram.resp.valid
  val (beat, lastBeatDone) = Counter(beatDone, cfg.wordsPerLine)

  // Every client's read port is driven alike; only the source's data is used:
  // the owner's in sInt, the requester's in sWb.
  for (c <- io.clients) {
    c.rd.way  := Mux(state === sInt, ownerWay, way)
    c.rd.idx  := idx
    c.rd.beat := beat
  }
  val srcData = Mux(state === sInt, io.clients(owner).rdData, io.clients(r).rdData)

  val moving = state === sWb || state === sInt || state === sFill
  io.dram.req.valid      := moving      // held high; FixedLatencyMem accepts a word when ready
  io.dram.req.bits.burst := beat =/= 0.U
  io.dram.req.bits.typ   := MT_W
  io.dram.req.bits.addr  := Mux(state === sWb, wbAddr, addr) | cfg.beatOff(beat)
  io.dram.req.bits.fcn   := Mux(state === sFill, M_XRD, M_XWR)
  io.dram.req.bits.data  := srcData

  // The requester's slot for this tenure: taken from the request during
  // sSnoop, and from the latched copy afterwards.
  val slotWay = Mux(state === sSnoop, req.way, way)
  val slotIdx = Mux(state === sSnoop, cfg.idxOf(req.addr), idx)

  // The requester fills from the owner (sInt) or from memory (sFill).
  val fillNow  = (state === sInt || state === sFill) && beatDone
  val fillData = Mux(state === sInt, srcData, io.dram.resp.bits)

  // ---------------- per-client outputs ----------------
  val grantNow = state =/= sIdle
  for (i <- 0 until n) {
    val c    = io.clients(i)
    val isR  = r === i.U
    val isOw = hasOwner && owner === i.U
    c.start     := state === sSnoop && isR
    c.grant     := grantNow && isR
    c.done      := state === sDone && isR
    c.doneState := policy.grantState(cmd, shared)
    c.victimInval := isR && (
      (state === sSnoop && BusCmd.isMiss(req.cmd) && !req.wb) ||
      (state === sWb && lastBeatDone && cmd =/= BusCmd.PutM))

    c.wr.en   := fillNow && isR
    c.wr.way  := slotWay      // also names the slot for victimInval and done
    c.wr.idx  := slotIdx
    c.wr.beat := beat
    c.wr.data := fillData

    c.block.valid := state === sInt && isOw
    c.block.way   := ownerWay
    c.block.idx   := idx
    c.block.last  := lastBeatDone
    c.block.next  := policy.snoopNext(cmd)
  }

  // ---------------- FSM ----------------
  switch (state) {
    is (sIdle) {
      when (valids.orR) {
        r     := pick
        state := sSnoop
      }
    }

    is (sSnoop) {
      cmd    := req.cmd
      addr   := req.addr
      way    := req.way
      wbAddr := req.wbAddr
      shared   := hitVec.orR
      hasOwner := ownerVec.orR
      owner    := OHToUInt(ownerVec)
      ownerWay := io.clients(OHToUInt(ownerVec)).snWay
      when (req.cmd === BusCmd.PutM) {
        state := sWb               // PutM is only issued for a dirty line
      } .elsewhen (req.cmd === BusCmd.Upg) {
        assert(!ownerVec.orR, "CS152 bus: upgrade found a dirty copy elsewhere")
        state := sDone
      } .elsewhen (req.wb) {
        state := sWb
      } .otherwise {
        state := Mux(ownerVec.orR, sInt, Mux(req.fill, sFill, sDone))
      }
    }

    is (sWb) {
      when (lastBeatDone) {
        state := Mux(cmd === BusCmd.PutM, sDone, Mux(hasOwner, sInt, sFill))
      }
    }

    is (sInt, sFill) {
      when (lastBeatDone) { state := sDone }
    }

    is (sDone) { state := sHold }

    is (sHold) {
      when (!req.valid) { state := sIdle }
    }
  }
}
