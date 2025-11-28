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

#include "cache/cache_metrics.h"
#include <gtest/gtest.h>
#include <nlohmann/json.hpp>
#include <thread>

using namespace libre2::cache;
using json = nlohmann::json;

class CacheMetricsTest : public ::testing::Test {};

//============================================================================
// Pattern Result Cache Metrics Tests
//============================================================================

TEST_F(CacheMetricsTest, ResultMetrics_InitialState) {
    PatternResultCacheMetrics m;

    // All atomics should be zero
    EXPECT_EQ(m.hits.load(), 0u);
    EXPECT_EQ(m.misses.load(), 0u);
    EXPECT_EQ(m.get_errors.load(), 0u);
    EXPECT_EQ(m.put_errors.load(), 0u);
    EXPECT_EQ(m.ttl_evictions.load(), 0u);
    EXPECT_EQ(m.lru_evictions.load(), 0u);

    // Snapshots should be zero
    EXPECT_EQ(m.current_entry_count, 0u);
    EXPECT_EQ(m.target_capacity_bytes, 0u);
    EXPECT_EQ(m.actual_size_bytes, 0u);
    EXPECT_DOUBLE_EQ(m.utilization_ratio, 0.0);

    // Hit rate should be 0% (no requests)
    EXPECT_DOUBLE_EQ(m.hit_rate(), 0.0);
}

TEST_F(CacheMetricsTest, ResultMetrics_HitRate) {
    PatternResultCacheMetrics m;

    // No hits or misses → 0%
    EXPECT_DOUBLE_EQ(m.hit_rate(), 0.0);

    // 100 hits, 0 misses → 100%
    m.hits.store(100);
    m.misses.store(0);
    EXPECT_DOUBLE_EQ(m.hit_rate(), 100.0);

    // 75 hits, 25 misses → 75%
    m.hits.store(75);
    m.misses.store(25);
    EXPECT_DOUBLE_EQ(m.hit_rate(), 75.0);

    // 50 hits, 50 misses → 50%
    m.hits.store(50);
    m.misses.store(50);
    EXPECT_DOUBLE_EQ(m.hit_rate(), 50.0);

    // 1 hit, 99 misses → 1%
    m.hits.store(1);
    m.misses.store(99);
    EXPECT_DOUBLE_EQ(m.hit_rate(), 1.0);
}

TEST_F(CacheMetricsTest, ResultMetrics_JsonSerialization) {
    PatternResultCacheMetrics m;

    // Set some values
    m.hits.store(1000);
    m.misses.store(500);
    m.get_errors.store(5);
    m.put_errors.store(2);
    m.ttl_evictions.store(10);
    m.lru_evictions.store(20);
    m.lru_evictions_bytes_freed.store(5242880);
    m.total_evictions.store(30);
    m.total_bytes_freed.store(6291456);
    m.current_entry_count = 1200;
    m.target_capacity_bytes = 104857600;
    m.actual_size_bytes = 125829120;
    m.utilization_ratio = 1.2;

    std::string json_str = m.toJson();
    json j = json::parse(json_str);

    // Verify all fields present
    EXPECT_EQ(j["hits"], 1000);
    EXPECT_EQ(j["misses"], 500);
    EXPECT_DOUBLE_EQ(j["hit_rate"], 66.666666666666667);  // 1000/(1000+500) = 66.67%
    EXPECT_EQ(j["get_errors"], 5);
    EXPECT_EQ(j["put_errors"], 2);

    EXPECT_EQ(j["evictions"]["ttl"], 10);
    EXPECT_EQ(j["evictions"]["lru"], 20);
    EXPECT_EQ(j["evictions"]["lru_bytes_freed"], 5242880);
    EXPECT_EQ(j["evictions"]["total_evictions"], 30);
    EXPECT_EQ(j["evictions"]["total_bytes_freed"], 6291456);

    EXPECT_EQ(j["capacity"]["target_bytes"], 104857600);
    EXPECT_EQ(j["capacity"]["actual_bytes"], 125829120);
    EXPECT_EQ(j["capacity"]["entry_count"], 1200);
    EXPECT_DOUBLE_EQ(j["capacity"]["utilization_ratio"], 1.2);
}

//============================================================================
// Pattern Compilation Cache Metrics Tests
//============================================================================

