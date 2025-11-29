RE2 PUBLIC API - IMPLEMENTATION CHECKLIST FOR libre2-java WRAPPER
==================================================================

SECTION 1: MUST IMPLEMENT (Core API - 40 methods)
================================================

CONSTRUCTORS (4 - all required for implicit conversion):
□ RE2(const char* pattern)
□ RE2(const std::string& pattern)
□ RE2(absl::string_view pattern)
□ RE2(absl::string_view pattern, const Options& options)

DESTRUCTOR (1):
□ ~RE2()

STATUS/VALIDATION (5):
□ bool ok() const
□ const std::string& pattern() const
□ const std::string& error() const
□ ErrorCode error_code() const
□ const std::string& error_arg() const

PATTERN ANALYSIS (7):
□ int NumberOfCapturingGroups() const
□ const std::map<std::string, int>& NamedCapturingGroups() const
□ const std::map<int, std::string>& CapturingGroupNames() const
□ int ProgramSize() const
□ int ReverseProgramSize() const
□ int ProgramFanout(std::vector<int>* histogram) const
□ int ReverseProgramFanout(std::vector<int>* histogram) const

MATCHING - LOW-LEVEL (4 - these are foundational):
□ static bool FullMatchN(absl::string_view text, const RE2& re, const Arg* const args[], int n)
□ static bool PartialMatchN(absl::string_view text, const RE2& re, const Arg* const args[], int n)
□ static bool ConsumeN(absl::string_view* input, const RE2& re, const Arg* const args[], int n)
□ static bool FindAndConsumeN(absl::string_view* input, const RE2& re, const Arg* const args[], int n)

MATCHING - HIGH-LEVEL (4 - variadic templates):
□ template <typename... A> static bool FullMatch(absl::string_view text, const RE2& re, A&&... a)
□ template <typename... A> static bool PartialMatch(absl::string_view text, const RE2& re, A&&... a)
□ template <typename... A> static bool Consume(absl::string_view* input, const RE2& re, A&&... a)
□ template <typename... A> static bool FindAndConsume(absl::string_view* input, const RE2& re, A&&... a)

MATCHING - GENERIC (1 - lowest level, most control):
□ bool Match(absl::string_view text, size_t startpos, size_t endpos, Anchor re_anchor, absl::string_view* submatch, int nsubmatch) const

REPLACEMENT (3):
□ static bool Replace(std::string* str, const RE2& re, absl::string_view rewrite)
□ static int GlobalReplace(std::string* str, const RE2& re, absl::string_view rewrite)
□ static bool Extract(absl::string_view text, const RE2& re, absl::string_view rewrite, std::string* out)

REWRITE VALIDATION (3):
□ bool CheckRewriteString(absl::string_view rewrite, std::string* error) const
□ static int MaxSubmatch(absl::string_view rewrite)
□ bool Rewrite(std::string* out, absl::string_view rewrite, const absl::string_view* vec, int veclen) const

CONFIGURATION (1):
□ const Options& options() const

UTILITIES (3):
□ static std::string QuoteMeta(absl::string_view unquoted)
□ template <typename T> static Arg CRadix(T* ptr)
□ template <typename T> static Arg Hex(T* ptr)
□ template <typename T> static Arg Octal(T* ptr)

GENERIC LOOKUP (1):
□ bool PossibleMatchRange(std::string* min, std::string* max, int maxlen) const


SECTION 2: SHOULD IMPLEMENT (Advanced Features)
==============================================

RE2::Options (30 methods):
□ Default construction and CannedOptions constructor
□ Property getters: max_mem(), encoding(), posix_syntax(), longest_match()
□ Property getters: log_errors(), literal(), never_nl(), dot_nl()
□ Property getters: never_capture(), case_sensitive(), perl_classes()
□ Property getters: word_boundary(), one_line()
□ Property setters: set_max_mem(), set_encoding(), set_posix_syntax()
□ Property setters: set_longest_match(), set_log_errors(), set_literal()
□ Property setters: set_never_nl(), set_dot_nl(), set_never_capture()
□ Property setters: set_case_sensitive(), set_perl_classes()
□ Property setters: set_word_boundary(), set_one_line()
□ Copy() and ParseFlags() methods

RE2::Arg (5 methods):
□ Arg() - empty constructor
□ Arg(std::nullptr_t ptr) - null pointer constructor
□ template <typename T> Arg(T* ptr) - pointer-based constructor
□ template <typename T> Arg(T* ptr, Parser parser) - custom parser constructor
□ bool Parse(const char* str, size_t n) const - parse method

LazyRE2 (3 methods):
□ RE2& operator*() const - dereference operator
□ RE2* operator->() const - pointer operator
□ RE2* get() const - explicit getter


SECTION 3: DO NOT IMPLEMENT (Deleted/Internal - 5 methods)
========================================================

EXPLICITLY DELETED (Never expose these):
✗ RE2(const RE2&) - copy constructor deleted
✗ RE2& operator=(const RE2&) - copy assignment deleted
✗ RE2(RE2&&) - move constructor deleted
✗ RE2& operator=(RE2&&) - move assignment deleted

INTERNAL ONLY:
✗ re2::Regexp* Regexp() const - internal implementation detail
✗ static void FUZZING_ONLY_set_maximum_global_replace_count(int i) - testing only


