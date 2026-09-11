/* CS152 Lab 2, open-ended 2 -- YOUR HARDWARE PREFETCHER. */

#include <stdint.h>
#include <stdio.h>

/* The core's demand access this cycle.  `valid` is false on a cycle with no
   access.  Nothing here lags: `is_miss` is the outcome of THIS access. */
struct Access {
  bool     valid;
  uint32_t addr;
  bool     is_write;
  bool     is_miss;
};

/* Registered status from the prefetch port. */
struct Status {
  bool busy;      /* a prefetch of yours is in flight (issued, not yet landed) */
  bool dropped;   /* the request you returned last cycle was refused */
};

/* Return {false, 0} for "no request this cycle". */
struct PrefetchReq {
  bool     valid;
  uint32_t addr;
};

class Prefetcher {
public:
  /* Called once, at time 0, with the geometry this simulator was built with.
     Line size is a config parameter in the directed portion, so it is passed
     rather than assumed. */
  void reset(unsigned line_bytes, unsigned sets, unsigned ways)
  {
    line_bytes_ = line_bytes;
    sets_       = sets;
    ways_       = ways;

    /* TODO: initialise your own state here. */
  }

  /* Called once per cycle. */
  PrefetchReq tick(const Access &acc, const Status &status)
  {
    /* TODO: replace this with your policy. */
    (void)acc; (void)status;
    return { false, 0 };
  }

private:
  unsigned line_bytes_ = 32;
  unsigned sets_       = 64;
  unsigned ways_       = 2;
};

/* ==========================================================================
 * DO NOT EDIT BELOW THIS LINE.
 *
 * This is the glue that connects the class above to the simulator.  It is
 * plain C linkage because the simulator calls it through Verilog DPI.
 * ========================================================================== */

static Prefetcher g_prefetcher;

extern "C" void prefetcher_init(unsigned int line_bytes,
                                unsigned int sets,
                                unsigned int ways)
{
  g_prefetcher.reset(line_bytes, sets, ways);
}

extern "C" void prefetcher_tick(unsigned int acc_valid,
                                unsigned int acc_addr,
                                unsigned int acc_write,
                                unsigned int acc_miss,
                                unsigned int pf_busy,
                                unsigned int pf_dropped,
                                unsigned int *req_valid,
                                unsigned int *req_addr)
{
  Access acc;
  acc.valid    = (acc_valid  != 0);
  acc.addr     = acc_addr;
  acc.is_write = (acc_write  != 0);
  acc.is_miss  = (acc_miss   != 0);

  Status st;
  st.busy    = (pf_busy    != 0);
  st.dropped = (pf_dropped != 0);

  PrefetchReq r = g_prefetcher.tick(acc, st);

  *req_valid = r.valid ? 1u : 0u;
  *req_addr  = r.addr;
}
