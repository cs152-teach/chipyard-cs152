/* CS152 Lab 2, open-ended 2 -- pf-eval: does my policy predict the right addresses?
 *
 * No RTL, no simulator, no clock.  This links your prefetcher.cc against a host
 * model of the cache and replays each kernel's access stream.
 */

#include <stdio.h>
#include <stdint.h>
#include <string.h>
#include <vector>
#include <string>
#include <set>

/* ---- the cache, matching CS152Lab2PrefetchConfig ---- */
/* One access. */
struct Acc { uint32_t addr; bool write; };

// 
static std::vector<unsigned> shuffled(unsigned n, unsigned seed)
{
  std::vector<unsigned> o(n);
  for (unsigned i = 0; i < n; i++) o[i] = i;
  unsigned rng = seed;
  for (unsigned i = n - 1; i > 0; i--) {
    rng = rng * 1103515245u + 12345u;
    unsigned j = (rng >> 16) % (i + 1);
    unsigned t = o[i]; o[i] = o[j]; o[j] = t;
  }
  return o;
}

static const unsigned LINE = 32;
static const unsigned SETS = 64;
static const unsigned WAYS = 2;

extern "C" void prefetcher_init(unsigned, unsigned, unsigned);
extern "C" void prefetcher_tick(unsigned, unsigned, unsigned, unsigned,
                                unsigned, unsigned, unsigned *, unsigned *);

struct Cache {
  uint32_t tag[SETS][WAYS];
  bool     valid[SETS][WAYS];
  uint8_t  age[SETS][WAYS];

  void reset() { memset(this, 0, sizeof(*this)); }
  static unsigned setOf(uint32_t line) { return line % SETS; }
  static uint32_t tagOf(uint32_t line) { return line / SETS; }

  int find(uint32_t line) const {
    unsigned s = setOf(line);
    for (unsigned w = 0; w < WAYS; w++)
      if (valid[s][w] && tag[s][w] == tagOf(line)) return (int)w;
    return -1;
  }
  void touch(uint32_t line, unsigned w) {
    unsigned s = setOf(line);
    for (unsigned k = 0; k < WAYS; k++) if (age[s][k] < age[s][w]) age[s][k]++;
    age[s][w] = 0;
  }
  void insert(uint32_t line) {
    unsigned s = setOf(line), victim = 0;
    bool found = false;
    for (unsigned w = 0; w < WAYS; w++) if (!valid[s][w]) { victim = w; found = true; break; }
    if (!found) for (unsigned w = 0; w < WAYS; w++) if (age[s][w] == WAYS - 1) { victim = w; break; }
    tag[s][victim] = tagOf(line); valid[s][victim] = true;
    touch(line, victim);
  }
};

struct Stats {
  unsigned long accesses = 0;   /* demand accesses in the trace */
  unsigned long misses   = 0;   /* ...that miss with no prefetching */
  unsigned long requests = 0;   /* prefetches you asked for */
  unsigned long covered  = 0;   /* MISSES a request of yours preceded */
  unsigned long useless  = 0;   /* requests no later access ever consumed */
};

static Stats evaluate(const std::vector<Acc> &trace)
{
  Cache c; c.reset();
  prefetcher_init(LINE, SETS, WAYS);

  Stats st;
  std::set<uint32_t> outstanding;

  for (size_t k = 0; k < trace.size(); k++) {
    uint32_t addr = trace[k].addr;
    uint32_t line = addr / LINE;
    st.accesses++;

    int way = c.find(line);
    bool hit = (way >= 0);
    if (!hit) st.misses++;

    std::set<uint32_t>::iterator o = outstanding.find(line);
    if (o != outstanding.end()) {
      if (!hit) st.covered++;
      outstanding.erase(o);
    }

    if (hit) c.touch(line, (unsigned)way); else c.insert(line);

    unsigned rv = 0, ra = 0;
    /* busy and dropped are always 0: this tool models no port.  See the header. */
    prefetcher_tick(1u, addr, trace[k].write ? 1u : 0u, hit ? 0u : 1u,
                    0u, 0u, &rv, &ra);
    if (!rv) continue;

    uint32_t pl = ra / LINE;
    if (pl == line) continue;                    /* asking for what you just got */
    if (outstanding.count(pl)) continue;         /* already asked, not yet used */
    st.requests++;
    outstanding.insert(pl);
  }

  st.useless = outstanding.size();               /* never consumed */
  return st;
}

/* ---- the kernels' access streams ---- */
static const uint32_t BASE = 0x80002000u;

