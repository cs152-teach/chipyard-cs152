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

#define NUM_COUNTERS 16
static uintptr_t counters[NUM_COUNTERS];
static char* counter_names[NUM_COUNTERS] = {
  "",
  "",
  "loads",
  "stores",
  "I$ miss",
  "D$ regular miss",
  "D$ prefetch miss",
  "D$ release",
  "ITLB miss",
  "DTLB miss",
  "L2 TLB miss",
  "branches",
  "mispredicts",
  "load-use interlock",
  "I$ blocked",
  "D$ blocked",
};

#define HPM_EVENTSET_BITS       8
#define HPM_EVENTSET_MASK       ((1U << HPM_EVENTSET_BITS) - 1)
#define HPM_EVENT(event, set)   ((1U << ((event) + HPM_EVENTSET_BITS)) | ((set) & HPM_EVENTSET_MASK))

/* CS152: replaced.  The original drove Rocket's hardware performance monitor
   (mhpmevent3.., hpmcounter3..), which Sodor does not have -- nPerfCounters = 0
   and every event is hard-disabled (sodor_internal_tile.scala:21), so those CSR
   accesses trap.  The L1D counters live in an MMIO block instead; see
   cs152_counters.h.

   Semantics are unchanged from the benchmark's point of view: setStats(1)
   starts a region of interest, setStats(0) ends it and prints the deltas.  That
   is what keeps the benchmark's own setup, its verification pass, and all HTIF
   traffic out of the measurement. */

static volatile unsigned int cs152_flush_scratch;

void setStats(int enable)
{
  if (enable) {
    /* Flush first so the region starts from a known cache state -- without it
       the measurement inherits whatever the setup code left behind.  The flush
       is asynchronous, so touch cacheable memory to wait for the walk.  Do NOT
       fold these into one write: the walk's writebacks would land in the
       just-zeroed counter. */
    cs152_ctr_wr(CS152_CTR_CONTROL, CS152_CTL_FLUSH);
    (void)cs152_flush_scratch;
    cs152_ctr_wr(CS152_CTR_CONTROL, CS152_CTL_ZERO);   /* zero, and start */
    return;
  }

  /* Freeze the counters before reading any of them.  This is an MMIO store on
     the counter port, so the cache never sees it and it is not counted.  Once
     frozen, every counter describes the same instant and it no longer matters
     where the compiler schedules its register spills -- which is why the reads
     below can be interleaved with the printfs instead of hoisted above them. */
  cs152_ctr_wr(CS152_CTR_CONTROL, CS152_CTL_STOP);

  unsigned int magic = cs152_ctr_rd(CS152_CTR_MAGIC);
  if (magic != CS152_CTR_MAGIC_VALUE) {
    /* Expected under spike, which has no counter hardware; a real problem on
       the simulator, where it means the two base addresses disagree. */
    printf("CS152: no counter block at 0x%x -- no counters for this run.\n",
           (unsigned int)CS152_CTR_BASE);
    printf("       Expected under spike.  On the simulator it means\n"
           "       CS152_CTR_BASE (cs152_counters.h) and CS152CounterBase\n"
           "       (CS152Params.scala) disagree.\n");
    return;
  }

  unsigned int hi   = cs152_ctr_rd(CS152_CTR_HITS);
  unsigned int mi   = cs152_ctr_rd(CS152_CTR_MISSES);
  unsigned int acc  = hi + mi;

  printf("=== CS152 L1D counters (region of interest, D-side only) ===\n");
  printf("  cycles      : %d\n", cs152_ctr_rd(CS152_CTR_CYCLES));
  printf("  loads       : %d\n", cs152_ctr_rd(CS152_CTR_LOADS));
  printf("  stores      : %d\n", cs152_ctr_rd(CS152_CTR_STORES));
  printf("  hits        : %d\n", hi);
  printf("  misses      : %d\n", mi);
  printf("  writebacks  : %d\n", cs152_ctr_rd(CS152_CTR_WRITEBACKS));
  if (acc)
    printf("  miss rate   : %d.%02d %%\n", mi * 100 / acc,
           (unsigned int)((unsigned long long)mi * 10000 / acc) % 100);
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
  int ret = main(0, 0);

  /* CS152: the original dumped the counters[] array here, which setStats()
     used to fill from Rocket's HPM CSRs.  Sodor has no HPM (nPerfCounters = 0),
     setStats() now reads the L1D MMIO block instead, and this loop printed 16
     lines of zeros.  Removed -- the region-of-interest block that setStats(0)
     prints is the real output. */

  /* CS152: report the verdict ourselves.  The test harness does print one, but
     only under +verbose -- which run-binary-fast deliberately omits -- and it
     writes to stderr, which the run rule does not capture.  So the harness
     verdict reaches neither the log nor, usually, the terminal.  Printing here
     puts it on stdout, directly below the counter block, which makes a saved
     log self-certifying: it carries both the geometry it ran and whether the
     result was right.  main() returns verify()'s value, which is 0 on success
     and otherwise the 1-based position of the first wrong element. */
  if (ret == 0)
    printf("*** PASSED ***\n");
  else
    printf("*** FAILED *** (first wrong element: %d)\n", ret);

  exit(ret);
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
      // should do something with padding so it's always 3 octits
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
  return 0; // incorrect return value, but who cares, anyway?
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
