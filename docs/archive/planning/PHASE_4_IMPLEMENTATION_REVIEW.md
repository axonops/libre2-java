# Phase 4 Implementation Review

**Date:** 2025-11-20
**Purpose:** Comprehensive review before starting multi-module refactor
**Branch Strategy:** Feature branch recommended

---

## Critical Findings

### ❌ Issue 1: JNI vs JNA Confusion

**CLAUDE.md says:**
> "Native bindings to RE2 C++ library via JNA"

**Actual implementation:**
- Using **JNI (Java Native Interface)**, not JNA
- Files: `RE2NativeJNI.java` with `native` methods
- No JNA dependency in pom.xml (correctly)

**Impact:** Documentation inconsistency only - code is correct

**Fix Required:**
- Update CLAUDE.md references from "JNA" to "JNI"
- Update any architecture docs mentioning JNA

---

### ✅ Verified: Native Library Structure

**Location:** `src/main/resources/native/`
```
native/
├── darwin-aarch64/libre2.dylib
├── darwin-x86_64/libre2.dylib
├── linux-aarch64/libre2.so
└── linux-x86_64/libre2.so
```

**During refactor:** These MUST stay in `libre2-core/src/main/resources/native/`

---

## Multi-Module Refactor Risks & Gaps

### Risk 1: Native Library Packaging

**Problem:** Native libraries must be packaged in `libre2-core.jar`

**Current:**
```
libre2-java.jar
└── native/
    ├── darwin-aarch64/libre2.dylib
    └── ...
```

**After refactor:**
```
libre2-core.jar  ← Must contain these!
└── native/
    ├── darwin-aarch64/libre2.dylib
    └── ...
```

**Verification needed:**
- Run `jar tf target/libre2-core-1.0.0-SNAPSHOT.jar | grep native`
- Ensure native libraries are in the JAR

---

### Risk 2: Build Scripts Location

**Current structure:**
```
libre2-java/
├── native/
│   ├── scripts/build.sh
│   ├── wrapper/re2_jni.cpp
│   └── Dockerfile
└── .github/workflows/
    ├── build-native.yml
    └── test.yml
```

**Questions:**
1. Where does `native/` directory go? Root or libre2-core/?
2. Do CI workflows need updating?
3. Does build-native.yml work with multi-module?

**Recommendation:**
- Keep `native/` at **root level** (builds before module structure)
- Update workflows to build native libraries first, then modules

---

### Risk 3: Git History Preservation

**Problem:** Moving files can lose git history

**Solution:** Use `git mv` instead of regular `mv`
```bash
# Good - preserves history
git mv src/ libre2-core/src/

# Bad - loses history
mv src/ libre2-core/src/
git add libre2-core/src/
```

---

### Risk 4: Test Resources

**Files that need to move:**
```
src/test/resources/
└── logback-test.xml  ← I just created this!
```

**Target:**
```
libre2-core/src/test/resources/
└── logback-test.xml
```

**Verification:** Tests still find logback-test.xml after move

---

### Risk 5: CI/CD Workflow Updates

**Current workflows:**
1. `.github/workflows/build-native.yml` - Builds native libraries
2. `.github/workflows/test.yml` - Runs tests

**Changes needed:**
1. **build-native.yml:** Should work unchanged (builds to `src/main/resources/native/`)
2. **test.yml:** Update to build multi-module project
   ```yaml
   # Before:
   - run: mvn clean test

   # After:
   - run: mvn clean test -pl libre2-core,libre2-cassandra
   ```

---

### Risk 6: Documentation Files Location

**Root-level docs:**
```
README.md
CLAUDE.md
LICENSE
DEVELOPMENT_STATUS.md
PHASE_*_COMPLETE.md
DECISION_LOG.md
ARCHITECTURE_DECISIONS.md
LOGGING_GUIDE.md
```

**Question:** Do these stay at root or move to libre2-core/?

**Recommendation:** Keep at root (apply to entire project)

---

### Risk 7: Version Management

**Current:** Single version `1.0.0-SNAPSHOT`

