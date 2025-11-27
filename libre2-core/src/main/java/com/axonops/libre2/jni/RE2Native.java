/*
 * Copyright 2025 AxonOps
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.axonops.libre2.jni;

/**
 * Production JNI adapter - delegates to package-private RE2NativeJNI.
 *
 * <p>Singleton instance used by all Pattern/Matcher/RE2 instances in production. Tests can inject
 * mock JniAdapter instead.
 *
 * <p><b>Internal API:</b> Not part of public API contract. Accessed via Pattern injection. Public
 * visibility required for cross-package access from api package.
 */
public final class RE2Native implements IRE2Native {

  /** Singleton instance - used in production. Public so Pattern can access it from api package. */
  public static final RE2Native INSTANCE = new RE2Native();

  private RE2Native() {}

  @Override
  public long compile(String pattern, boolean caseSensitive) {
    return RE2NativeJNI.compile(pattern, caseSensitive);
  }

  @Override
  public void freePattern(long handle) {
    RE2NativeJNI.freePattern(handle);
  }

  @Override
  public boolean patternOk(long handle) {
    return RE2NativeJNI.patternOk(handle);
  }

  @Override
  public String getError() {
    return RE2NativeJNI.getError();
  }

  @Override
  public String getPattern(long handle) {
    return RE2NativeJNI.getPattern(handle);
  }

  @Override
  public int numCapturingGroups(long handle) {
    return RE2NativeJNI.numCapturingGroups(handle);
  }

  @Override
  public long patternMemory(long handle) {
    return RE2NativeJNI.patternMemory(handle);
  }

  @Override
  public boolean fullMatch(long handle, String text) {
    return RE2NativeJNI.fullMatch(handle, text);
  }

  @Override
  public boolean partialMatch(long handle, String text) {
    return RE2NativeJNI.partialMatch(handle, text);
  }

  @Override
  public boolean fullMatchDirect(long handle, long address, int length) {
    return RE2NativeJNI.fullMatchDirect(handle, address, length);
  }

  @Override
  public boolean partialMatchDirect(long handle, long address, int length) {
    return RE2NativeJNI.partialMatchDirect(handle, address, length);
  }

  @Override
  public boolean[] fullMatchBulk(long handle, String[] texts) {
    return RE2NativeJNI.fullMatchBulk(handle, texts);
  }

  @Override
  public boolean[] partialMatchBulk(long handle, String[] texts) {
    return RE2NativeJNI.partialMatchBulk(handle, texts);
  }

  @Override
  public boolean[] fullMatchDirectBulk(long handle, long[] addresses, int[] lengths) {
    return RE2NativeJNI.fullMatchDirectBulk(handle, addresses, lengths);
  }

  @Override
  public boolean[] partialMatchDirectBulk(long handle, long[] addresses, int[] lengths) {
    return RE2NativeJNI.partialMatchDirectBulk(handle, addresses, lengths);
  }

  @Override
  public String[] extractGroups(long handle, String text) {
    return RE2NativeJNI.extractGroups(handle, text);
  }

  @Override
  public String[][] extractGroupsBulk(long handle, String[] texts) {
    return RE2NativeJNI.extractGroupsBulk(handle, texts);
  }

  @Override
  public String[] extractGroupsDirect(long handle, long address, int length) {
    return RE2NativeJNI.extractGroupsDirect(handle, address, length);
  }

  @Override
  public String[][] findAllMatches(long handle, String text) {
    return RE2NativeJNI.findAllMatches(handle, text);
  }

  @Override
  public String[][] findAllMatchesDirect(long handle, long address, int length) {
    return RE2NativeJNI.findAllMatchesDirect(handle, address, length);
  }

  @Override
  public String[] getNamedGroups(long handle) {
    return RE2NativeJNI.getNamedGroups(handle);
  }

  @Override
  public String replaceFirst(long handle, String text, String replacement) {
    return RE2NativeJNI.replaceFirst(handle, text, replacement);
  }

  @Override
  public String replaceAll(long handle, String text, String replacement) {
    return RE2NativeJNI.replaceAll(handle, text, replacement);
  }

  @Override
  public String[] replaceAllBulk(long handle, String[] texts, String replacement) {
    return RE2NativeJNI.replaceAllBulk(handle, texts, replacement);
  }

  @Override
  public String replaceFirstDirect(long handle, long address, int length, String replacement) {
    return RE2NativeJNI.replaceFirstDirect(handle, address, length, replacement);
  }

  @Override
  public String replaceAllDirect(long handle, long address, int length, String replacement) {
    return RE2NativeJNI.replaceAllDirect(handle, address, length, replacement);
  }

  @Override
  public String[] replaceAllDirectBulk(
      long handle, long[] addresses, int[] lengths, String replacement) {
    return RE2NativeJNI.replaceAllDirectBulk(handle, addresses, lengths, replacement);
  }

  @Override
  public String quoteMeta(String text) {
    return RE2NativeJNI.quoteMeta(text);
  }

  @Override
  public int[] programFanout(long handle) {
    return RE2NativeJNI.programFanout(handle);
  }
}
