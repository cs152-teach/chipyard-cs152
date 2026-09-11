// CS152 Lab 2 -- DPI harness for the C++ prefetcher model.

import "DPI-C" function void prefetcher_init(input int unsigned line_bytes,
                                             input int unsigned sets,
                                             input int unsigned ways);

import "DPI-C" function void prefetcher_tick(input  int unsigned acc_valid,
                                             input  int unsigned acc_addr,
                                             input  int unsigned acc_write,
                                             input  int unsigned acc_miss,
                                             input  int unsigned pf_busy,
                                             input  int unsigned pf_dropped,
                                             output int unsigned req_valid,
                                             output int unsigned req_addr);

module ModelPrefetcher #(
   parameter LINE_BYTES = 32,
   parameter SETS       = 64,
   parameter WAYS       = 2
) (
   input         clock,
   input         reset,

   input         accValid,
   input  [31:0] accAddr,
   input         accWrite,
   input         accMiss,

   input         busy,
   input         dropped,

   output        req_valid,
   output [31:0] req_bits
);

   int unsigned _req_valid;
   int unsigned _req_addr;

   reg          reg_req_valid;
   reg   [31:0] reg_req_addr;

   assign req_valid = reg_req_valid;
   assign req_bits  = reg_req_addr;

   initial begin
      _req_valid = 0;
      _req_addr  = 0;
      prefetcher_init(LINE_BYTES, SETS, WAYS);
   end

   always @(posedge clock) begin
      if (reset) begin
         reg_req_valid <= 1'b0;
         reg_req_addr  <= 32'b0;
      end else begin
         prefetcher_tick({31'b0, accValid},
                         accAddr,
                         {31'b0, accWrite},
                         {31'b0, accMiss},
                         {31'b0, busy},
                         {31'b0, dropped},
                         _req_valid,
                         _req_addr);
         reg_req_valid <= _req_valid[0];
         reg_req_addr  <= _req_addr;
      end
   end

endmodule
