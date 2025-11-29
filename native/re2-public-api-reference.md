# RE2 C++ Public API - Complete Reference

**Source:** Google RE2 (Branch: 2025-11-05)  
**Header:** `re2/re2.h`  
**Implementation:** `re2/re2.cc`  
**Purpose:** Definitive API catalog for building JNI wrapper and API delegation

---

## Quick Summary

- **Total Public Methods:** 82 (including nested classes)
- **Main Classes:** RE2, RE2::Options, RE2::Arg, RE2::Set, LazyRE2
- **Key Enums:** ErrorCode, CannedOptions, Anchor, Encoding
- **Pattern:** Static and instance methods; template-heavy

---

## RE2 Class - Core API

### 1. Constructors & Destructors (5 methods)

Must implement all 4 constructor variants + destructor:

```cpp
// Constructor variants - handle different input types
RE2(const char* pattern);
RE2(const std::string& pattern);
RE2(absl::string_view pattern);
RE2(absl::string_view pattern, const Options& options);

// Destructor
~RE2();
```

**Implementation Notes:**
- All 4 constructor forms required for implicit conversions
- RE2 handles NULL pattern gracefully (converts to "")
- Expensive object: should use std::shared_ptr<RE2> in wrappers

---

### 2. Non-Copyable & Non-Movable (4 methods)

These are explicitly deleted - **do NOT expose in wrapper**:

```cpp
RE2(const RE2&) = delete;           // No copy constructor
RE2& operator=(const RE2&) = delete; // No copy assignment
RE2(RE2&&) = delete;                // No move constructor
RE2& operator=(RE2&&) = delete;     // No move assignment
```

**Rationale:** RE2 objects are thread-safe and logically immutable. Prevent accidental copies.

---

### 3. Status & Metadata Methods (5 methods)

Query error state and pattern information:

```cpp
// Validity check
bool ok() const;

// Pattern retrieval
const std::string& pattern() const;

// Error information (when ok() == false)
const std::string& error() const;
ErrorCode error_code() const;
const std::string& error_arg() const;  // Fragment showing error location
```

**Usage Pattern:**
```cpp
RE2 re("pattern");
if (!re.ok()) {
    std::cerr << "Error: " << re.error() 
              << " at " << re.error_arg() << std::endl;
}
```

---

### 4. Program Analysis Methods (4 methods)

Analyze compiled program size and structure:

```cpp
// Size of compiled program (approximate cost measure)
int ProgramSize() const;
int ReverseProgramSize() const;

// Fanout histogram (branching factor analysis)
int ProgramFanout(std::vector<int>* histogram) const;
int ReverseProgramFanout(std::vector<int>* histogram) const;
```

**Use Case:** Performance tuning, pattern complexity estimation

---

### 5. Pattern Analysis Methods (4 methods)

Analyze pattern structure without matching:

```cpp
// Number of capturing groups (for memory pre-allocation)
int NumberOfCapturingGroups() const;

// Named group mappings
const std::map<std::string, int>& NamedCapturingGroups() const;
const std::map<int, std::string>& CapturingGroupNames() const;

// Range of possible matches
bool PossibleMatchRange(std::string* min, std::string* max, int maxlen) const;
```

**CRITICAL:** Named group maps only valid while RE2 object exists!

---

### 6. Low-Level Matching - Array Methods (4 methods)

Core matching with runtime-determined argument count:

```cpp
// Full match (entire text must match pattern)
static bool FullMatchN(absl::string_view text, const RE2& re,
                       const Arg* const args[], int n);

// Partial match (pattern can match substring)
static bool PartialMatchN(absl::string_view text, const RE2& re,
                          const Arg* const args[], int n);

// Consume from front (advance string_view past match)
static bool ConsumeN(absl::string_view* input, const RE2& re,
                     const Arg* const args[], int n);

// Find and consume (search, don't require front anchor)
static bool FindAndConsumeN(absl::string_view* input, const RE2& re,
                            const Arg* const args[], int n);
```

**Critical Implementation Detail:**
- `args` is array of `const Arg*` pointers
- `n` is number of args (== capturing groups)
- These are the foundation for high-level APIs