static std::vector<Acc> t_stream1()
{
  std::vector<Acc> t;
  for (unsigned i = 0; i < 4096; i++) t.push_back({ BASE + 4 * i, false });
  return t;
}
static std::vector<Acc> t_stride_big()
{
  const unsigned SW = 16, NSTEP = 512, N = SW * NSTEP;
  std::vector<Acc> t;
  for (unsigned i = 0; i < N; i += SW) t.push_back({ BASE + 4 * i, false });
  for (int j = (int)N - (int)SW; j >= 0; j -= (int)SW)
    t.push_back({ BASE + 4 * (unsigned)j, false });
  return t;
}
static std::vector<Acc> t_tile()
{
  const unsigned ROWW = 256, NROW = 64, TILEW = 32;
  std::vector<Acc> t;
  for (unsigned tt = 0; tt < ROWW; tt += TILEW)
    for (unsigned r = 0; r < NROW; r++)
      for (unsigned i = 0; i < TILEW; i++)
        t.push_back({ BASE + 4 * (r * ROWW + tt + i), false });
  return t;
}
static std::vector<Acc> t_spatial()
{
  const unsigned REGION_W = 64, NREG = 256, NOFF = 4;
  const unsigned off[NOFF] = { 0, 12, 40, 52 };   /* lines 0,1,5,6 -- see the kernel */
  std::vector<Acc> t;
  for (unsigned r = 0; r < NREG; r++) {
    unsigned v = r;
    v = ((v & 0x55u) << 1) | ((v >> 1) & 0x55u);
    v = ((v & 0x33u) << 2) | ((v >> 2) & 0x33u);
    v = ((v & 0x0fu) << 4) | ((v >> 4) & 0x0fu);
    unsigned base = (v & 0xffu) * REGION_W;
    for (unsigned o = 0; o < NOFF; o++)
      t.push_back({ BASE + 4 * (base + off[o]), false });
  }
  return t;
}

static std::vector<Acc> t_temporal()
{
  /* Matches kernels/temporal.c: one shuffled ring, four laps. */
  const unsigned NODES = 1024, STRIDE = 16, LAPS = 4;
  std::vector<unsigned> ord = shuffled(NODES, 747796405u);
  /* Walk the ring from node 0's position, exactly as the kernel does. */
  std::vector<unsigned> nextof(NODES);
  for (unsigned i = 0; i < NODES; i++) nextof[ord[i]] = ord[(i + 1) % NODES];
  std::vector<Acc> t;
  unsigned cur = 0;
  for (unsigned lap = 0; lap < LAPS; lap++)
    for (unsigned i = 0; i < NODES; i++) {
      t.push_back({ BASE + 4 * (cur * STRIDE), false });
      cur = nextof[cur];
    }
  return t;
}

static std::vector<Acc> t_chase()
{
  const unsigned NODES = 384, STRIDE = 64;   /* one node per 256 B region */
  std::vector<unsigned> ord = shuffled(NODES, 22695477u);
  std::vector<unsigned> nextof(NODES);
  for (unsigned i = 0; i < NODES; i++) nextof[ord[i]] = ord[(i + 1) % NODES];
  std::vector<Acc> t;
  unsigned cur = 0;
  for (unsigned i = 0; i < NODES; i++) {
    t.push_back({ BASE + 4 * (cur * STRIDE), false });
    cur = nextof[cur];
  }
  return t;
}

struct Kernel { const char *name; std::vector<Acc> (*gen)(); };

int main(int argc, char **argv)
{
  Kernel ks[] = {
    { "stream1",    t_stream1    },
    { "spatial",    t_spatial    },
    { "temporal",   t_temporal   },
    { "stride_big", t_stride_big },
    { "tile",       t_tile       },
    { "chase",      t_chase      },
  };

  int selected = 0;
  for (int a = 1; a < argc; a++) {
    bool known = false;
    for (Kernel &k : ks) if (!strcmp(k.name, argv[a])) { known = true; break; }
    if (!known) {
      fprintf(stderr, "pf-eval: no such kernel: %s\nknown:", argv[a]);
      for (Kernel &k : ks) fprintf(stderr, " %s", k.name);
      fprintf(stderr, "\n");
      return 2;
    }
    selected++;
  }

  printf("pf-eval -- address prediction only.  No cycles, no port: see the header.\n\n");
  printf("%-11s %8s %9s %9s %8s %9s %9s\n",
         "kernel", "misses", "requests", "covered", "useless", "accuracy", "coverage");
  printf("%-11s %8s %9s %9s %8s %9s %9s\n",
         "------", "------", "--------", "-------", "-------", "--------", "--------");

  for (Kernel &k : ks) {
    if (selected) {
      bool want = false;
      for (int a = 1; a < argc; a++) if (!strcmp(k.name, argv[a])) { want = true; break; }
      if (!want) continue;
    }
    std::vector<Acc> tr = k.gen();
    Stats s = evaluate(tr);
    double acc = s.requests ? 100.0 * (double)(s.requests - s.useless) / (double)s.requests : 0.0;
    double cov = s.misses ? 100.0 * (double)s.covered / (double)s.misses : 0.0;
    printf("%-11s %8lu %9lu %9lu %8lu %8.1f%% %8.1f%%\n",
           k.name, s.misses, s.requests, s.covered, s.useless, acc, cov);
  }
  return 0;
}
