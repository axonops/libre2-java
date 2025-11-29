# RE2 API Complete Requirements - ALL Must Be Implemented

**Goal:** 100% like-for-like RE2 wrapper
**No exceptions. No priorities. EVERYTHING.**

---

## WHAT WE'RE MISSING - ALL MUST BE IMPLEMENTED

### Phase 1.2.5h - RE2::Arg Support
**Status:** ❌ NOT STARTED
**Functions:**
1. Re-export RE2::Arg class (typedef)
2. Fix fullMatchN() signature: string*[] → const Arg* const[]
3. Fix partialMatchN() signature: string*[] → const Arg* const[]
4. Fix consumeN() signature: string*[] → const Arg* const[]
5. Fix findAndConsumeN() signature: string*[] → const Arg* const[]
6. Add Hex<T>(T* ptr)
7. Add Octal<T>(T* ptr)
8. Add CRadix<T>(T* ptr)
9. Add options() const → returns const PatternOptions&
**Tests:** Port ALL re2_arg_test.cc (~50+ tests)
**Effort:** 6-8 hours

### Phase 1.2.5i - ProgramFanout & Enums
**Status:** ❌ NOT STARTED
**Functions:**
1. programFanout(vector<int>* histogram)
2. reverseProgramFanout(vector<int>* histogram)
3. Expose ErrorCode enum (15 values)
4. Expose CannedOptions enum (4 values)
5. Expose Encoding enum (2 values)
**Tests:** 15+
**Effort:** 3-4 hours

### Phase 1.2.5j - RE2::Options Getters/Setters
**Status:** ❌ NOT STARTED  
**Functions:** ALL 28 methods
1. Options() constructor - expose
2. Options(CannedOptions) constructor
3-15. Getters: max_mem(), encoding(), posix_syntax(), longest_match(), log_errors(), literal(), never_nl(), dot_nl(), never_capture(), case_sensitive(), perl_classes(), word_boundary(), one_line()
16-28. Setters: set_max_mem(), set_encoding(), set_posix_syntax(), set_longest_match(), set_log_errors(), set_literal(), set_never_nl(), set_dot_nl(), set_never_capture(), set_case_sensitive(), set_perl_classes(), set_word_boundary(), set_one_line()
29. Copy(const Options&)
30. ParseFlags()
**Tests:** 30+
**Effort:** 6-8 hours

### Phase 1.2.6 - RE2::Set Class
**Status:** ❌ NOT STARTED
**Functions:** ALL 9 methods
1. Set(const Options&, Anchor) constructor
2. ~Set() destructor
3. Set(Set&&) move constructor
4. Set& operator=(Set&&) move assignment
5. int Add(string_view, string*)
6. int Size() const
7. bool Compile()
8. bool Match(string_view, vector<int>*) const
9. bool Match(string_view, vector<int>*, ErrorInfo*) const
**Tests:** 40+
**Effort:** 12-16 hours

---

## TOTAL REQUIREMENTS

**Functions to add:** 49
**Methods to fix:** 6 (wrong signatures)
**Tests to add:** 135+
**Total effort:** 27-36 hours

**ALL of this is REQUIRED. No priorities. No "nice to have". EVERYTHING.**

---

**Next:** Start with Phase 1.2.5h (RE2::Arg) NOW.
