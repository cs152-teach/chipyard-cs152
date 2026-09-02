// Soft multiply/divide for rv32i.

typedef unsigned int       u32;
typedef signed int         s32;
typedef unsigned long long u64;

// ---------------------------------------------------------------- 32-bit ---

u32 __mulsi3(u32 a, u32 b)
{
  u32 r = 0;
  while (b) {
    if (b & 1) r += a;
    a <<= 1;
    b >>= 1;
  }
  return r;
}

static u32 udivmod32(u32 num, u32 den, u32 *rem)
{
  u32 q = 0, r = 0;
  int i;
  if (den == 0) { if (rem) *rem = num; return ~0u; }   // undefined; do not trap
  for (i = 31; i >= 0; i--) {
    r = (r << 1) | ((num >> i) & 1u);
    if (r >= den) { r -= den; q |= (u32)1 << i; }
  }
  if (rem) *rem = r;
  return q;
}

u32 __udivsi3(u32 a, u32 b) { return udivmod32(a, b, 0); }
u32 __umodsi3(u32 a, u32 b) { u32 r; udivmod32(a, b, &r); return r; }

s32 __divsi3(s32 a, s32 b)
{
  int neg = 0;
  u32 ua, ub, q;
  if (a < 0) { a = -a; neg ^= 1; }
  if (b < 0) { b = -b; neg ^= 1; }
  ua = (u32)a; ub = (u32)b;
  q = udivmod32(ua, ub, 0);
  return neg ? -(s32)q : (s32)q;
}

s32 __modsi3(s32 a, s32 b)
{
  int neg = (a < 0);
  u32 ua, ub, r;
  if (a < 0) a = -a;
  if (b < 0) b = -b;
  ua = (u32)a; ub = (u32)b;
  udivmod32(ua, ub, &r);
  return neg ? -(s32)r : (s32)r;
}

// ---------------------------------------------------------------- 64-bit ---
// Built from 32-bit halves on purpose: a variable-distance 64-bit shift would
// itself compile to a libgcc call (__lshrdi3), which we would then also have to
// supply.

typedef struct { u32 lo, hi; } u64p;

static inline u64p split(u64 v) { u64p p; p.lo = (u32)v; p.hi = (u32)(v >> 32); return p; }
static inline u64  join(u64p p) { return ((u64)p.hi << 32) | p.lo; }

static u64 udivmod64(u64 num, u64 den, u64 *rem)
{
  u64p n = split(num), d = split(den);
  u64p q = { 0, 0 }, r = { 0, 0 };
  int i;

  if (d.lo == 0 && d.hi == 0) { if (rem) *rem = num; return ~(u64)0; }

  for (i = 63; i >= 0; i--) {
    u32 bit = (i >= 32) ? ((n.hi >> (i - 32)) & 1u) : ((n.lo >> i) & 1u);
    // r <<= 1 | bit
    r.hi = (r.hi << 1) | (r.lo >> 31);
    r.lo = (r.lo << 1) | bit;
    // if (r >= d) { r -= d; set bit i of q }
    if (r.hi > d.hi || (r.hi == d.hi && r.lo >= d.lo)) {
      u32 borrow = (r.lo < d.lo);
      r.lo -= d.lo;
      r.hi -= d.hi + borrow;
      if (i >= 32) q.hi |= (u32)1 << (i - 32);
      else         q.lo |= (u32)1 << i;
    }
  }
  if (rem) *rem = join(r);
  return join(q);
}

u64 __udivdi3(u64 a, u64 b) { return udivmod64(a, b, 0); }
u64 __umoddi3(u64 a, u64 b) { u64 r; udivmod64(a, b, &r); return r; }
