########################################################################
# CS152 Lab 3: Open-Ended Problem 1                                    #
########################################################################

workdir := $(dir $(realpath $(firstword $(MAKEFILE_LIST))))
workdir := $(workdir:/=)
basedir := $(workdir)/../..

# Offline replay harness: compiles your predictor model together with replay.cc
# into a host binary, so you can validate a design against a captured branch
# trace in milliseconds instead of waiting for an RTL simulation.

predictor_src := $(basedir)/generators/boom/src/main/resources/csrc/predictor_sw.cc
replay_bin    := $(workdir)/replay
TRACES        ?= $(wildcard $(workdir)/traces/*.trace)

CXX      ?= g++
CXXFLAGS ?= -O2 -std=c++17 -Wall

.PHONY: replay
replay: $(replay_bin)

$(replay_bin): $(workdir)/replay.cc $(predictor_src)
	$(CXX) $(CXXFLAGS) -o $@ $^

# make check [TRACES="a.trace b.trace"] [WORST=10]
.PHONY: check
check: $(replay_bin)
	@if [ -z "$(TRACES)" ]; then \
	  echo "No traces found. Capture one with BPD_TRACE=<file> (see the lab handout),"; \
	  echo "or point this target at one explicitly:"; \
	  echo "  make check TRACES=\"path/to/median.trace\""; \
	  exit 1; \
	fi
	$(replay_bin) $(if $(WORST),-w $(WORST)) $(TRACES)

.PHONY: clean
clean:
	rm -f -- $(replay_bin) $(zip_obj)

# Autograder submission

zip_obj := upload.zip
zip_srcs := $(basedir)/generators/boom/src/main/resources/csrc/predictor_sw.cc

.PHONY: zip
zip: $(zip_srcs)
	rm -f -- $(zip_obj)
	zip -j $(zip_obj) $^


.SUFFIXES: # Disable built-in suffix rules