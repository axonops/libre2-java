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
package com.axonops.libre2.api;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for bulk matching operations (Collection and array variants).
 */
class BulkMatchingTest {

    @Test
    void testMatchAll_Collection_Basic() {
        Pattern pattern = Pattern.compile("test.*");

        List<String> inputs = List.of("test1", "prod1", "test2", "other");
        boolean[] results = pattern.matchAll(inputs);

        assertArrayEquals(new boolean[]{true, false, true, false}, results);
    }

    @Test
    void testMatchAll_Array_Basic() {
        Pattern pattern = Pattern.compile("\\d{3}-\\d{4}");

        String[] inputs = {"123-4567", "invalid", "999-8888", "nope"};
        boolean[] results = pattern.matchAll(inputs);

        assertArrayEquals(new boolean[]{true, false, true, false}, results);
    }

    @Test
    void testMatchAll_Empty() {
        Pattern pattern = Pattern.compile("test");

        boolean[] results = pattern.matchAll(Collections.emptyList());
        assertEquals(0, results.length);

        boolean[] results2 = pattern.matchAll(new String[0]);
        assertEquals(0, results2.length);
    }

    @Test
    void testMatchAll_Set() {
        Pattern pattern = Pattern.compile("[a-z]+");

        Set<String> inputs = Set.of("abc", "123", "xyz", "456");
        boolean[] results = pattern.matchAll(inputs);

        // Set order is not guaranteed, but count should be correct
        int matchCount = 0;
        for (boolean match : results) {
            if (match) matchCount++;
        }
        assertEquals(2, matchCount);  // "abc" and "xyz"
    }

    @Test
    void testMatchAll_Queue() {
        Pattern pattern = Pattern.compile("item\\d+");

        Queue<String> inputs = new LinkedList<>(List.of("item1", "other", "item2"));
        boolean[] results = pattern.matchAll(inputs);

        assertArrayEquals(new boolean[]{true, false, true}, results);
    }

    @Test
    void testMatchAll_NullInput() {
        Pattern pattern = Pattern.compile("test");

        assertThrows(NullPointerException.class, () -> pattern.matchAll((Collection<String>) null));
        assertThrows(NullPointerException.class, () -> pattern.matchAll((String[]) null));
    }

    @Test
    void testFilter_Basic() {
        Pattern pattern = Pattern.compile("test.*");

        List<String> inputs = List.of("test1", "prod1", "test2", "other");
        List<String> filtered = pattern.filter(inputs);

        assertEquals(List.of("test1", "test2"), filtered);
    }

    @Test
    void testFilter_NoMatches() {
        Pattern pattern = Pattern.compile("nomatch");

        List<String> inputs = List.of("test1", "test2", "test3");
        List<String> filtered = pattern.filter(inputs);

        assertTrue(filtered.isEmpty());
    }

    @Test
    void testFilter_AllMatch() {
        Pattern pattern = Pattern.compile("test\\d");

        List<String> inputs = List.of("test1", "test2", "test3");
        List<String> filtered = pattern.filter(inputs);

        assertEquals(inputs, filtered);
    }

    @Test
    void testFilterNot_Basic() {
        Pattern pattern = Pattern.compile("test.*");

        List<String> inputs = List.of("test1", "prod1", "test2", "other");
        List<String> filtered = pattern.filterNot(inputs);

        assertEquals(List.of("prod1", "other"), filtered);
    }

    @Test
    void testFilterNot_AllMatch() {
        Pattern pattern = Pattern.compile("test\\d");

        List<String> inputs = List.of("test1", "test2");
        List<String> filtered = pattern.filterNot(inputs);

        assertTrue(filtered.isEmpty());
    }

    @Test
    void testRetainMatches_List() {
        Pattern pattern = Pattern.compile("test.*");

        List<String> inputs = new ArrayList<>(List.of("test1", "prod1", "test2", "other"));
        int removed = pattern.retainMatches(inputs);

        assertEquals(2, removed);
        assertEquals(List.of("test1", "test2"), inputs);
    }

    @Test
    void testRetainMatches_Set() {
        Pattern pattern = Pattern.compile("[a-z]+");

        Set<String> inputs = new HashSet<>(Set.of("abc", "123", "xyz"));
        int removed = pattern.retainMatches(inputs);

        assertEquals(1, removed);
        assertEquals(Set.of("abc", "xyz"), inputs);
    }