**After multi-module:**
```xml
<!-- Parent POM -->
<version>1.0.0-SNAPSHOT</version>

<!-- libre2-core -->
<version>${project.parent.version}</version>

<!-- libre2-cassandra -->
<version>${project.parent.version}</version>
```

**Important:** All modules share same version (simpler)

---

### Risk 8: Maven Central Publishing

**Current:** Publish single JAR

**After multi-module:** Publish 2 JARs
- `libre2-core-1.0.0.jar`
- `libre2-cassandra-1.0.0.jar`

**Impact:** Need to update release process (future concern)

---

## Feature Branch Strategy

### ✅ Strongly Recommended

**Current:** `development` branch (204 tests passing, stable)

**Proposed:** Create feature branch for this refactor

### Option 1: Single Feature Branch (Recommended)
```bash
git checkout development
git checkout -b feature/phase4-multimodule-metrics
# Do all Phase 4A + 4B work here
# When complete, PR back to development
```

**Pros:**
- All Phase 4 work in one branch
- Single PR to review
- Clear scope

**Cons:**
- Longer-lived branch
- More merge conflicts if development moves

### Option 2: Two Feature Branches
```bash
# First: Refactor only
git checkout -b feature/phase4a-multimodule-refactor
# Complete Phase 4A, verify tests pass
# PR #1: Merge to development

# Then: Metrics implementation
git checkout development
git checkout -b feature/phase4b-metrics-logging
# Complete Phase 4B
# PR #2: Merge to development
```

**Pros:**
- Smaller PRs, easier to review
- Can merge refactor quickly if it works
- Reduces risk

**Cons:**
- Two branches to manage
- Second branch depends on first merging

### Recommendation: Option 2 (Two Branches)

**Rationale:**
- Phase 4A (refactor) is risky - good to isolate and verify
- Phase 4B (metrics) builds on stable multi-module structure
- Easier to rollback if Phase 4A goes wrong

**Branch names:**
1. `feature/phase4a-multimodule-refactor`
2. `feature/phase4b-metrics-logging`

---

## Detailed Phase 4A Plan (Revised)

### Step 1: Create Feature Branch
```bash
git checkout development
git pull origin development
git checkout -b feature/phase4a-multimodule-refactor
```

### Step 2: Create Parent POM Structure
```bash
# Current root pom.xml becomes parent
# Rename to pom.xml (keep as parent)
# Change packaging to pom
# Add <modules> section
```

### Step 3: Move Code to libre2-core
```bash
# Use git mv to preserve history
mkdir -p libre2-core
git mv src libre2-core/
git mv pom.xml libre2-core/pom.xml.bak

# Create new parent pom.xml at root
# Create libre2-core/pom.xml (child POM)
```

### Step 4: Create libre2-cassandra Skeleton
```bash
mkdir -p libre2-cassandra/src/main/java/com/axonops/libre2/cassandra
mkdir -p libre2-cassandra/src/test/java/com/axonops/libre2/cassandra

# Create libre2-cassandra/pom.xml
```

### Step 5: Verify Build
```bash
mvn clean install
# Should build both modules
# Should run all 204 tests
```

### Step 6: Verify Native Libraries in JAR
```bash
jar tf libre2-core/target/libre2-core-1.0.0-SNAPSHOT.jar | grep native
# Should see:
# native/darwin-aarch64/libre2.dylib
# native/darwin-x86_64/libre2.dylib
# native/linux-aarch64/libre2.so
# native/linux-x86_64/libre2.so
```

### Step 7: Update CI Workflows (if needed)
```yaml
# .github/workflows/test.yml
- name: Build and test
  run: mvn clean test
  # Should work with multi-module automatically
```

### Step 8: Commit and Push
```bash
git add -A
git commit -m "Phase 4A: Refactor to multi-module project (core + cassandra)"
git push origin feature/phase4a-multimodule-refactor
```

### Step 9: Create PR
- PR to `development` branch
- Run CI to verify all platforms pass
- Review changes
- Merge when verified

---

## Gaps to Address Before Starting

