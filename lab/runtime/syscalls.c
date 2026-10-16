// See LICENSE for license details.

#include <stdint.h>
#include <string.h>
#include <stdarg.h>
#include <stdio.h>
#include <limits.h>
#include <sys/signal.h>
#include "util.h"
#include "cs152_counters.h"

#define SYS_write 64

#undef strcmp

extern volatile uint64_t tohost;
extern volatile uint64_t fromhost;

static uintptr_t syscall(uintptr_t which, uint64_t arg0, uint64_t arg1, uint64_t arg2)
{
  volatile uint64_t magic_mem[8] __attribute__((aligned(64)));
  magic_mem[0] = which;
  magic_mem[1] = arg0;
  magic_mem[2] = arg1;
  magic_mem[3] = arg2;
  __sync_synchronize();

  tohost = (uintptr_t)magic_mem;
  while (fromhost == 0)
    ;
  fromhost = 0;

  __sync_synchronize();
  return magic_mem[0];
}

/* Sodor has no hardware performance-monitor CSRs (mhpmcounter*).  The L1D
   counters live in an MMIO block instead, one per core; see cs152_counters.h.

     setStats(1)       this core: flush its L1D, then zero and start its counters
     setStats(0)       this core: stop its counters and save a copy of them
     printStats(nc)    every core calls it; core 0 prints the table

   The copies live in memory so that core 0 can print every core's numbers:
   printf goes through tohost, which only one core at a time may use. */

/* crt.S parks any hart at or beyond the core count, so every mhartid that
   gets here indexes arrays of CS152_MAX_CORES safely. */

/* One table row per counter.  instret comes from the core's CSR, not the
   counter block. */
#define CS152_INSTRET 0xff
static const struct { const char *name; unsigned char off, total; } cs152_rows[] = {
  { "cycles",        CS152_CTR_CYCLES,     0 },
  { "instret",       CS152_INSTRET,        1 },
  { "loads",         CS152_CTR_LOADS,      1 },
  { "stores",        CS152_CTR_STORES,     1 },
  { "hits",          CS152_CTR_HITS,       1 },
  { "load misses",   CS152_CTR_LOADMISS,   1 },
  { "store misses",  CS152_CTR_STOREMISS,  1 },
  { "upgrades",      CS152_CTR_UPGRADES,   1 },
  { "writebacks",    CS152_CTR_WRITEBACKS, 1 },
  { "invalidations", CS152_CTR_INVALS,     1 },
  { "interventions", CS152_CTR_INTERVENE,  1 },
  { "bus-wait cyc",  CS152_CTR_BUSWAIT,    1 },
};
#define CS152_NROWS (sizeof(cs152_rows) / sizeof(cs152_rows[0]))

/* One cache line (or more) per core, so the copies do not false-share. */
struct cs152_stats { unsigned int v[CS152_NROWS]; } __attribute__((aligned(64)));
static struct cs152_stats cs152_stats[CS152_MAX_CORES];
static __thread unsigned long cs152_instret0;
static volatile unsigned int cs152_flush_scratch;

void setStats(int enable)
{
  if (enable) {
    /* Flush first so the region starts from a known (empty) cache state.
       The flush is asynchronous; the load from cacheable memory waits for it
       to finish.  FLUSH and ZERO are separate writes so that the flush's
       writebacks are not counted in the region. */
    cs152_ctr_wr(CS152_CTR_CONTROL, CS152_CTL_FLUSH);
    (void)cs152_flush_scratch;
    cs152_ctr_wr(CS152_CTR_CONTROL, CS152_CTL_ZERO);   /* zero, and start */
    cs152_instret0 = read_csr(minstret);
    return;
  }

  /* Stop first, then read, so every counter describes the same instant and
     the stores below (to cacheable memory) are not counted. */
  cs152_ctr_wr(CS152_CTR_CONTROL, CS152_CTL_STOP);
  unsigned long instret = read_csr(minstret) - cs152_instret0;

  unsigned int *v = cs152_stats[read_csr(mhartid)].v;
  for (unsigned r = 0; r < CS152_NROWS; r++)
    v[r] = cs152_rows[r].off == CS152_INSTRET ? instret : cs152_ctr_rd(cs152_rows[r].off);
}