TEST_F(CacheMetricsTest, PatternMetrics_InitialState) {
    PatternCacheMetrics m;

    EXPECT_EQ(m.hits.load(), 0u);
    EXPECT_EQ(m.misses.load(), 0u);
    EXPECT_EQ(m.compilation_errors.load(), 0u);
    EXPECT_EQ(m.ttl_evictions.load(), 0u);
    EXPECT_EQ(m.lru_evictions.load(), 0u);
    EXPECT_EQ(m.ttl_entries_moved_to_deferred.load(), 0u);
    EXPECT_EQ(m.lru_entries_moved_to_deferred.load(), 0u);

    EXPECT_DOUBLE_EQ(m.hit_rate(), 0.0);
}

TEST_F(CacheMetricsTest, PatternMetrics_HitRate) {
    PatternCacheMetrics m;

    m.hits.store(800);
    m.misses.store(200);
    EXPECT_DOUBLE_EQ(m.hit_rate(), 80.0);
}

TEST_F(CacheMetricsTest, PatternMetrics_JsonSerialization) {
    PatternCacheMetrics m;

    m.hits.store(5000);
    m.misses.store(1000);
    m.compilation_errors.store(3);
    m.ttl_evictions.store(15);
    m.lru_evictions.store(25);
    m.lru_evictions_bytes_freed.store(2621440);
    m.ttl_entries_moved_to_deferred.store(5);
    m.lru_entries_moved_to_deferred.store(8);
    m.total_evictions.store(40);
    m.total_bytes_freed.store(4194304);
    m.current_entry_count = 280;
    m.target_capacity_bytes = 104857600;
    m.actual_size_bytes = 115343360;
    m.utilization_ratio = 1.1;

    std::string json_str = m.toJson();
    json j = json::parse(json_str);

    EXPECT_EQ(j["hits"], 5000);
    EXPECT_EQ(j["misses"], 1000);
    EXPECT_DOUBLE_EQ(j["hit_rate"], 83.333333333333333);  // 5000/6000
    EXPECT_EQ(j["compilation_errors"], 3);

    EXPECT_EQ(j["evictions"]["ttl"], 15);
    EXPECT_EQ(j["evictions"]["lru"], 25);
    EXPECT_EQ(j["evictions"]["lru_bytes_freed"], 2621440);
    EXPECT_EQ(j["evictions"]["ttl_moved_to_deferred"], 5);
    EXPECT_EQ(j["evictions"]["lru_moved_to_deferred"], 8);
    EXPECT_EQ(j["evictions"]["total_evictions"], 40);
    EXPECT_EQ(j["evictions"]["total_bytes_freed"], 4194304);

    EXPECT_EQ(j["capacity"]["target_bytes"], 104857600);
    EXPECT_EQ(j["capacity"]["actual_bytes"], 115343360);
    EXPECT_EQ(j["capacity"]["entry_count"], 280);
    EXPECT_DOUBLE_EQ(j["capacity"]["utilization_ratio"], 1.1);
}

//============================================================================
// Deferred Cache Metrics Tests
//============================================================================

TEST_F(CacheMetricsTest, DeferredMetrics_InitialState) {
    DeferredCacheMetrics m;

    EXPECT_EQ(m.immediate_evictions.load(), 0u);
    EXPECT_EQ(m.immediate_evictions_bytes_freed.load(), 0u);
    EXPECT_EQ(m.forced_evictions.load(), 0u);
    EXPECT_EQ(m.forced_evictions_bytes_freed.load(), 0u);
    EXPECT_EQ(m.total_evictions.load(), 0u);
    EXPECT_EQ(m.total_bytes_freed.load(), 0u);

    EXPECT_EQ(m.current_entry_count, 0u);
    EXPECT_EQ(m.actual_size_bytes, 0u);
}

TEST_F(CacheMetricsTest, DeferredMetrics_JsonSerialization) {
    DeferredCacheMetrics m;

    m.immediate_evictions.store(10);
    m.immediate_evictions_bytes_freed.store(1048576);
    m.forced_evictions.store(2);
    m.forced_evictions_bytes_freed.store(524288);
    m.total_evictions.store(12);
    m.total_bytes_freed.store(1572864);
    m.current_entry_count = 3;
    m.actual_size_bytes = 1048576;

    std::string json_str = m.toJson();
    json j = json::parse(json_str);

    EXPECT_EQ(j["evictions"]["immediate"], 10);
    EXPECT_EQ(j["evictions"]["immediate_bytes_freed"], 1048576);
    EXPECT_EQ(j["evictions"]["forced"], 2);
    EXPECT_EQ(j["evictions"]["forced_bytes_freed"], 524288);
    EXPECT_EQ(j["evictions"]["total_evictions"], 12);
    EXPECT_EQ(j["evictions"]["total_bytes_freed"], 1572864);

    EXPECT_EQ(j["capacity"]["actual_bytes"], 1048576);
    EXPECT_EQ(j["capacity"]["entry_count"], 3);
}

