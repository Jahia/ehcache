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

package net.sf.ehcache.constructs.nonstop.store;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import net.sf.ehcache.CacheException;
import net.sf.ehcache.Element;
import net.sf.ehcache.Status;
import net.sf.ehcache.search.Results;
import net.sf.ehcache.search.attribute.AttributeExtractor;
import net.sf.ehcache.store.ElementValueComparator;
import net.sf.ehcache.store.Policy;
import net.sf.ehcache.store.Store;
import net.sf.ehcache.store.StoreListener;
import net.sf.ehcache.store.StoreQuery;
import net.sf.ehcache.writer.CacheWriterManager;

/**
 * Implementation of {@link Store} which returns null for all get
 * operations and does nothing for puts and removes.
 *
 * @author Abhishek Sanoujam
 *
 */
public final class NoOpOnTimeoutStore implements Store {

    /**
     * the singleton instance
     */
    private static final NoOpOnTimeoutStore INSTANCE = new NoOpOnTimeoutStore();

    /**
     * private constructor
     */
    private NoOpOnTimeoutStore() {
        //
    }

    /**
     * Returns the singleton instance
     *
     * @return the singleton instance
     */
    public static NoOpOnTimeoutStore getInstance() {
        return INSTANCE;
    }

    /**
     * {@inheritDoc}
     */
    public void addStoreListener(StoreListener listener) {
    }

    /**
     * {@inheritDoc}
     */
    public boolean bufferFull() {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public boolean containsKey(Object key) {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public boolean containsKeyInMemory(Object key) {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public boolean containsKeyOffHeap(Object key) {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public boolean containsKeyOnDisk(Object key) {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public void dispose() {
    }

    /**
     * {@inheritDoc}
     */
    public Results executeQuery(StoreQuery query) {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public void expireElements() {
    }

    /**
     * {@inheritDoc}
     */
    public void flush() throws IOException {
    }

    /**
     * {@inheritDoc}
     */
    public Element get(Object key) {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public Policy getInMemoryEvictionPolicy() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public int getInMemorySize() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    public long getInMemorySizeInBytes() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    public Object getInternalContext() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public List getKeys() {
        return Collections.EMPTY_LIST;
    }

    /**
     * {@inheritDoc}
     */
    public Object getMBean() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public int getOffHeapSize() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    public long getOffHeapSizeInBytes() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    public int getOnDiskSize() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    public long getOnDiskSizeInBytes() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    public Element getQuiet(Object key) {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public int getSize() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    public Status getStatus() {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public int getTerracottaClusteredSize() {
        return 0;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isCacheCoherent() {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isClusterCoherent() {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isNodeCoherent() {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public boolean put(Element element) throws CacheException {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public Element putIfAbsent(Element element) {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public boolean putWithWriter(Element element, CacheWriterManager writerManager) throws CacheException {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public Element remove(Object key) {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public void removeAll() throws CacheException {
    }

    /**
     * {@inheritDoc}
     */
    public Element removeElement(Element element, ElementValueComparator comparator) {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public void removeStoreListener(StoreListener listener) {
    }

    /**
     * {@inheritDoc}
     */
    public Element removeWithWriter(Object key, CacheWriterManager writerManager) throws CacheException {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public boolean replace(Element old, Element element, ElementValueComparator comparator) {
        return false;
    }

    /**
     * {@inheritDoc}
     */
    public Element replace(Element element) {
        return null;
    }

    /**
     * {@inheritDoc}
     */
    public void setAttributeExtractors(Map<String, AttributeExtractor> extractors) {
    }

    /**
     * {@inheritDoc}
     */
    public void setInMemoryEvictionPolicy(Policy policy) {
    }

    /**
     * {@inheritDoc}
     */
    public void setNodeCoherent(boolean coherent) throws UnsupportedOperationException {
    }

    /**
     * {@inheritDoc}
     */
    public void waitUntilClusterCoherent() throws UnsupportedOperationException {
    }

}