    @Test
    void testRemoveMatches_List() {
        Pattern pattern = Pattern.compile("test.*");

        List<String> inputs = new ArrayList<>(List.of("test1", "prod1", "test2", "other"));
        int removed = pattern.removeMatches(inputs);

        assertEquals(2, removed);
        assertEquals(List.of("prod1", "other"), inputs);
    }

    @Test
    void testRemoveMatches_Set() {
        Pattern pattern = Pattern.compile("[a-z]+");

        Set<String> inputs = new HashSet<>(Set.of("abc", "123", "xyz"));
        int removed = pattern.removeMatches(inputs);

        assertEquals(2, removed);
        assertEquals(Set.of("123"), inputs);
    }

    @Test
    void testRetainMatches_ImmutableCollection() {
        Pattern pattern = Pattern.compile("test");

        List<String> immutable = List.of("test", "other");
        assertThrows(UnsupportedOperationException.class, () -> pattern.retainMatches(immutable));
    }

    @Test
    void testFilterByKey_Basic() {
        Pattern pattern = Pattern.compile("test.*");

        Map<String, Integer> inputs = Map.of(
            "test_1", 100,
            "prod_1", 200,
            "test_2", 300
        );

        Map<String, Integer> filtered = pattern.filterByKey(inputs);

        assertEquals(2, filtered.size());
        assertEquals(100, filtered.get("test_1"));
        assertEquals(300, filtered.get("test_2"));
        assertNull(filtered.get("prod_1"));
    }

    @Test
    void testFilterByValue_Basic() {
        Pattern pattern = Pattern.compile(".*@example\\.com");

        Map<Integer, String> inputs = Map.of(
            1, "user@example.com",
            2, "invalid",
            3, "admin@example.com"
        );

        Map<Integer, String> filtered = pattern.filterByValue(inputs);

        assertEquals(2, filtered.size());
        assertEquals("user@example.com", filtered.get(1));
        assertEquals("admin@example.com", filtered.get(3));
        assertNull(filtered.get(2));
    }

    @Test
    void testFilterNotByKey_Basic() {
        Pattern pattern = Pattern.compile("test.*");

        Map<String, Integer> inputs = Map.of(
            "test_1", 100,
            "prod_1", 200,
            "test_2", 300
        );

        Map<String, Integer> filtered = pattern.filterNotByKey(inputs);

        assertEquals(1, filtered.size());
        assertEquals(200, filtered.get("prod_1"));
    }

    @Test
    void testFilterNotByValue_Basic() {
        Pattern pattern = Pattern.compile(".*@example\\.com");

        Map<Integer, String> inputs = Map.of(
            1, "user@example.com",
            2, "invalid",
            3, "admin@example.com"
        );

        Map<Integer, String> filtered = pattern.filterNotByValue(inputs);

        assertEquals(1, filtered.size());
        assertEquals("invalid", filtered.get(2));
    }

    @Test
    void testRetainMatchesByKey_Basic() {
        Pattern pattern = Pattern.compile("test.*");

        Map<String, Integer> map = new HashMap<>();
        map.put("test_1", 100);
        map.put("prod_1", 200);
        map.put("test_2", 300);

        int removed = pattern.retainMatchesByKey(map);

        assertEquals(1, removed);
        assertEquals(2, map.size());
        assertEquals(100, map.get("test_1"));
        assertEquals(300, map.get("test_2"));
    }

    @Test
    void testRetainMatchesByValue_Basic() {
        Pattern pattern = Pattern.compile("\\d+");

        Map<String, String> map = new HashMap<>();
        map.put("a", "123");
        map.put("b", "abc");
        map.put("c", "456");

        int removed = pattern.retainMatchesByValue(map);

        assertEquals(1, removed);
        assertEquals(2, map.size());
        assertEquals("123", map.get("a"));
        assertEquals("456", map.get("c"));
    }