SECTION 4: ENUMERATIONS TO EXPOSE
=================================

ErrorCode (15 values):
□ NoError = 0
□ ErrorInternal, ErrorBadEscape, ErrorBadCharClass
□ ErrorBadCharRange, ErrorMissingBracket, ErrorMissingParen
□ ErrorUnexpectedParen, ErrorTrailingBackslash, ErrorRepeatArgument
□ ErrorRepeatSize, ErrorRepeatOp, ErrorBadPerlOp, ErrorBadUTF8
□ ErrorBadNamedCapture, ErrorPatternTooLarge

CannedOptions (4 values):
□ DefaultOptions = 0
□ Latin1, POSIX, Quiet

Anchor (3 values):
□ UNANCHORED, ANCHOR_START, ANCHOR_BOTH

Encoding (2 values):
□ EncodingUTF8 = 1, EncodingLatin1


IMPLEMENTATION STRATEGY
=======================

PHASE 1 - Core API (40 methods) - Priority 1:
⚡ Implement first for 89% functionality:
  - 4 constructors + destructor
  - 5 status methods
  - 7 pattern analysis methods
  - 8 matching methods (N-variants + variadic templates)
  - 3 replacement methods
  - 3 rewrite validation methods
  - 1 generic Match() method
  - 1 PossibleMatchRange() method

PHASE 2 - Convenience Layer (35 methods) - Priority 2:
  - 30 Options getter/setter pairs
  - 5 Arg constructor/parsing methods
  - 3 LazyRE2 operator methods

PHASE 3 - Advanced Features - Priority 3:
  - Program fanout analysis
  - Rewrite string validation edge cases
  - Generic Match() optimization hints

PHASE 4 - Testing & Optimization - Priority 4:
  - Unit tests for each method
  - Integration tests with Cassandra patterns
  - Performance benchmarks
  - Memory usage profiling


CRITICAL JNI INTERCEPTION POINTS
================================

These methods MUST have JNI hooks for caching/optimization:

1. All 4 constructors
   Reason: Pattern compilation is expensive
   Action: Cache compiled patterns by (pattern_string + options_hash)
   Impact: 100-1000x speedup for repeated patterns

2. FullMatchN / PartialMatchN
   Reason: Hot path for matching operations
   Action: Profile with real Cassandra query patterns
   Impact: Consider vectorized matching or batching

3. Consume / FindAndConsume
   Reason: Sequential scanning performance
   Action: May benefit from batch processing
   Impact: Optimize for multi-pattern scanning loops

4. Replace / GlobalReplace
   Reason: Memory mutation operations
   Action: Careful JNI string marshalling
   Impact: Minimize garbage collection pressure

5. Options construction
   Reason: Affects all pattern compilation
   Action: Cache common option sets
   Impact: Faster pattern instantiation


TESTING REQUIREMENTS
====================

For each method, verify:
□ NULL input handling (graceful degradation)
□ Empty string handling (edge case)
□ Very large inputs (megabytes - stress test)
□ Unicode/UTF-8 edge cases (multi-byte characters)
□ Memory leaks (valgrind, asan, clang-sanitizers)
□ Thread safety (concurrent access from multiple threads)
□ ARM64 correctness (test on ARM64 hardware/emulator)
□ JNI string marshalling (Java UTF-8 ↔ C++)
□ JVM heap pressure (excessive allocations?)
□ GC interaction (any full GC pauses?)


EXPECTED OUTCOMES
=================

After implementing this API:

✓ Direct 1:1 delegation to RE2 C++ library
✓ No performance degradation vs native RE2
✓ All Google RE2 functionality available to Java
✓ Proper error handling and validation
✓ Thread-safe concurrent access
✓ Production-ready for Apache Cassandra
✓ Better performance than google/re2j


EFFORT ESTIMATION
=================

Assuming 1 senior C++/JNI developer:

Phase 1 (Core API):          8-16 hours
Phase 2 (Options/Arg):       4-8 hours
Phase 3 (Advanced):          4-8 hours
Phase 4 (Testing/Perf):      16-32 hours
Documentation:               8-16 hours
Build/CMake integration:     4-8 hours
---
TOTAL:                       52-104 hours (1-2 weeks)


MAINTENANCE NOTES
=================

- This list is based on RE2 branch 2025-11-05
- If upstream RE2 adds new methods, add them to Phase 3
- Keep sync with re2/re2.h as source of truth
- Document any deviation from original RE2 API
- Test against multiple RE2 versions if needed
- Monitor RE2 GitHub issues for breaking changes


KEY SUCCESS CRITERIA
====================

1. ✓ All 40 core methods implemented and tested
2. ✓ Zero memory leaks (asan clean)
3. ✓ Thread-safe across 100+ concurrent threads
4. ✓ ARM64 safe (explicit memory_order where needed)
5. ✓ JNI string marshalling zero-copy where possible
6. ✓ Performance >= native RE2 (no JNI overhead)
7. ✓ Cassandra integration seamless
8. ✓ Documentation complete and accurate
9. ✓ All error cases handled gracefully
10. ✓ Production-ready quality

---
Generated: 2025-11-29
Source: Google RE2 (2025-11-05 branch)
Reference: re2/re2.h and re2/re2.cc
Purpose: Complete API catalog for libre2-java JNI wrapper
