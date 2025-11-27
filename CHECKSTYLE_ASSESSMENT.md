# Checkstyle Violation Assessment

**Date:** 2025-11-27
**Branch:** feature/checkstyle
**Configuration:** Google Java Style (google_checks.xml)

---

## Summary

**Total Violations:** 3,196
**Files Affected:** 28 (24 production + 4 test)
**Build Impact:** Fails with "You have 3196 Checkstyle violations"

---

## Violation Breakdown by Type

| Violation Type | Count | % of Total | Description |
|----------------|-------|------------|-------------|
| **IndentationCheck** | 2,666 | 83% | 4-space indent vs Google's 2-space |
| **JavadocParagraphCheck** | 240 | 8% | Javadoc formatting issues |
| **LineLengthCheck** | 141 | 4% | Lines exceed 100 chars |
| **CustomImportOrderCheck** | 44 | 1% | Import order incorrect |
| **EmptyLineSeparatorCheck** | 26 | 1% | Missing blank lines |
| **AbbreviationAsWordInNameCheck** | 17 | <1% | "RE2" has 2 consecutive capitals |
| **VariableDeclarationUsageDistanceCheck** | 19 | <1% | Variable declared too far from use |
| **OverloadMethodsDeclarationOrderCheck** | 15 | <1% | Overloaded methods not grouped |
| **OperatorWrapCheck** | 13 | <1% | Operator wrapping style |
| **Other** | 15 | <1% | Misc violations |

---

## The Core Problem: Indentation (83% of violations)

### Current Code Style:
```java
public class Pattern {
    private final String pattern;  // ← 4-space indent

    public void method() {         // ← 4-space indent
        doSomething();             // ← 8-space indent
    }
}
```

### Google Java Style Requires:
```java
public class Pattern {
  private final String pattern;  // ← 2-space indent

  public void method() {         // ← 2-space indent
    doSomething();               // ← 4-space indent
  }
}
```

**Impact of Fixing:**
- Reformat ALL 27 production classes
- Reformat ALL test classes
- ~5,000-6,000 lines changed (indentation-only)
- Massive diff, but purely cosmetic

---

## Files with Most Violations

**Top 10:**
1. Pattern.java - ~800 violations
2. PatternCache.java - ~300 violations
3. RE2.java - ~200 violations
4. Matcher.java - ~150 violations
5. IdleEvictionTask.java - ~100 violations
6. RE2Config.java - ~80 violations
7. (Other files) - ~50-100 each

---

## Options for Resolving

### Option 1: Modify Google Style to Allow 4-Space Indentation ✅ RECOMMENDED

**Pros:**
- Fixes 2,666 violations (83%) immediately
- No code changes needed
- Keep other Google Style rules
- Only ~530 violations remain (manageable)

**Cons:**
- Not "pure" Google Style
- Custom configuration to maintain

**Implementation:**
```xml
<!-- config/checkstyle/google_checks.xml -->
<module name="Indentation">
  <property name="basicOffset" value="4"/>  <!-- Change from 2 to 4 -->
  <property name="caseIndent" value="4"/>   <!-- Change from 2 to 4 -->
</module>
```

---

### Option 2: Reformat Everything to Google Style

**Pros:**
- Strict compliance with Google Style
- Standard configuration (no customization)
- Clean, consistent 2-space indent

**Cons:**
- Massive diff (~5,000-6,000 lines changed)
- All indentation changes in git history
- Requires IDE reconfiguration for developers

**Implementation:**
- Use IntelliJ/Eclipse auto-formatter with Google Style
- Run on all files
- Commit reformatted code

---

### Option 3: Suppress Indentation, Fix Other Violations

**Pros:**
- Focus on real issues (Javadoc, line length, etc.)
- No massive reformatting
- Incremental improvement

**Cons:**
- Don't enforce indentation consistency
- Missing 83% of style checking value

**Implementation:**
```xml
<!-- Disable indentation check in google_checks.xml -->
<module name="Indentation">
  <property name="severity" value="ignore"/>
</module>
```

---

## Remaining Violations (if we fix indentation)

**After fixing indentation, ~530 violations remain:**

1. **JavadocParagraphCheck:** 240 violations
   - Add `<p>` tags in Javadoc
   - Easy to fix with regex

2. **LineLengthCheck:** 141 violations
   - Break long lines
   - Some might be unavoidable (long method signatures, URLs)

3. **CustomImportOrderCheck:** 44 violations
   - Reorder imports (IDE can fix automatically)

4. **AbbreviationAsWordInNameCheck:** 17 violations
   - "RE2" violates rule (max 1 consecutive capital)
   - Would need to rename classes (not recommended)
   - Suggest: Suppress this check for "RE2*" pattern

5. **Other:** ~90 violations
   - Various small issues
   - Can be fixed incrementally

---

## Recommendations

### Phase 1: Configure for 4-Space Indentation (Now)
1. Modify `config/checkstyle/google_checks.xml`
2. Change `basicOffset` from 2 to 4
3. Re-run Checkstyle
4. Violations drop: 3,196 → ~530

### Phase 2: Suppress RE2 Abbreviation Rule (Now)
1. Add suppression for `RE2*` class names
2. Violations drop: ~530 → ~513

### Phase 3: Fix Remaining Violations (Later/Incrementally)
1. Fix Javadoc paragraphs (~240 violations)
2. Fix import order (~44 violations)
3. Fix line length where reasonable (~141 violations)
4. Fix misc violations (~88 violations)

---

## Decision Needed

**What approach do you prefer?**

**A) Modified Google Style (4-space indent)** ← Recommended
- Quick win, fixes 83% immediately
- ~530 violations to fix incrementally

**B) Pure Google Style (2-space indent)**
- Reformat everything
- Large one-time diff

**C) Suppress indentation entirely**
- No indentation checking
- Focus on other rules

**Token Usage:** ~403,000 / 1,000,000 (40.3% used)