### Gap 1: JNI vs JNA Documentation
- [ ] Update CLAUDE.md (JNA → JNI)
- [ ] Update any architecture docs
- [ ] Update comments mentioning JNA

### Gap 2: Missing Verification Steps
- [ ] Add step to verify native libraries in JAR
- [ ] Add step to verify RE2LibraryLoader still works
- [ ] Add step to verify tests find resources

### Gap 3: Rollback Plan
- [ ] Document how to rollback if refactor fails
- [ ] Consider tagging current state before refactor

### Gap 4: Documentation Location Strategy
- [ ] Decide: root-level docs stay at root or move?
- [ ] Decision: Keep at root (apply to entire project)

---

## Pre-Flight Checklist

Before starting Phase 4A:

### Repository State
- [ ] On `development` branch
- [ ] All changes committed
- [ ] All tests passing (204/204)
- [ ] No uncommitted changes (`git status` clean)

### Branch Strategy Agreed
- [ ] Feature branch name decided: `feature/phase4a-multimodule-refactor`
- [ ] PR strategy agreed (merge back to development)
- [ ] Rollback plan understood

### Build Environment
- [ ] Maven 3.9.5+ installed
- [ ] Java 17+ installed
- [ ] Can run `mvn clean test` successfully
- [ ] Native libraries present in src/main/resources/native/

### Documentation Updates Ready
- [ ] JNI vs JNA corrections ready
- [ ] Multi-module architecture docs complete ✅
- [ ] Logging docs complete ✅

---

## Estimated Timeline (Revised)

### Phase 4A: Multi-Module Refactor
- **Create parent POM:** 10 minutes
- **Move code with git mv:** 10 minutes
- **Create libre2-cassandra skeleton:** 5 minutes
- **Verify build and tests:** 10 minutes
- **Update CI if needed:** 5 minutes
- **Fix any issues:** 15 minutes buffer
- **Total:** ~60 minutes (was 30, now more realistic)

### Phase 4B: Metrics Implementation
- **Create metrics infrastructure:** 1 hour
- **Enhance logging:** 1 hour
- **Integrate collection points:** 1.5 hours
- **Implement Cassandra module:** 1 hour
- **Write tests:** 1.5 hours
- **Fix issues and test:** 1 hour buffer
- **Total:** ~6 hours (was 4-6, upper bound realistic)

**Total Phase 4:** ~7 hours (more realistic than original 4.5-6.5)

---

## Questions for User

### 1. Feature Branch Strategy
**Which approach:**
- **Option A:** Single branch `feature/phase4-multimodule-metrics` (all work)
- **Option B:** Two branches (refactor first, then metrics) ← **Recommended**

### 2. Native Directory Location
**Where should native/ stay:**
- **Option A:** Root level (alongside parent POM) ← **Recommended**
- **Option B:** Move to libre2-core/native/

### 3. JNI Correction Priority
**When to fix JNI vs JNA docs:**
- **Option A:** Fix now before refactor
- **Option B:** Fix after refactor as separate PR ← **Recommended (less distraction)**

### 4. Risk Tolerance
**If Phase 4A refactor has issues:**
- **Option A:** Keep working until fixed
- **Option B:** Rollback and re-plan
- **Option C:** Ask for help from team

---

## Recommendation

### Start with Phase 4A on Feature Branch

**Steps:**
1. ✅ Create feature branch: `feature/phase4a-multimodule-refactor`
2. ✅ Execute Phase 4A (multi-module refactor)
3. ✅ Verify all 204 tests pass
4. ✅ Create PR to `development`
5. ✅ Merge when verified
6. ✅ Then start Phase 4B on new feature branch

**This approach:**
- Isolates refactor risk
- Allows incremental progress
- Makes rollback easier
- Keeps PRs reviewable

---

## Ready to Proceed?

**Waiting for approval on:**
1. Feature branch strategy (Option B: Two branches recommended)
2. Proceed with Phase 4A first
3. Any concerns or additional considerations

**Once approved, I'll:**
1. Create the feature branch
2. Execute Phase 4A refactor carefully
3. Verify tests pass
4. Report results before proceeding to Phase 4B