//============================================================================
// RE2 Library Metrics Tests
//============================================================================

TEST_F(CacheMetricsTest, RE2Metrics_InitialState) {
    RE2LibraryMetrics m;

    EXPECT_EQ(m.patterns_compiled.load(), 0u);
    EXPECT_EQ(m.compilation_failures.load(), 0u);
    EXPECT_EQ(m.case_sensitive_patterns.load(), 0u);
    EXPECT_EQ(m.case_insensitive_patterns.load(), 0u);

    EXPECT_EQ(m.total_program_size_bytes, 0u);
    EXPECT_EQ(m.avg_program_size_bytes, 0u);
    EXPECT_EQ(m.max_program_size_bytes, 0u);
    EXPECT_EQ(m.min_program_size_bytes, 0u);

    EXPECT_DOUBLE_EQ(m.avg_capturing_groups, 0.0);
    EXPECT_EQ(m.max_capturing_groups, 0u);
    EXPECT_EQ(m.patterns_with_named_groups, 0u);
}

TEST_F(CacheMetricsTest, RE2Metrics_JsonSerialization) {
    RE2LibraryMetrics m;

    m.patterns_compiled.store(1000);
    m.compilation_failures.store(5);
    m.case_sensitive_patterns.store(800);
    m.case_insensitive_patterns.store(200);

    m.total_program_size_bytes = 1024000;
    m.avg_program_size_bytes = 1024;
    m.max_program_size_bytes = 10240;
    m.min_program_size_bytes = 128;

    m.avg_capturing_groups = 2.5;
    m.max_capturing_groups = 10;
    m.patterns_with_named_groups = 50;

    std::string json_str = m.toJson();
    json j = json::parse(json_str);

    EXPECT_EQ(j["program_size"]["total_bytes"], 1024000);
    EXPECT_EQ(j["program_size"]["average_bytes"], 1024);
    EXPECT_EQ(j["program_size"]["max_bytes"], 10240);
    EXPECT_EQ(j["program_size"]["min_bytes"], 128);

    EXPECT_EQ(j["patterns"]["total_compiled"], 1000);
    EXPECT_EQ(j["patterns"]["compilation_failures"], 5);
    EXPECT_EQ(j["patterns"]["case_sensitive"], 800);
    EXPECT_EQ(j["patterns"]["case_insensitive"], 200);

    EXPECT_DOUBLE_EQ(j["capturing_groups"]["avg_per_pattern"], 2.5);
    EXPECT_EQ(j["capturing_groups"]["max_per_pattern"], 10);
    EXPECT_EQ(j["capturing_groups"]["patterns_with_named_groups"], 50);
}

//============================================================================
// Combined Metrics Tests
//============================================================================

TEST_F(CacheMetricsTest, CombinedMetrics_StructureValid) {
    CacheMetrics cm;

    // Set some values
    cm.pattern_result_cache.hits.store(100);
    cm.pattern_cache.hits.store(200);
    cm.deferred_cache.immediate_evictions.store(5);
    cm.re2_library.patterns_compiled.store(1000);
    cm.generated_at = std::chrono::system_clock::now();

    std::string json_str = cm.toJson();
    json j = json::parse(json_str);

    // Verify top-level structure
    EXPECT_TRUE(j.contains("pattern_result_cache"));
    EXPECT_TRUE(j.contains("pattern_cache"));
    EXPECT_TRUE(j.contains("deferred_cache"));
    EXPECT_TRUE(j.contains("re2_library"));
    EXPECT_TRUE(j.contains("generated_at"));

    // Verify nested structure
    EXPECT_TRUE(j["pattern_result_cache"].is_object());
    EXPECT_TRUE(j["pattern_cache"].is_object());
    EXPECT_TRUE(j["deferred_cache"].is_object());
    EXPECT_TRUE(j["re2_library"].is_object());
    EXPECT_TRUE(j["generated_at"].is_string());

    // Verify values
    EXPECT_EQ(j["pattern_result_cache"]["hits"], 100);
    EXPECT_EQ(j["pattern_cache"]["hits"], 200);
    EXPECT_EQ(j["deferred_cache"]["evictions"]["immediate"], 5);
    EXPECT_EQ(j["re2_library"]["patterns"]["total_compiled"], 1000);
}