---

### 7. High-Level Matching - Variadic Templates (4 methods)

User-friendly interface with variable arguments:

```cpp
// Matches entire text exactly
template <typename... A>
static bool FullMatch(absl::string_view text, const RE2& re, A&&... a);

// Matches any substring of text
template <typename... A>
static bool PartialMatch(absl::string_view text, const RE2& re, A&&... a);

// Advances string_view past match at beginning
template <typename... A>
static bool Consume(absl::string_view* input, const RE2& re, A&&... a);

// Finds match anywhere, advances string_view past it
template <typename... A>
static bool FindAndConsume(absl::string_view* input, const RE2& re, A&&... a);
```

**CRITICAL FOR JNI:** These variadic templates use `std::forward<A>` to build Arg objects.

---

### 8. String Replacement Methods (3 methods)

Pattern-based string transformation:

```cpp
// Replace first match
static bool Replace(std::string* str,
                   const RE2& re,
                   absl::string_view rewrite);

// Replace all non-overlapping matches, return count
static int GlobalReplace(std::string* str,
                        const RE2& re,
                        absl::string_view rewrite);

// Extract and rewrite (non-modifying version)
static bool Extract(absl::string_view text,
                   const RE2& re,
                   absl::string_view rewrite,
                   std::string* out);
```

**Rewrite Format:**
- `\0` = entire match
- `\1`, `\2`, ... = capturing groups
- `\\` = literal backslash

---

### 9. Utility Methods (1 method)

Escape special regex characters:

```cpp
static std::string QuoteMeta(absl::string_view unquoted);
```

**Example:**
```cpp
RE2::QuoteMeta("1.5-2.0?")  // Returns: "1\.5\-2\.0\?"
```

---

### 10. Generic Matching Interface (1 method)

Low-level matching with fine-grained control:

```cpp
bool Match(absl::string_view text,
          size_t startpos,
          size_t endpos,
          Anchor re_anchor,
          absl::string_view* submatch,
          int nsubmatch) const;
```

**Anchor Options:**
- `UNANCHORED` - pattern can match anywhere
- `ANCHOR_START` - must match at startpos
- `ANCHOR_BOTH` - must match entire [startpos, endpos) range

**Returns:** submatch[0] = full match, submatch[1..n] = groups

---

### 11. Rewrite Validation Methods (3 methods)

Validate rewrite strings before use:

```cpp
// Check rewrite string for validity
bool CheckRewriteString(absl::string_view rewrite, std::string* error) const;

// Get max group number needed for rewrite
static int MaxSubmatch(absl::string_view rewrite);

// Perform substitution into output string
bool Rewrite(std::string* out,
            absl::string_view rewrite,
            const absl::string_view* vec,
            int veclen) const;
```

---

### 12. Options-Related Methods (1 method)

Retrieve construction options:

```cpp
const Options& options() const;
```

---

### 13. Argument Conversion Methods (3 methods)

Parse numbers in different bases:

```cpp
// C-style radix (0x prefix = hex, 0 prefix = octal, else decimal)
template <typename T>
static Arg CRadix(T* ptr);

// Hexadecimal
template <typename T>
static Arg Hex(T* ptr);

// Octal
template <typename T>
static Arg Octal(T* ptr);
```

**Example:**
```cpp
int a, b, c, d;
RE2::FullMatch("100 40 0100 0x40", "(.*) (.*) (.*) (.*)",
               RE2::Octal(&a), RE2::Hex(&b), 
               RE2::CRadix(&c), RE2::CRadix(&d));
// a=64, b=64, c=64, d=64
```

---

### 14. Fuzzing/Testing Methods (1 method)

Limit global replacements (security):

```cpp
static void FUZZING_ONLY_set_maximum_global_replace_count(int i);
```

**Note:** FOR FUZZING ONLY - use -1 for unlimited (default)

---

### 15. Internal Accessor Methods (1 method)

Access internal Regexp (rarely used):

```cpp
re2::Regexp* Regexp() const;
```

**WARNING:** For debugging only. Subject to change. Do not use in production.

---

## RE2::Options Class (30 methods)

