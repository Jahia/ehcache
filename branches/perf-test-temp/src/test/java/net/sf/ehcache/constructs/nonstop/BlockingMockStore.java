/**
 *  Copyright 2003-2010 Terracotta, Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package net.sf.ehcache.constructs.nonstop;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.sf.ehcache.CacheException;
import net.sf.ehcache.Element;
import net.sf.ehcache.Status;
import net.sf.ehcache.search.Attribute;
import net.sf.ehcache.search.Results;
import net.sf.ehcache.search.attribute.AttributeExtractor;
import net.sf.ehcache.store.ElementValueComparator;
import net.sf.ehcache.store.Policy;
import net.sf.ehcache.store.Store;
import net.sf.ehcache.store.StoreListener;
import net.sf.ehcache.store.StoreQuery;
import net.sf.ehcache.writer.CacheWriterManager;

/**
 * All operations in this Store never returns
 *
 * @author Abhishek Sanoujam
 *
 */
public class BlockingMockStore implements Store {

    private static final List<String> skipMethods;
    static {
        // list of methods (in Store) which are:
        // - indirectly used from other methods in Ehcache before reaching the Store layer
        // - nonstopStore does not delegate these methods to the executorService
        List<String> skip = new ArrayList<String>();
        skip.add("bufferFull");
        skip.add("containsKeyOnDisk");
        skip.add("containsKeyOffHeap");
        skip.add("getOffHeapSize");
        skipMethods = skip;
    }

    private volatile boolean blocking = true;

    public void setBlocking(boolean enabled) {
        this.blocking = enabled;
    }

    private String getPreviousMethodName() {
        StackTraceElement[] stackTrace = new Exception().fillInStackTrace().getStackTrace();
        StackTraceElement element = stackTrace[2];
        return element.getMethodName();
    }

    private void neverReturn() {
        if (blocking && !skipMethods.contains(getPreviousMethodName())) {
            try {
                Thread.currentThread().join();
            } catch (Exception e) {
                throw new CacheException(e);
            }
        }
    }

    public boolean bufferFull() {
        neverReturn();
        return false;
    }

    public boolean containsKey(Object key) {
        neverReturn();
        return false;
    }

    public boolean containsKeyInMemory(Object key) {
        neverReturn();
        return false;
    }

    public boolean containsKeyOnDisk(Object key) {
        neverReturn();
        return false;
    }

    public void dispose() {
        neverReturn();
    }

    public void expireElements() {
        neverReturn();
    }

    public void flush() throws IOException {
        neverReturn();
    }

    public Element get(Object key) {
        neverReturn();
        return null;
    }

    public Policy getInMemoryEvictionPolicy() {
        neverReturn();
        return null;
    }

    public int getInMemorySize() {
        neverReturn();
        return 0;
    }

    public long getInMemorySizeInBytes() {
        neverReturn();
        return 0;
    }

    public Object getInternalContext() {
        neverReturn();
        return null;
    }

    public List getKeys() {
        neverReturn();
        return null;
    }

    public int getOnDiskSize() {
        neverReturn();
        return 0;
    }

    public long getOnDiskSizeInBytes() {
        neverReturn();
        return 0;
    }

    public Element getQuiet(Object key) {
        neverReturn();
        return null;
    }

    public int getSize() {
        neverReturn();
        return 0;
    }

    public Status getStatus() {
        neverReturn();
        return null;
    }

    public int getTerracottaClusteredSize() {
        neverReturn();
        return 0;
    }

    public boolean isCacheCoherent() {
        neverReturn();
        return false;
    }

    public boolean isClusterCoherent() {
        neverReturn();
        return false;
    }

    public boolean isNodeCoherent() {
        neverReturn();
        return false;
    }

    public boolean put(Element element) throws CacheException {
        neverReturn();
        return false;
    }

    public Element putIfAbsent(Element element) throws NullPointerException {
        neverReturn();
        return null;
    }

    public boolean putWithWriter(Element element, CacheWriterManager writerManager) throws CacheException {
        neverReturn();
        return false;
    }

    public Element remove(Object key) {
        neverReturn();
        return null;
    }

    public void removeAll() throws CacheException {
        neverReturn();
    }

    public Element removeElement(Element element, ElementValueComparator comparator) throws NullPointerException {
        neverReturn();
        return null;
    }

    public Element removeWithWriter(Object key, CacheWriterManager writerManager) throws CacheException {
        neverReturn();
        return null;
    }

    public boolean replace(Element old, Element element, ElementValueComparator comparator) throws NullPointerException,
            IllegalArgumentException {
        neverReturn();
        return false;
    }

    public Element replace(Element element) throws NullPointerException {
        neverReturn();
        return null;
    }

    public void setInMemoryEvictionPolicy(Policy policy) {
        neverReturn();
    }

    public void setNodeCoherent(boolean coherent) throws UnsupportedOperationException {
        neverReturn();
    }

    public void waitUntilClusterCoherent() throws UnsupportedOperationException {
        neverReturn();
    }

    public void addStoreListener(StoreListener listener) {
        neverReturn();
    }

    public void removeStoreListener(StoreListener listener) {
        neverReturn();
    }

    public int getOffHeapSize() {
        neverReturn();
        return 0;
    }

    public long getOffHeapSizeInBytes() {
        neverReturn();
        return 0;
    }

    public boolean containsKeyOffHeap(Object key) {
        neverReturn();
        return false;
    }

    public Object getMBean() {
        return null;
    }

    public void setAttributeExtractors(Map<String, AttributeExtractor> extractors) {
        // no-op
    }

    public Results executeQuery(StoreQuery query) {
        throw new UnsupportedOperationException();
    }

    public <T> Attribute<T> getSearchAttribute(String attributeName) {
        throw new UnsupportedOperationException();
    }

}