void printStats(int nc)
{
  barrier(nc);                       /* every core has its copy in place */
  if (read_csr(mhartid) != 0)
    return;

  if (cs152_ctr_rd(CS152_CTR_MAGIC) != CS152_CTR_MAGIC_VALUE) {
    /* Expected under spike, which has no counter hardware.  On the RTL
       simulator it means CS152_CTR_BASE does not match the hardware. */
    printf("CS152: no counter block at 0x%x -- no counters for this run.\n",
           (unsigned int)CS152_CTR_BASE);
    return;
  }

  printf("=== CS152 L1D counters (region of interest, D-side only) ===\n");
  printf("  %-14s", "");
  for (int i = 0; i < nc; i++)
    printf("     core %d", i);
  if (nc > 1)
    printf("      total");
  printf("\n");
  for (unsigned r = 0; r < CS152_NROWS; r++) {
    unsigned int sum = 0;
    printf("  %-14s", cs152_rows[r].name);
    for (int i = 0; i < nc; i++) {
      printf(" %10d", cs152_stats[i].v[r]);
      sum += cs152_stats[i].v[r];
    }
    if (cs152_rows[r].total && nc > 1)
      printf(" %10d", sum);
    printf("\n");
  }
}

void __attribute__((noreturn)) finish(int ret)
{
  /* Print the result on stdout, directly below the counter table, so the run
     log records it.  ret is verify()'s value: 0 on success, otherwise the
     1-based position of the first wrong element. */
  if (ret == 0)
    printf("*** PASSED ***\n");
  else
    printf("*** FAILED *** (first wrong element: %d)\n", ret);
  exit(ret);
  __builtin_unreachable();
}

/* A round-numbered barrier from plain loads and stores (Sodor has no atomics).

   Each core owns one arrival flag and is the only core that ever writes it.
   Core 0 waits until every other core's flag shows this round, then publishes
   the round in `release`; the others spin on `release`.  A core cannot run
   ahead into the next round before core 0 has finished checking this one,
   because it only leaves after core 0 writes `release`, and core 0 writes it
   last.

   Correctness needs only coherence plus in-order, one-at-a-time memory
   operations, which each Sodor core provides.  Each flag is on its own cache
   line, so the flags do not false-share. */
struct cs152_flag { volatile int v; } __attribute__((aligned(64)));
static struct cs152_flag cs152_arrive[CS152_MAX_CORES];
static struct cs152_flag cs152_release;

void barrier(int ncores)
{
  int cid = read_csr(mhartid);
  int round = cs152_arrive[cid].v + 1;

  if (cid == 0) {
    for (int i = 1; i < ncores; i++)
      while (cs152_arrive[i].v != round)
        ;
    cs152_arrive[0].v = round;
    cs152_release.v = round;
  } else {
    cs152_arrive[cid].v = round;
    while (cs152_release.v != round)
      ;
  }
}

void __attribute__((noreturn)) tohost_exit(uintptr_t code)
{
  tohost = (code << 1) | 1;
  while (1);
}

uintptr_t __attribute__((weak)) handle_trap(uintptr_t cause, uintptr_t epc, uintptr_t regs[32])
{
  tohost_exit(1337);
}

void exit(int code)
{
  tohost_exit(code);
}

void abort()
{
  exit(128 + SIGABRT);
}

void printstr(const char* s)
{
  syscall(SYS_write, 1, (uintptr_t)s, strlen(s));
}

void __attribute__((weak)) thread_entry(int cid, int nc)
{
  // multi-threaded programs override this function.
  // for the case of single-threaded programs, only let core 0 proceed.
  while (cid != 0);
}

int __attribute__((weak)) main(int argc, char** argv)
{
  // single-threaded programs override this function.
  printstr("Implement main(), foo!\n");
  return -1;
}