TEST_F(CacheMetricsTest, CombinedMetrics_AllCachesSections) {
    CacheMetrics cm;
    cm.generated_at = std::chrono::system_clock::now();

    std::string json_str = cm.toJson();
    json j = json::parse(json_str);

    // Pattern Result Cache sections
    EXPECT_TRUE(j["pattern_result_cache"].contains("hits"));
    EXPECT_TRUE(j["pattern_result_cache"].contains("misses"));
    EXPECT_TRUE(j["pattern_result_cache"].contains("hit_rate"));
    EXPECT_TRUE(j["pattern_result_cache"].contains("evictions"));
    EXPECT_TRUE(j["pattern_result_cache"].contains("capacity"));

    // Pattern Compilation Cache sections
    EXPECT_TRUE(j["pattern_cache"].contains("hits"));
    EXPECT_TRUE(j["pattern_cache"].contains("compilation_errors"));
    EXPECT_TRUE(j["pattern_cache"].contains("evictions"));
    EXPECT_TRUE(j["pattern_cache"].contains("capacity"));

    // Deferred Cache sections
    EXPECT_TRUE(j["deferred_cache"].contains("evictions"));
    EXPECT_TRUE(j["deferred_cache"].contains("capacity"));

    // RE2 Library sections
    EXPECT_TRUE(j["re2_library"].contains("program_size"));
    EXPECT_TRUE(j["re2_library"].contains("patterns"));
    EXPECT_TRUE(j["re2_library"].contains("capturing_groups"));
}

TEST_F(CacheMetricsTest, CombinedMetrics_TimestampFormat) {
    CacheMetrics cm;
    cm.generated_at = std::chrono::system_clock::now();

    std::string json_str = cm.toJson();
    json j = json::parse(json_str);

    std::string timestamp = j["generated_at"];

    // Should be ISO 8601 format: YYYY-MM-DDTHH:MM:SSZ
    EXPECT_EQ(timestamp.length(), 20u);
    EXPECT_EQ(timestamp[4], '-');
    EXPECT_EQ(timestamp[7], '-');
    EXPECT_EQ(timestamp[10], 'T');
    EXPECT_EQ(timestamp[13], ':');
    EXPECT_EQ(timestamp[16], ':');
    EXPECT_EQ(timestamp[19], 'Z');
}

//============================================================================
// Atomic Operations Tests (Thread Safety)
//============================================================================

TEST_F(CacheMetricsTest, AtomicCounters_ThreadSafe) {
    PatternResultCacheMetrics m;

    // Increment counters from multiple threads
    const int iterations = 1000;
    std::thread t1([&m, iterations]() {
        for (int i = 0; i < iterations; i++) {
            m.hits.fetch_add(1);
        }
    });

    std::thread t2([&m, iterations]() {
        for (int i = 0; i < iterations; i++) {
            m.misses.fetch_add(1);
        }
    });

    t1.join();
    t2.join();

    EXPECT_EQ(m.hits.load(), iterations);
    EXPECT_EQ(m.misses.load(), iterations);
}

//============================================================================
// Edge Cases
//============================================================================

TEST_F(CacheMetricsTest, UtilizationRatio_OverCapacity) {
    PatternResultCacheMetrics m;

    m.target_capacity_bytes = 100 * 1024 * 1024;
    m.actual_size_bytes = 150 * 1024 * 1024;  // 150% of target
    m.utilization_ratio = 1.5;

    std::string json_str = m.toJson();
    json j = json::parse(json_str);

    EXPECT_DOUBLE_EQ(j["capacity"]["utilization_ratio"], 1.5);
    EXPECT_GT(j["capacity"]["actual_bytes"], j["capacity"]["target_bytes"]);
}

TEST_F(CacheMetricsTest, LargeCounters) {
    PatternCacheMetrics m;

    // Test with very large counter values
    m.hits.store(UINT64_MAX - 1000);
    m.misses.store(1000);

    std::string json_str = m.toJson();
    json j = json::parse(json_str);

    EXPECT_EQ(j["hits"], UINT64_MAX - 1000);
    EXPECT_EQ(j["misses"], 1000);
}
