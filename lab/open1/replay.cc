//========================================================================
// CS152 Lab 3, Open-Ended Problem 1
//
// Offline driver for predictor_sw.cc.  Replays a committed branch trace
// captured from an RTL run through your predict_branch()/update_branch()
// and reports accuracy, so you can iterate in milliseconds instead of
// waiting for a simulation.
//
//   make -C lab/open1 check [TRACES="..."] [WORST=n]
//========================================================================

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <algorithm>
#include <string>
#include <unordered_map>
#include <vector>

// Provided by predictor_sw.cc, which is compiled in alongside this file.
extern "C" void initialize_branch_predictor();
extern "C" void predict_branch(unsigned long long ip, unsigned long long hist,
                               unsigned char *pred);
extern "C" void update_branch(unsigned long long ip, unsigned long long hist,
                              unsigned char taken);

// WithSWBPD sets globalHistoryLength = 32, so the hardware hands your model a
// 32-bit history.  Mask ours the same way.
static const int  GLOBAL_HISTORY_LENGTH = 32;
static const unsigned long long HIST_MASK =
    (GLOBAL_HISTORY_LENGTH >= 64) ? ~0ULL : ((1ULL << GLOBAL_HISTORY_LENGTH) - 1);

struct SiteStats { unsigned long long count = 0, mispredicted = 0; };

struct Result {
  unsigned long long branches = 0;
  unsigned long long mispredicted = 0;
  std::unordered_map<unsigned long long, SiteStats> sites;
};

static bool replay(const char *path, Result &r)
{
  FILE *f = fopen(path, "r");
  if (!f) { fprintf(stderr, "replay: cannot open %s\n", path); return false; }

  unsigned long long hist = 0;
  char line[256];
  while (fgets(line, sizeof(line), f)) {
    char pc_str[64], type[16];
    int taken;
    if (sscanf(line, "%63s %d %15s", pc_str, &taken, type) != 3)
      continue;
    // Only conditional branches reach the software predictor: the hardware
    // gates update_branch() on the fetch packet's branch mask, and jalr
    // targets come from the BTB/RAS, not from your direction prediction.
    if (strcmp(type, "br") != 0)
      continue;

    unsigned long long pc = strtoull(pc_str, NULL, 16);
    unsigned char pred = 0;
    predict_branch(pc, hist, &pred);

    const bool wrong = ((pred != 0) != (taken != 0));
    r.branches++;
    r.mispredicted += wrong;
    SiteStats &s = r.sites[pc];
    s.count++;
    s.mispredicted += wrong;

    // The hardware updates with the history as of the branch itself, then
    // appends this outcome for the next one.
    update_branch(pc, hist, (unsigned char)(taken != 0));
    hist = ((hist << 1) | (unsigned long long)(taken != 0)) & HIST_MASK;
  }

  fclose(f);
  return true;
}

static void print_worst(const Result &r, int n)
{
  std::vector<std::pair<unsigned long long, SiteStats>> v(r.sites.begin(), r.sites.end());
  std::sort(v.begin(), v.end(), [](const auto &a, const auto &b) {
    return a.second.mispredicted > b.second.mispredicted;
  });
  printf("  worst branch sites (by mispredictions):\n");
  for (int i = 0; i < n && i < (int)v.size(); i++) {
    if (v[i].second.mispredicted == 0) break;
    printf("    %016llx  %llu/%llu  %.4f\n", v[i].first,
           v[i].second.mispredicted, v[i].second.count,
           (double)v[i].second.mispredicted / (double)v[i].second.count);
  }
}

int main(int argc, char **argv)
{
  int worst = 0;
  std::vector<const char *> traces;
  for (int i = 1; i < argc; i++) {
    if (!strcmp(argv[i], "-w") || !strcmp(argv[i], "--worst")) {
      worst = (i + 1 < argc) ? atoi(argv[++i]) : 10;
    } else {
      traces.push_back(argv[i]);
    }
  }
  if (traces.empty()) {
    fprintf(stderr,
            "usage: %s [-w N] <trace> [<trace> ...]\n"
            "  Replays each trace through predictor_sw.cc and reports accuracy.\n"
            "  -w N  also list the N branch sites with the most mispredictions.\n",
            argv[0]);
    return 1;
  }

  printf("%-14s %12s %14s %10s\n", "trace", "branches", "mispredicted", "accuracy");
  unsigned long long all_br = 0, all_mp = 0;
  for (const char *t : traces) {
    // Each trace starts from a cold predictor, exactly as a simulation would.
    initialize_branch_predictor();

    Result r;
    if (!replay(t, r)) return 1;
    if (r.branches == 0) { printf("%-14s %12s\n", t, "(no branches)"); continue; }

    std::string name(t);
    size_t slash = name.find_last_of('/');
    if (slash != std::string::npos) name = name.substr(slash + 1);

    printf("%-14s %12llu %14llu %9.2f%%\n", name.c_str(), r.branches, r.mispredicted,
           100.0 * (1.0 - (double)r.mispredicted / (double)r.branches));
    if (worst) print_worst(r, worst);

    all_br += r.branches;
    all_mp += r.mispredicted;
  }
  if (traces.size() > 1 && all_br)
    printf("%-14s %12llu %14llu %9.2f%%\n", "TOTAL", all_br, all_mp,
           100.0 * (1.0 - (double)all_mp / (double)all_br));
  return 0;
}