    @Test
    void testRemoveMatchesByKey_Basic() {
        Pattern pattern = Pattern.compile("test.*");

        Map<String, Integer> map = new HashMap<>();
        map.put("test_1", 100);
        map.put("prod_1", 200);
        map.put("test_2", 300);

        int removed = pattern.removeMatchesByKey(map);

        assertEquals(2, removed);
        assertEquals(1, map.size());
        assertEquals(200, map.get("prod_1"));
    }

    @Test
    void testRemoveMatchesByValue_Basic() {
        Pattern pattern = Pattern.compile("\\d+");

        Map<String, String> map = new HashMap<>();
        map.put("a", "123");
        map.put("b", "abc");
        map.put("c", "456");

        int removed = pattern.removeMatchesByValue(map);

        assertEquals(2, removed);
        assertEquals(1, map.size());
        assertEquals("abc", map.get("b"));
    }

    @Test
    void testBulk_LargeCollection() {
        Pattern pattern = Pattern.compile("item\\d+");

        // Create 1000 strings
        List<String> inputs = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            if (i % 2 == 0) {
                inputs.add("item" + i);
            } else {
                inputs.add("other" + i);
            }
        }

        boolean[] results = pattern.matchAll(inputs);

        assertEquals(1000, results.length);

        int matchCount = 0;
        for (boolean match : results) {
            if (match) matchCount++;
        }

