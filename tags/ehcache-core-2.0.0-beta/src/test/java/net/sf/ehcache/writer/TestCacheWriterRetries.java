package net.sf.ehcache.writer;

import net.sf.ehcache.CacheException;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;

import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class TestCacheWriterRetries implements CacheWriter {
    private final int retries;
    private final Map<Object, Element> writtenElements = new HashMap<Object, Element>();
    private final Map<Object, Integer> retryCount = new HashMap<Object, Integer>();
    private final Map<Object, Integer> writeCount = new HashMap<Object, Integer>();
    private final Map<Object, Integer> deleteCount = new HashMap<Object, Integer>();

  public TestCacheWriterRetries(int retries) {
        this.retries = retries;
    }

    public Map<Object, Element> getWrittenElements() {
        return writtenElements;
    }

    public Map<Object, Integer> getWriteCount() {
        return writeCount;
    }

    public Map<Object, Integer> getDeleteCount() {
        return deleteCount;
    }

    public void init() {
        // nothing to do
    }

    public void dispose() throws CacheException {
        // nothing to do
    }

    private void failUntilNoMoreRetries(Object key) {
        int remainingRetries;
        if (!retryCount.containsKey(key)) {
            remainingRetries = retries;
        } else {
            remainingRetries = retryCount.get(key);
        }
        if (remainingRetries-- > 0) {
            retryCount.put(key, remainingRetries);
            throw new RuntimeException("Throwing exception to test retries, " + remainingRetries + " remaining for " + key);
        }
        retryCount.remove(key);
    }

    private void increaseWriteCount(Object key) {
        if (!writeCount.containsKey(key)) {
            writeCount.put(key, 1);
        } else {
            writeCount.put(key, writeCount.get(key) + 1);
        }
    }

    private void increaseDeleteCount(Object key) {
        if (!deleteCount.containsKey(key)) {
            deleteCount.put(key, 1);
        } else {
            deleteCount.put(key, deleteCount.get(key) + 1);
        }
    }

    private void put(Object key, Element element) {
        if (!deleteCount.containsKey(key)) {
            writtenElements.put(key, element);
        }
        increaseWriteCount(key);
    }

    public synchronized void write(Element element) throws CacheException {
        final Object key = element.getObjectKey();
        failUntilNoMoreRetries(key);
        put(key, element);
    }

    public synchronized void writeAll(Collection<Element> elements) throws CacheException {
        Iterator<Element> it = elements.iterator();
        while (it.hasNext()) {
            Element element = it.next();
            // fail on the last item in the batch
            final Object key = element.getObjectKey();
            if (!it.hasNext()) {
                failUntilNoMoreRetries(key);
            }
            put(key, element);
        }
    }

    private void remove(Object key) {
        writtenElements.remove(key);
        increaseDeleteCount(key);
    }

    public synchronized void delete(Object key) throws CacheException {
        failUntilNoMoreRetries(key);
        remove(key);
    }

    public synchronized void deleteAll(Collection<Object> keys) throws CacheException {
        Iterator<Object> it = keys.iterator();
        while (it.hasNext()) {
            Object key = it.next();
            if (!it.hasNext()) {
                failUntilNoMoreRetries(key);
            }
            remove(key);
        }
    }

    public CacheWriter clone(Ehcache cache) throws CloneNotSupportedException {
        return null;
    }
}