static void init_tls()
{
  register void* thread_pointer asm("tp");
  extern char _tdata_begin, _tdata_end, _tbss_end;
  size_t tdata_size = &_tdata_end - &_tdata_begin;
  memcpy(thread_pointer, &_tdata_begin, tdata_size);
  size_t tbss_size = &_tbss_end - &_tdata_end;
  memset(thread_pointer + tdata_size, 0, tbss_size);
}

void _init(int cid, int nc)
{
  init_tls();
  thread_entry(cid, nc);

  // only single-threaded programs should ever get here.
  finish(main(0, 0));
}

#undef putchar
int putchar(int ch)
{
  static __thread char buf[64] __attribute__((aligned(64)));
  static __thread int buflen = 0;

  buf[buflen++] = ch;

  if (ch == '\n' || buflen == sizeof(buf))
  {
    syscall(SYS_write, 1, (uintptr_t)buf, buflen);
    buflen = 0;
  }

  return 0;
}

void printhex(uint64_t x)
{
  char str[17];
  int i;
  for (i = 0; i < 16; i++)
  {
    str[15-i] = (x & 0xF) + ((x & 0xF) < 10 ? '0' : 'a'-10);
    x >>= 4;
  }
  str[16] = 0;

  printstr(str);
}

static inline void printnum(void (*putch)(int, void**), void **putdat,
                    unsigned long long num, unsigned base, int width, int padc)
{
  unsigned digs[sizeof(num)*CHAR_BIT];
  int pos = 0;

  while (1)
  {
    digs[pos++] = num % base;
    if (num < base)
      break;
    num /= base;
  }

  while (width-- > pos)
    putch(padc, putdat);

  while (pos-- > 0)
    putch(digs[pos] + (digs[pos] >= 10 ? 'a' - 10 : '0'), putdat);
}

static unsigned long long getuint(va_list *ap, int lflag)
{
  if (lflag >= 2)
    return va_arg(*ap, unsigned long long);
  else if (lflag)
    return va_arg(*ap, unsigned long);
  else
    return va_arg(*ap, unsigned int);
}

static long long getint(va_list *ap, int lflag)
{
  if (lflag >= 2)
    return va_arg(*ap, long long);
  else if (lflag)
    return va_arg(*ap, long);
  else
    return va_arg(*ap, int);
}

static void vprintfmt(void (*putch)(int, void**), void **putdat, const char *fmt, va_list ap)
{
  register const char* p;
  const char* last_fmt;
  register int ch, err;
  unsigned long long num;
  int base, lflag, width, precision, altflag;
  char padc;

  while (1) {
    while ((ch = *(unsigned char *) fmt) != '%') {
      if (ch == '\0')
        return;
      fmt++;
      putch(ch, putdat);
    }
    fmt++;

    // Process a %-escape sequence
    last_fmt = fmt;
    padc = ' ';
    width = -1;
    precision = -1;
    lflag = 0;
    altflag = 0;
  reswitch:
    switch (ch = *(unsigned char *) fmt++) {

    // flag to pad on the right
    case '-':
      padc = '-';
      goto reswitch;
      
    // flag to pad with 0's instead of spaces
    case '0':
      padc = '0';
      goto reswitch;

    // width field
    case '1':
    case '2':
    case '3':
    case '4':
    case '5':
    case '6':
    case '7':
    case '8':
    case '9':
      for (precision = 0; ; ++fmt) {
        precision = precision * 10 + ch - '0';
        ch = *fmt;
        if (ch < '0' || ch > '9')
          break;
      }
      goto process_precision;

    case '*':
      precision = va_arg(ap, int);
      goto process_precision;

    case '.':
      if (width < 0)
        width = 0;
      goto reswitch;

    case '#':
      altflag = 1;
      goto reswitch;

    process_precision:
      if (width < 0)
        width = precision, precision = -1;
      goto reswitch;

    // long flag (doubled for long long)
    case 'l':
      lflag++;
      goto reswitch;

    // character
    case 'c':
      putch(va_arg(ap, int), putdat);
      break;

    // string
    case 's':
      if ((p = va_arg(ap, char *)) == NULL)
        p = "(null)";
      if (width > 0 && padc != '-')
        for (width -= strnlen(p, precision); width > 0; width--)
          putch(padc, putdat);
      for (; (ch = *p) != '\0' && (precision < 0 || --precision >= 0); width--) {
        putch(ch, putdat);
        p++;
      }
      for (; width > 0; width--)
        putch(' ', putdat);
      break;

    // (signed) decimal
    case 'd':
      num = getint(&ap, lflag);
      if ((long long) num < 0) {
        putch('-', putdat);
        num = -(long long) num;
      }
      base = 10;
      goto signed_number;

    // unsigned decimal
    case 'u':
      base = 10;
      goto unsigned_number;

    // (unsigned) octal
    case 'o':
      base = 8;
      goto unsigned_number;

    // pointer
    case 'p':
      static_assert(sizeof(long) == sizeof(void*));
      lflag = 1;
      putch('0', putdat);
      putch('x', putdat);
      /* fall through to 'x' */

    // (unsigned) hexadecimal
    case 'x':
      base = 16;
    unsigned_number:
      num = getuint(&ap, lflag);
    signed_number:
      printnum(putch, putdat, num, base, width, padc);
      break;

    // escaped '%' character
    case '%':
      putch(ch, putdat);
      break;
      
    // unrecognized escape sequence - just print it literally
    default:
      putch('%', putdat);
      fmt = last_fmt;
      break;
    }
  }
}