Configuration for pattern compilation and matching:

### Constructor & Copy (2 methods)

```cpp
Options();                          // Default constructor
Options(CannedOptions);             // From predefined options

// Predefined options:
// - DefaultOptions
// - Latin1 (treat input as Latin-1 instead of UTF-8)
// - POSIX (POSIX syntax, leftmost-longest match)
// - Quiet (suppress error logging)
```

### Configuration Methods (28 methods)

Getter/setter pairs for all options:

```cpp
// Memory budget (default: 8MB)
int64_t max_mem() const;
void set_max_mem(int64_t m);

// Character encoding
Encoding encoding() const;  // EncodingUTF8 or EncodingLatin1
void set_encoding(Encoding encoding);

// POSIX mode (stricter syntax)
bool posix_syntax() const;
void set_posix_syntax(bool b);

// Longest match vs first match
bool longest_match() const;
void set_longest_match(bool b);

// Error logging
bool log_errors() const;
void set_log_errors(bool b);

// Literal string (no special regex chars)
bool literal() const;
void set_literal(bool b);

// Never match newline
bool never_nl() const;
void set_never_nl(bool b);

// Dot matches newline
bool dot_nl() const;
void set_dot_nl(bool b);

// All parens are non-capturing
bool never_capture() const;
void set_never_capture(bool b);

// Case sensitivity
bool case_sensitive() const;
void set_case_sensitive(bool b);

// Perl character classes (\d, \s, \w)
bool perl_classes() const;
void set_perl_classes(bool b);

// Word boundary (\b, \B)
bool word_boundary() const;
void set_word_boundary(bool b);

// ^ and $ only at line boundaries
bool one_line() const;
void set_one_line(bool b);

// Copy all settings
void Copy(const Options& src);

// Convert to Regexp::ParseFlags (internal use)
int ParseFlags() const;
```

---

## RE2::Arg Class (5 methods)

Type-safe argument holder for submatch extraction:

### Constructor Variants (4 constructors)

```cpp
Arg();                                  // Empty/null arg
Arg(std::nullptr_t ptr);                // Explicit NULL

// Pointer to type T (auto-selects parser)
template <typename T>
Arg(T* ptr);

// Custom parser function
template <typename T>
Arg(T* ptr, Parser parser);
```

**Supported Types:**
- `std::string` - captured substring
- `absl::string_view` - non-owning string reference
- `std::optional<T>` - optional values
- Numeric: `int`, `long`, `long long`, `float`, `double`, `short`, etc.
- `char` - single character
- Custom types with `bool ParseFrom(const char*, size_t)` method
- `(void*)NULL` - ignore this capture group

### Parsing Method (1 method)

```cpp
bool Parse(const char* str, size_t n) const;
```

---

## LazyRE2 Class (3 methods)

Thread-safe lazy initialization wrapper:

```cpp
RE2& operator*() const;   // Dereference (get RE2 reference)
RE2* operator->() const;  // Pointer access

RE2* get() const;         // Explicit getter
```

**Usage:**
```cpp
static LazyRE2 re = {"pattern"};
if (RE2::FullMatch(text, *re)) { ... }
```

---

## RE2 Enums

### ErrorCode (15 values)

```cpp
NoError = 0,
ErrorInternal,
ErrorBadEscape,
ErrorBadCharClass,
ErrorBadCharRange,
ErrorMissingBracket,
ErrorMissingParen,
ErrorUnexpectedParen,
ErrorTrailingBackslash,
ErrorRepeatArgument,
ErrorRepeatSize,
ErrorRepeatOp,
ErrorBadPerlOp,
ErrorBadUTF8,
ErrorBadNamedCapture,
ErrorPatternTooLarge
```

### CannedOptions (4 values)

```cpp
DefaultOptions = 0
Latin1
POSIX
Quiet
```

### Anchor (3 values)

```cpp
UNANCHORED      // No anchoring
ANCHOR_START    // Anchor at start only
ANCHOR_BOTH     // Anchor at start and end
```

### Encoding (2 values)

```cpp
EncodingUTF8 = 1
EncodingLatin1
```

