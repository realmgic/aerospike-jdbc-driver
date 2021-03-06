package com.nosqldriver.util;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.stream.IntStream;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singleton;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PagedCollectionTest {
    private static final String[] beatles = new String[] {"John Lennon", "Paul McCartney", "George Harrison", "Ringo Starr"};


    @Test
    void empty() {
        Collection<String> collection =  new PagedCollection<>(new ArrayList<>(), 0, true);
        assertEquals(0, collection.size());
        assertTrue(collection.isEmpty());
        assertFalse(collection.iterator().hasNext());

        assertFalse(collection.add("hello"));
        assertTrue(collection.isEmpty());
    }


    @Test
    void unsupportedOperations() {
        Collection<String> collection =  new PagedCollection<>(singletonList("hello"), 1, true);
        assertFalse(collection.isEmpty());
        assertTrue(collection.contains("hello"));
        assertThrows(UnsupportedOperationException.class, () -> collection.remove("hello"));
        assertThrows(UnsupportedOperationException.class, () -> collection.removeAll(singleton("hello")));
        assertThrows(UnsupportedOperationException.class, () -> collection.retainAll(singleton("hello")));
        assertThrows(UnsupportedOperationException.class, () -> collection.addAll(singleton("hello")));
        assertThrows(UnsupportedOperationException.class, collection::clear);
    }


    @Test
    void singleRewrite() {
        Collection<String> collection =  new PagedCollection<>(new ArrayList<>(), 1, true);
        assertEquals(0, collection.size());
        assertTrue(collection.isEmpty());
        assertFalse(collection.iterator().hasNext());

        assertFalse(collection.add("hello"));
        assertFalse(collection.isEmpty());
        assertTrue(collection.contains("hello"));
        assertFalse(collection.contains("bye"));
        assertTrue(collection.iterator().hasNext());
        assertEquals("hello", collection.iterator().next());

        assertFalse(collection.add("bye"));
        assertFalse(collection.isEmpty());
        assertTrue(collection.contains("bye"));
        assertTrue(collection.iterator().hasNext());
        assertEquals("bye", collection.iterator().next());
    }


    @Test
    void doubleRewrite2_2() {
        assertMultipleRewrite(beatles, 2);
    }

    @Test
    void doubleRewrite3_1() {
        assertMultipleRewrite(beatles, 3);
    }


    @Test
    void assertMultipleRewrite() {
        String[] data = IntStream.range(0, 10).boxed().map(i -> "something" + i).toArray(String[]::new);
        for (int pageSize = 1; pageSize <= 10; pageSize++) {
            assertMultipleRewrite(data, pageSize);
        }
    }


    @Test
    void singleAppend() {
        Collection<String> collection =  new PagedCollection<>(new ArrayList<>(), 1, false);
        assertEquals(0, collection.size());
        assertTrue(collection.isEmpty());
        assertFalse(collection.iterator().hasNext());

        assertTrue(collection.add("hello"));
        assertFalse(collection.isEmpty());
        assertTrue(collection.iterator().hasNext());
        assertEquals("hello", collection.iterator().next());

        assertTrue(collection.add("bye"));
        assertFalse(collection.isEmpty());
        assertTrue(collection.iterator().hasNext());
        assertArrayEquals(new String[] {"hello", "bye"}, collection.toArray(new String[0]));
    }


    @Test
    void singleAppendSorted() {
        Collection<String> collection =  new PagedCollection<>(new TreeSet<>(), 1, false);
        assertEquals(0, collection.size());
        assertTrue(collection.isEmpty());
        assertFalse(collection.iterator().hasNext());

        assertTrue(collection.add("hello"));
        assertFalse(collection.isEmpty());
        assertTrue(collection.iterator().hasNext());
        assertEquals("hello", collection.iterator().next());

        assertTrue(collection.add("bye"));
        assertFalse(collection.isEmpty());
        assertTrue(collection.iterator().hasNext());
        assertArrayEquals(new String[] {"bye", "hello"}, collection.toArray(new String[0]));
    }


    @Test
    void ascSortedMoreThanPageSize() {
        Collection<String> collection =  new PagedCollection<>(new TreeSet<>(), 4, false, NavigableSet::pollLast);

        String[] input = new String[] {"b", "d", "c", "f", "a", "e"};
        for (String s : input) {
            assertTrue(collection.add(s));
        }
        assertArrayEquals(new String[] {"a", "b", "c", "d"}, collection.toArray(new String[0]));
    }

    @Test
    void containsAllEmpty() {
        Collection<String> collection =  new PagedCollection<>(new TreeSet<>(), 4, false, NavigableSet::pollLast);
        assertTrue(collection.containsAll(emptyList()));
    }

    @Test
    void containsAll() {
        Collection<String> collection = new PagedCollection<>(new TreeSet<>(asList("a", "b", "c", "d")), 4, false, NavigableSet::pollLast);
        assertTrue(collection.containsAll(asList("a", "b", "c", "d")));
    }

    private void assertMultipleRewrite(String[] data, int  pageSize) {
        Collection<String> collection = new PagedCollection<>(new ArrayList<>(), pageSize, true);
        assertEquals(0, collection.size());
        assertTrue(collection.isEmpty());
        assertFalse(collection.iterator().hasNext());


        for (int i = 0, j = 0; i < data.length; i++) {
            assertEquals((i + 1) % pageSize != 0, collection.add(data[i]));
            assertFalse(collection.isEmpty());
            assertTrue(collection.iterator().hasNext());
            if (i % pageSize == 0) {
                j = i;
            }
            assertEquals(asList(data).subList(j, i + 1), asList(collection.toArray()));
        }
    }

    @Test
    void validateToString() {
        assertTrue(new PagedCollection<>(new TreeSet<>(asList("a", "b", "c", "d")), 4, false, NavigableSet::pollLast).toString().startsWith("PagedCollection"));
    }
}