int printf(const char* fmt, ...)
{
  va_list ap;
  va_start(ap, fmt);

  vprintfmt((void*)putchar, 0, fmt, ap);

  va_end(ap);
  return 0; // does not return the number of characters printed
}

int sprintf(char* str, const char* fmt, ...)
{
  va_list ap;
  char* str0 = str;
  va_start(ap, fmt);

  void sprintf_putch(int ch, void** data)
  {
    char** pstr = (char**)data;
    **pstr = ch;
    (*pstr)++;
  }

  vprintfmt(sprintf_putch, (void**)&str, fmt, ap);
  *str = 0;

  va_end(ap);
  return str - str0;
}

void* memcpy(void* dest, const void* src, size_t len)
{
  if ((((uintptr_t)dest | (uintptr_t)src | len) & (sizeof(uintptr_t)-1)) == 0) {
    const uintptr_t* s = src;
    uintptr_t *d = dest;
    while (d < (uintptr_t*)(dest + len))
      *d++ = *s++;
  } else {
    const char* s = src;
    char *d = dest;
    while (d < (char*)(dest + len))
      *d++ = *s++;
  }
  return dest;
}

void* memset(void* dest, int byte, size_t len)
{
  if ((((uintptr_t)dest | len) & (sizeof(uintptr_t)-1)) == 0) {
    uintptr_t word = byte & 0xFF;
    word |= word << 8;
    word |= word << 16;
    word |= word << 16 << 16;

    uintptr_t *d = dest;
    while (d < (uintptr_t*)(dest + len))
      *d++ = word;
  } else {
    char *d = dest;
    while (d < (char*)(dest + len))
      *d++ = byte;
  }
  return dest;
}

size_t strlen(const char *s)
{
  const char *p = s;
  while (*p)
    p++;
  return p - s;
}

size_t strnlen(const char *s, size_t n)
{
  const char *p = s;
  while (n-- && *p)
    p++;
  return p - s;
}

int strcmp(const char* s1, const char* s2)
{
  unsigned char c1, c2;

  do {
    c1 = *s1++;
    c2 = *s2++;
  } while (c1 != 0 && c1 == c2);

  return c1 - c2;
}

char* strcpy(char* dest, const char* src)
{
  char* d = dest;
  while ((*d++ = *src++))
    ;
  return dest;
}

long atol(const char* str)
{
  long res = 0;
  int sign = 0;

  while (*str == ' ')
    str++;

  if (*str == '-' || *str == '+') {
    sign = *str == '-';
    str++;
  }

  while (*str) {
    res *= 10;
    res += *str++ - '0';
  }

  return sign ? -res : res;
}