---

## Key Implementation Patterns for JNI Wrapper

### Pattern 1: Intercepting Static Matching

```cpp
// RE2 static method
static bool RE2::FullMatch(absl::string_view text, const RE2& re, ...)

// Wrapper intercept
static bool Wrapper::FullMatch(absl::string_view text, const RE2& re, ...) {
    // Pre-processing
    // Delegate to RE2::FullMatch(text, re, ...)
    // Post-processing
    return result;
}
```

### Pattern 2: Caching Patterns

```cpp
// Store RE2 objects (they're expensive to compile)
std::unordered_map<std::string, std::shared_ptr<RE2>> pattern_cache;

// Reuse across calls
auto re = pattern_cache.find(pattern_string);
if (re == pattern_cache.end()) {
    re = pattern_cache[pattern_string] = 
        std::make_shared<RE2>(pattern_string, options);
}
```

### Pattern 3: Options Propagation

```cpp
RE2::Options opts;
opts.set_max_mem(my_max_mem);
opts.set_case_sensitive(my_case_sensitive);
opts.set_log_errors(false);  // Suppress RE2's error logging

RE2 re(pattern, opts);
```

### Pattern 4: N-method wrapping (for dynamic arg count)

```cpp
const RE2::Arg* args[16];  // Max 16 args
for (int i = 0; i < n; i++) {
    args[i] = &my_args[i];
}
return RE2::FullMatchN(text, re, args, n);
```

---

## Methods NOT to Expose in Wrapper

- **Copy/Move ops** - deleted, should stay deleted
- **Re2::Regexp()** - internal implementation detail
- **FUZZING_ONLY_* methods** - testing only
- **Internal parse/rewrite internals** - use high-level APIs

---

## Critical Invariants for Wrapper Implementation

1. **RE2 Thread Safety:** All public methods are thread-safe. No mutex needed in wrapper.

2. **String Lifetime:** `absl::string_view` is non-owning. Input strings must outlive the call.

3. **Memory Ownership:**
   - Returned `const std::string&` valid only while RE2 object exists
   - Named group maps valid only while RE2 object exists

4. **Match Semantics:**
   - FullMatch: Entire text must match
   - PartialMatch: Any substring can match
   - Consume: Must match at beginning, advances pointer
   - FindAndConsume: Can match anywhere, advances pointer

5. **Capturing Groups:**
   - Group 0 = entire match
   - Group 1..N = parenthesized subexpressions
   - Optional groups return empty string_view (not error)

6. **Replacement String Format:**
   - `\0` through `\9` for substitution
   - `\\` for literal backslash
   - Other `\X` sequences may be errors (depends on validation)

---

## Quick Reference: Common Wrapping Tasks

| Task | Methods to Wrap | Notes |
|------|-----------------|-------|
| Compile & store pattern | `RE2()` constructors | Use shared_ptr for reuse |
| Check compilation | `ok()`, `error()` | Always check after construction |
| Full string match | `FullMatch()`, `FullMatchN()` | Variadic or array variant |
| Search substring | `PartialMatch()`, `PartialMatchN()` | Pattern can be anywhere |
| Scan incrementally | `Consume()`, `FindAndConsume()` | Modifies input pointer |
| Replace text | `Replace()`, `GlobalReplace()` | Returns bool or count |
| Analyze pattern | `NumberOfCapturingGroups()` | Plan memory pre-allocation |
| Configure matching | `Options` class | Set before construction |
| Parse arguments | `Arg` class | Automatic type handling |

---

## Summary Statistics

| Category | Count | Status |
|----------|-------|--------|
| Total Public Methods | 82 | EXPOSE |
| Deleted Methods | 4 | DO NOT EXPOSE |
| Static Methods | ~30 | EXPOSE |
| Instance Methods | ~50 | EXPOSE |
| Templates | ~10 | EXPOSE |
| Enums | 4 | EXPOSE |
| Nested Classes | 4 | EXPOSE |
| Supported Arg Types | 12+ | EXPOSE |
| Error Codes | 15 | EXPOSE |

---

**This document is the definitive API reference for libre2-java wrapper implementation.**
