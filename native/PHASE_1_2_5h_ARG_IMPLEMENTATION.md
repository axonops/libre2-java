# Phase 1.2.5h - RE2::Arg Implementation Plan

**Priority:** 🔴 CRITICAL
**Status:** Planning
**Effort:** 6-8 hours (simplified - just re-export RE2::Arg)

---

## GOAL

Add RE2::Arg support for typed captures (int, float, double, optional, etc).

**This is REQUIRED for like-for-like RE2 compatibility.**

---

## IMPLEMENTATION STRATEGY

### Approach: Re-export RE2::Arg (Simplest)

Just expose RE2::Arg directly in our wrapper API.

```cpp
// In libre2_api.h:
namespace libre2 {
namespace api {

// Re-export RE2::Arg for typed captures
using Arg = RE2::Arg;

// Re-export helper functions
template <typename T>
inline Arg Hex(T* ptr) { return RE2::Hex(ptr); }

template <typename T>
inline Arg Octal(T* ptr) { return RE2::Octal(ptr); }

template <typename T>
inline Arg CRadix(T* ptr) { return RE2::CRadix(ptr); }

} // namespace api
} // namespace libre2
```

**Benefits:**
- Simple (just typedef + helpers)
- Perfect RE2 compatibility
- Zero maintenance (RE2 handles all type parsing)
- No backward compatibility needed (not released yet)

---

## FUNCTIONS TO UPDATE

### Replace String-Only N-Variants with Arg-Based:

**OLD (string-only - REMOVE):**
```cpp
bool fullMatchN(RE2Pattern* pattern, string_view text,
                string* captures[], int n_captures);
```

**NEW (Arg-based - CORRECT):**
```cpp
bool fullMatchN(RE2Pattern* pattern, string_view text,
                const Arg* const args[], int n_args);
```

**Functions to update (4):**
1. fullMatchN → accept Arg*[]
2. partialMatchN → accept Arg*[]  
3. consumeN → accept Arg*[]
4. findAndConsumeN → accept Arg*[]

### Simplify Implementation:

**OLD (complex - with string* conversion):**
```cpp
bool fullMatchN(..., string* captures[], int n) {
    // Build RE2::Arg vector
    vector<RE2::Arg> args_vec;
    for (int i = 0; i < n; i++) {
        args_vec.emplace_back(captures[i]);
    }
    // Build pointer array
    vector<const RE2::Arg*> args_ptrs(n);
    for (int i = 0; i < n; i++) {
        args_ptrs[i] = &args_vec[i];
    }
    return RE2::FullMatchN(text, *pattern->compiled_regex, args_ptrs.data(), n);
}
```

**NEW (simple - direct pass-through):**
```cpp
bool fullMatchN(..., const Arg* const args[], int n) {
    if (!pattern || !pattern->isValid()) return false;
    // Direct delegation - no conversion!
    return RE2::FullMatchN(text, *pattern->compiled_regex, args, n);
}
```

**Savings:** ~20 lines per function, simpler, faster

---

## DIRECT/BULK VARIANTS

Direct and Bulk variants stay the same - they call the Arg-based standard variant.

**No changes needed** - already calling fullMatchN internally.

---

## TESTING PLAN

### Port ALL Tests from re2_arg_test.cc:

**File:** reference-repos/re2/re2/testing/re2_arg_test.cc (183 lines)

**Tests to port:**
1. ✅ Int16Test - Parse to int16_t
2. ✅ Uint16Test - Parse to uint16_t
3. ✅ Int32Test - Parse to int32_t
4. ✅ Uint32Test - Parse to uint32_t
5. ✅ Int64Test - Parse to int64_t
6. ✅ Uint64Test - Parse to uint64_t
7. ✅ ParseFromTest - Custom type parsing
8. ✅ OptionalDoubleTest - std::optional<double>
9. ✅ OptionalIntWithCRadixTest - std::optional with hex

**Additional tests:**
10. ✅ Mixed types (string + int + float in one match)
11. ✅ Hex() helper validation
12. ✅ Octal() helper validation
13. ✅ CRadix() helper validation

**Total: 50+ tests**

---

## IMPLEMENTATION STEPS

### Step 1: Re-export RE2::Arg (header only)
- Add `using Arg = RE2::Arg;`
- Add Hex(), Octal(), CRadix() inline helpers
- **Effort:** 30 minutes

### Step 2: Update N-Variant Signatures
- Change `string* captures[], int n_captures` → `const Arg* const args[], int n_args`
- Simplify implementation (remove Arg conversion code)
- Update all 4 functions: fullMatchN, partialMatchN, consumeN, findAndConsumeN
- **Effort:** 1-2 hours

### Step 3: Update Tests
- Update existing N-variant tests to use Arg
- **Effort:** 1 hour

### Step 4: Port re2_arg_test.cc
- Port all 9 test cases
- Add 50+ typed capture tests
- All with RE2 comparison pattern
- **Effort:** 3-4 hours

### Step 5: Build and Verify
- All tests pass
- Verify typed captures work (int, float, optional, etc)
- **Effort:** 30 minutes

---

## SUCCESS CRITERIA

- ✅ RE2::Arg re-exported in libre2::api
- ✅ Hex(), Octal(), CRadix() available
- ✅ N-variants accept Arg*[] (not string*[])
- ✅ All 50+ tests from re2_arg_test.cc ported
- ✅ All tests passing
- ✅ Typed captures work (int, float, double, optional)
- ✅ Custom type parsing works
- ✅ Implementation simpler (direct pass-through, no conversion)

---

**Total Effort:** 6-8 hours
**API Completeness After:** ~75%
**CRITICAL:** This is non-negotiable for RE2 compatibility