        assertEquals(500, matchCount);  // Every other item
    }

    @Test
    void testFilter_PreservesOrder() {
        Pattern pattern = Pattern.compile("keep\\d");

        List<String> inputs = List.of("keep1", "drop1", "keep2", "drop2", "keep3");
        List<String> filtered = pattern.filter(inputs);

        // Order should be preserved
        assertEquals(List.of("keep1", "keep2", "keep3"), filtered);
    }

    @Test
    void testMapFiltering_EmptyMap() {
        Pattern pattern = Pattern.compile("test");

        Map<String, Integer> empty = Map.of();

        assertEquals(0, pattern.filterByKey(empty).size());
        assertEquals(0, pattern.filterNotByKey(empty).size());
        assertEquals(0, pattern.retainMatchesByKey(new HashMap<>(empty)));
        assertEquals(0, pattern.removeMatchesByKey(new HashMap<>(empty)));
    }

    @Test
    void testCollectionFiltering_DifferentTypes() {
        Pattern pattern = Pattern.compile("[a-z]+");

        // ArrayList
        List<String> list = new ArrayList<>(List.of("abc", "123", "xyz"));
        assertEquals(2, pattern.filter(list).size());

        // HashSet
        Set<String> set = new HashSet<>(Set.of("abc", "123", "xyz"));
        assertEquals(2, pattern.filter(set).size());

        // LinkedList (Queue)
        Queue<String> queue = new LinkedList<>(List.of("abc", "123", "xyz"));
        assertEquals(2, pattern.filter(queue).size());
    }

    @Test
    void testFilterNot_AllCollectionTypes() {
        Pattern pattern = Pattern.compile("\\d+");

        // ArrayList
        List<String> arrayList = new ArrayList<>(List.of("123", "abc", "456"));
        assertEquals(List.of("abc"), pattern.filterNot(arrayList));

        // HashSet
        Set<String> hashSet = new HashSet<>(Set.of("123", "abc", "456"));
        List<String> filtered = pattern.filterNot(hashSet);
        assertEquals(1, filtered.size());
        assertTrue(filtered.contains("abc"));

        // LinkedList (as Queue)
        Queue<String> linkedList = new LinkedList<>(List.of("123", "abc", "456"));
        assertEquals(1, pattern.filterNot(linkedList).size());
    }

    @Test
    void testRetainMatches_AllCollectionTypes() {
        Pattern pattern = Pattern.compile("[a-z]+");

        // ArrayList
        List<String> arrayList = new ArrayList<>(List.of("abc", "123", "xyz"));
        assertEquals(1, pattern.retainMatches(arrayList));
        assertEquals(2, arrayList.size());

        // HashSet
        Set<String> hashSet = new HashSet<>(Set.of("abc", "123", "xyz"));
        assertEquals(1, pattern.retainMatches(hashSet));
        assertEquals(2, hashSet.size());

        // LinkedList (as Queue)
        Queue<String> queue = new LinkedList<>(List.of("abc", "123", "xyz"));
        assertEquals(1, pattern.retainMatches(queue));
        assertEquals(2, queue.size());

        // TreeSet (sorted)
        Set<String> treeSet = new TreeSet<>(Set.of("abc", "123", "xyz"));
        assertEquals(1, pattern.retainMatches(treeSet));
        assertEquals(Set.of("abc", "xyz"), treeSet);
    }

    @Test
    void testRemoveMatches_AllCollectionTypes() {
        Pattern pattern = Pattern.compile("[a-z]+");

        // ArrayList
        List<String> arrayList = new ArrayList<>(List.of("abc", "123", "xyz"));
        assertEquals(2, pattern.removeMatches(arrayList));
        assertEquals(List.of("123"), arrayList);

        // HashSet
        Set<String> hashSet = new HashSet<>(Set.of("abc", "123", "xyz"));
        assertEquals(2, pattern.removeMatches(hashSet));
        assertEquals(Set.of("123"), hashSet);

        // LinkedList (as Queue)
        Queue<String> queue = new LinkedList<>(List.of("abc", "123", "xyz"));
        assertEquals(2, pattern.removeMatches(queue));
        assertEquals(1, queue.size());
        assertTrue(queue.contains("123"));

        // TreeSet
        Set<String> treeSet = new TreeSet<>(Set.of("abc", "123", "xyz"));
        assertEquals(2, pattern.removeMatches(treeSet));
        assertEquals(Set.of("123"), treeSet);
    }

    @Test
    void testMapOperations_AllVariants() {
        Pattern pattern = Pattern.compile("key\\d+");

        // Test all 8 map operations
        Map<String, String> map1 = new HashMap<>(Map.of("key1", "val1", "other", "val2", "key2", "val3"));
        assertEquals(2, pattern.filterByKey(map1).size());

        Map<String, String> map2 = new HashMap<>(Map.of("key1", "val1", "other", "val2"));
        assertEquals(1, pattern.filterNotByKey(map2).size());

        Map<String, String> map3 = new HashMap<>(Map.of("k1", "key1", "k2", "other", "k3", "key2"));
        assertEquals(2, pattern.filterByValue(map3).size());

        Map<String, String> map4 = new HashMap<>(Map.of("k1", "key1", "k2", "other"));
        assertEquals(1, pattern.filterNotByValue(map4).size());

        Map<String, Integer> map5 = new HashMap<>(Map.of("key1", 1, "other", 2, "key2", 3));
        assertEquals(1, pattern.retainMatchesByKey(map5));
        assertEquals(2, map5.size());

        Map<String, Integer> map6 = new HashMap<>(Map.of("key1", 1, "other", 2));
        assertEquals(1, pattern.removeMatchesByKey(map6));
        assertEquals(1, map6.size());

        Map<Integer, String> map7 = new HashMap<>(Map.of(1, "key1", 2, "other", 3, "key2"));
        assertEquals(1, pattern.retainMatchesByValue(map7));
        assertEquals(2, map7.size());

        Map<Integer, String> map8 = new HashMap<>(Map.of(1, "key1", 2, "other"));
        assertEquals(1, pattern.removeMatchesByValue(map8));
        assertEquals(1, map8.size());
    }

    @Test
    void testMatchAll_LinkedHashSet_PreservesOrder() {
        Pattern pattern = Pattern.compile("keep.*");

        // LinkedHashSet preserves insertion order
        Set<String> linkedSet = new LinkedHashSet<>();
        linkedSet.add("keep1");
        linkedSet.add("drop1");
        linkedSet.add("keep2");
        linkedSet.add("drop2");

        boolean[] results = pattern.matchAll(linkedSet);
        assertEquals(4, results.length);

        // Verify order matches insertion order
        List<String> asList = new ArrayList<>(linkedSet);
        boolean[] expected = {true, false, true, false};
        assertArrayEquals(expected, results);
    }

    @Test
    void testFilter_Queue_FIFO_Order() {
        Pattern pattern = Pattern.compile("item\\d");

        // Queue maintains FIFO order
        Queue<String> queue = new LinkedList<>(List.of("item1", "other", "item2", "item3"));
        List<String> filtered = pattern.filter(queue);

        // Should preserve order
        assertEquals(List.of("item1", "item2", "item3"), filtered);
    }

    // ========== Additional Scenarios ==========

    @Test
    void testMatchAll_WithNullElements() {
        Pattern pattern = Pattern.compile("test.*");

        // Null elements in array should not crash
        String[] arrayWithNulls = {"test1", null, "test2", null, "other"};
        boolean[] results = pattern.matchAll(arrayWithNulls);

        assertEquals(5, results.length);
        // Nulls should be treated as non-matches (handled by JNI)
        assertTrue(results[0]);   // "test1" matches
        assertFalse(results[1]);  // null doesn't match
        assertTrue(results[2]);   // "test2" matches
        assertFalse(results[3]);  // null doesn't match
        assertFalse(results[4]);  // "other" doesn't match
    }

    @Test
    void testFilter_WithDuplicates() {
        Pattern pattern = Pattern.compile("keep");

        // List with duplicates
        List<String> withDuplicates = List.of("keep", "drop", "keep", "keep", "drop");
        List<String> filtered = pattern.filter(withDuplicates);

        // All "keep" entries preserved, including duplicates
        assertEquals(3, filtered.size());
        assertEquals(List.of("keep", "keep", "keep"), filtered);
    }

    @Test
    void testRetainMatches_WithDuplicates() {
        Pattern pattern = Pattern.compile("\\d+");

        // ArrayList with duplicates: 3x"123", 1x"456" = 4 numeric, 2 non-numeric
        List<String> list = new ArrayList<>(List.of("123", "abc", "123", "xyz", "456", "123"));
        int removed = pattern.retainMatches(list);

        assertEquals(2, removed);  // "abc" and "xyz" removed
        assertEquals(4, list.size());  // 3x"123" + 1x"456" remain
        // All numeric strings retained (including duplicates)
        assertEquals(3, list.stream().filter(s -> s.equals("123")).count());  // 3x "123"
        assertEquals(1, list.stream().filter(s -> s.equals("456")).count());  // 1x "456"
        assertTrue(list.stream().allMatch(s -> s.matches("\\d+")));  // All are numeric
    }

    @Test
    void testMapFiltering_TreeMap() {
        Pattern pattern = Pattern.compile("key[1-2]");

        // TreeMap maintains sorted order
        Map<String, Integer> treeMap = new TreeMap<>(Map.of(
            "key3", 3,
            "key1", 1,
            "other", 5,
            "key2", 2
        ));

        Map<String, Integer> filtered = pattern.filterByKey(treeMap);

        assertEquals(2, filtered.size());
        assertEquals(1, filtered.get("key1"));
        assertEquals(2, filtered.get("key2"));
        assertNull(filtered.get("key3"));
        assertNull(filtered.get("other"));
    }

    @Test
    void testMapFiltering_LinkedHashMap() {
        Pattern pattern = Pattern.compile("user\\d+");

        // LinkedHashMap preserves insertion order
        Map<String, String> linkedMap = new LinkedHashMap<>();
        linkedMap.put("user2", "second");
        linkedMap.put("admin", "first");
        linkedMap.put("user1", "third");

        Map<String, String> filtered = pattern.filterByKey(linkedMap);

        assertEquals(2, filtered.size());
        // LinkedHashMap order should be preserved
        List<String> keys = new ArrayList<>(filtered.keySet());
        assertTrue(keys.contains("user2"));
        assertTrue(keys.contains("user1"));
    }

    @Test
    void testMapFiltering_ConcurrentHashMap() {
        Pattern pattern = Pattern.compile("data_.*");

        // ConcurrentHashMap (thread-safe map)
        Map<String, Integer> concurrentMap = new java.util.concurrent.ConcurrentHashMap<>();
        concurrentMap.put("data_1", 100);
        concurrentMap.put("meta_1", 200);
        concurrentMap.put("data_2", 300);

        Map<String, Integer> filtered = pattern.filterByKey(concurrentMap);

        assertEquals(2, filtered.size());
        assertEquals(100, filtered.get("data_1"));
        assertEquals(300, filtered.get("data_2"));
    }

    @Test
    void testRetainMatchesByKey_TreeMap() {
        Pattern pattern = Pattern.compile("keep.*");

        // TreeMap (sorted)
        Map<String, Integer> treeMap = new TreeMap<>(Map.of(
            "keep1", 1,
            "drop1", 2,
            "keep2", 3
        ));

        int removed = pattern.retainMatchesByKey(treeMap);

        assertEquals(1, removed);
        assertEquals(2, treeMap.size());
        assertTrue(treeMap.containsKey("keep1"));
        assertTrue(treeMap.containsKey("keep2"));
    }

    @Test
    void testRetainMatchesByValue_LinkedHashMap() {
        Pattern pattern = Pattern.compile("valid");

        Map<Integer, String> linkedMap = new LinkedHashMap<>();
        linkedMap.put(1, "valid");
        linkedMap.put(2, "invalid");
        linkedMap.put(3, "valid");

        int removed = pattern.retainMatchesByValue(linkedMap);

        assertEquals(1, removed);
        assertEquals(2, linkedMap.size());
        assertEquals("valid", linkedMap.get(1));
        assertEquals("valid", linkedMap.get(3));
    }

    @Test
    void testBulk_VeryLargeCollection_10k() {
        Pattern pattern = Pattern.compile("item\\d{4}");  // item + 4 digits

        // Create 10,000 strings
        List<String> inputs = new ArrayList<>(10_000);
        for (int i = 0; i < 10_000; i++) {
            inputs.add("item" + i);
        }

        // Test matchAll
        boolean[] results = pattern.matchAll(inputs);
        assertEquals(10_000, results.length);

        // Count matches (items 0-9999, all 4 digits match from item1000 onward)
        int matchCount = 0;
        for (boolean match : results) {
            if (match) matchCount++;
        }
        assertEquals(9000, matchCount);  // item1000 through item9999

        // Test filter
        List<String> filtered = pattern.filter(inputs);
        assertEquals(9000, filtered.size());

        // Test in-place
        List<String> mutable = new ArrayList<>(inputs);
        int removed = pattern.retainMatches(mutable);
        assertEquals(1000, removed);
        assertEquals(9000, mutable.size());
    }

    @Test
    void testBulk_VeryLargeMap_10k() {
        Pattern pattern = Pattern.compile("user_\\d+");

        // Create 10,000 entry map
        Map<String, Integer> largeMap = new HashMap<>();
        for (int i = 0; i < 10_000; i++) {
            if (i % 2 == 0) {
                largeMap.put("user_" + i, i);
            } else {
                largeMap.put("admin_" + i, i);
            }
        }

        // Test filterByKey
        Map<String, Integer> filtered = pattern.filterByKey(largeMap);
        assertEquals(5000, filtered.size());

        // Test in-place
        Map<String, Integer> mutable = new HashMap<>(largeMap);
        int removed = pattern.retainMatchesByKey(mutable);
        assertEquals(5000, removed);
        assertEquals(5000, mutable.size());
    }

    @Test
    void testMatchAll_EmptyStrings() {
        Pattern pattern = Pattern.compile(".*");  // Matches everything including empty

        List<String> inputs = List.of("", "test", "", "other", "");
        boolean[] results = pattern.matchAll(inputs);

        // ".*" should match empty strings
        assertArrayEquals(new boolean[]{true, true, true, true, true}, results);
    }

    @Test
    void testFilter_PreservesDuplicateOrder() {
        Pattern pattern = Pattern.compile("keep");

        // Specific order with duplicates
        List<String> ordered = List.of("keep", "drop", "keep", "other", "keep");
        List<String> filtered = pattern.filter(ordered);

        // Order and duplicates preserved
        assertEquals(List.of("keep", "keep", "keep"), filtered);
    }

    @Test
    void testRemoveMatchesByKey_ConcurrentHashMap() {
        Pattern pattern = Pattern.compile("tmp_.*");

        Map<String, String> concurrentMap = new java.util.concurrent.ConcurrentHashMap<>();
        concurrentMap.put("tmp_cache", "data1");
        concurrentMap.put("perm_data", "data2");
        concurrentMap.put("tmp_session", "data3");

        int removed = pattern.removeMatchesByKey(concurrentMap);

        assertEquals(2, removed);
        assertEquals(1, concurrentMap.size());
        assertTrue(concurrentMap.containsKey("perm_data"));
    }
}
