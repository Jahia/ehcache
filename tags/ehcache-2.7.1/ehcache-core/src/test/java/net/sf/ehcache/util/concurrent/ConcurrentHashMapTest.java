package net.sf.ehcache.util.concurrent;

import net.sf.ehcache.Element;
import org.junit.Test;

import java.util.HashSet;
import java.util.UUID;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

/**
 * @author Alex Snaps
 */
public class ConcurrentHashMapTest {

    private static final int ENTRIES = 10000;

    @Test
    public void testRandomValuesWithObjects() {

        ConcurrentHashMap<Object, KeyHolder<Object>> map = new ConcurrentHashMap<Object, KeyHolder<Object>>();

        for(int i = 0; i < ENTRIES; i++) {
            final Object o = new Object();
            map.put(o, new KeyHolder<Object>(o));
        }

        assertThings(map);
    }

    @Test
    public void testRandomValuesWithComparable() {
        ConcurrentHashMap<Comparable, KeyHolder<Object>> map = new ConcurrentHashMap<Comparable, KeyHolder<Object>>();

        for(int i = 0; i < ENTRIES; i++) {
            final EvilComparableKey o = new EvilComparableKey(UUID.randomUUID().toString());
            map.put(o, new KeyHolder<Object>(o));
        }

        assertThings(map);
    }

    @Test
    public void testReplaceWithWeirdBehavior() {
        ConcurrentHashMap<String, Element> elementMap = new ConcurrentHashMap<String, Element>();
        final Element initialElement = new Element("key", "foo");
        elementMap.put("key", initialElement);
        assertThat(elementMap.replace("key", initialElement, new Element("key", "foo")), is(true));
        assertThat(elementMap.replace("key", initialElement, new Element("key", "foo")), is(false));

        ConcurrentHashMap<String, String> stringMap = new ConcurrentHashMap<String, String>();
        final String initialString = "foo";
        stringMap.put("key", initialString);
        assertThat(stringMap.replace("key", initialString, new String(initialString)), is(true));
        assertThat(stringMap.replace("key", initialString, new String(initialString)), is(true));
    }

    @Test
    public void testRandomValues() {
        ConcurrentHashMap<Object, KeyHolder<Object>> map = new ConcurrentHashMap<Object, KeyHolder<Object>>();

        for(int i = 0; i < ENTRIES; i++) {
            final Object o;
            switch(i % 4) {
                case 0:
                    o = new Object();
                    break;
                case 1:
                    o = new EvilKey(Integer.toString(i));
                    break;
                default:
                    o = new EvilComparableKey(Integer.toString(i));

            }
            assertThat(map.put(o, new KeyHolder<Object>(o)) == null, is(true));
        }

        for (Object o : map.keySet()) {
            assertThat(o.toString(), map.get(o) != null, is(true));
            assertThat(o.toString(), map.containsKey(o), is(true));
        }

        assertThings(map);
    }

    @Test
    public void testRandomValuesWithCollisions() {
        ConcurrentHashMap<Object, KeyHolder<Object>> map = new ConcurrentHashMap<Object, KeyHolder<Object>>();

        for(int i = 0; i < ENTRIES; i++) {
            final EvilKey o = new EvilKey(UUID.randomUUID().toString());
            map.put(o, new KeyHolder<Object>(o));
        }

        assertThings(map);
    }

    @Test
    public void testUsesObjectIdentityForElementsOnly() {

        final String key = "ourKey";

        ConcurrentHashMap<String, Object> map = new ConcurrentHashMap<String, Object>();

        String value = new String("key");
        String valueAgain = new String("key");
        map.put(key, value);
        assertThat(map.replace(key, valueAgain, valueAgain), is(true));
        assertThat(map.replace(key, value, valueAgain), is(true));

        Element elementValue = new Element(key, value);
        Element elementValueAgain = new Element(key, value);
        map.put(key, elementValue);
        assertThat(map.replace(key, elementValueAgain, elementValue), is(false));
        assertThat(map.replace(key, elementValue, elementValueAgain), is(true));
        assertThat(map.replace(key, elementValue, elementValueAgain), is(false));
        assertThat(map.replace(key, elementValueAgain, elementValue), is(true));
    }

    @Test
    public void testActuallyWorks() throws InterruptedException {
        final long top = 100000000;
        final String key = "counter";
        final ConcurrentHashMap<String, Long> map = new ConcurrentHashMap<String, Long>();
        map.put(key, 0L);

        final Runnable runnable = new Runnable() {
            @Override
            public void run() {
                for(Long val = map.get(key); val < top && map.replace(key, val, val + 1); val = map.get(key));
            }
        };

        Thread[] threads = new Thread[Runtime.getRuntime().availableProcessors() * 2];
        for (int i = 0, threadsLength = threads.length; i < threadsLength; ) {
            threads[i] = new Thread(runnable);
            threads[i].setName("Mutation thread #" + ++i);
            threads[i - 1].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        assertThat(map.get(key), is(top));

    }

    private void assertThings(final ConcurrentHashMap<?, ?> map) {
        assertThat(map.size(), is(ENTRIES));

        for(int i = 0; i < 100; i ++) {
            final HashSet randomValues = new HashSet(map.getRandomValues(ENTRIES));
            assertThat(randomValues.size(), is(ENTRIES));
            for (Object randomValue : randomValues) {
                assertThat(randomValue, instanceOf(KeyHolder.class));
                final Object key = ((KeyHolder)randomValue).key;
                assertThat("Missing " + key, map.containsKey(key), is(true));
            }
        }
    }
}

class KeyHolder<K> {
    final K key;

    KeyHolder(final K key) {
        this.key = key;
    }
}

class EvilKey {
    final String value;

    EvilKey(final String value) {
        this.value = value;
    }

    @Override
    public int hashCode() {
        return Math.abs(this.value.hashCode() % 2);
    }

    @Override
    public boolean equals(final Object obj) {
        return obj != null && obj.getClass() == this.getClass() && ((EvilKey)obj).value.equals(value);
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName() + " { " + value + " }";
    }
}

class EvilComparableKey extends EvilKey implements Comparable<EvilComparableKey> {

    EvilComparableKey(final String value) {
        super(value);
    }

    @Override
    public int compareTo(final EvilComparableKey o) {
        return value.compareTo(o != null ? o.value : null);
    }
}
