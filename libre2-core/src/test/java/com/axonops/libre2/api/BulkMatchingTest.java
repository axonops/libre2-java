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
}
