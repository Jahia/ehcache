/**
 *  Copyright Terracotta, Inc.
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
package net.sf.ehcache.store.cachingtier;

import net.sf.ehcache.Element;
import net.sf.ehcache.pool.PoolAccessor;
import net.sf.ehcache.store.Policy;
import net.sf.ehcache.store.cachingtier.HeapCacheBackEnd;
import net.sf.ehcache.util.concurrent.ConcurrentHashMap;
import net.sf.ehcache.util.ratestatistics.AtomicRateStatistic;
import net.sf.ehcache.util.ratestatistics.RateStatistic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A backend to a OnHeapCachingTier that will be cap'ed using a pool
 *
 * @param <K> the key type
 * @param <V> the value type
 *
 * @author Alex Snaps
 */
public class PooledBasedBackEnd<K, V> extends ConcurrentHashMap<K, V> implements HeapCacheBackEnd<K, V> {

    private static final int MAX_EVICTIONS = 5;

    private final RateStatistic hitRate = new AtomicRateStatistic(1000, TimeUnit.MILLISECONDS);
    private final RateStatistic missRate = new AtomicRateStatistic(1000, TimeUnit.MILLISECONDS);


    private volatile Policy policy;
    private volatile EvictionCallback<K, V> evictionCallback;
    private AtomicReference<PoolAccessor> poolAccessor = new AtomicReference<PoolAccessor>();


    /**
     * Constructs a Pooled backend
     * @param memoryEvictionPolicy the policy it'll use to decide what to evict
     */
    public PooledBasedBackEnd(final Policy memoryEvictionPolicy) {
        setPolicy(memoryEvictionPolicy);
    }

    @Override
    public V putIfAbsent(final K key, final V value) {
        long delta = poolAccessor.get().add(key, value, FAKE_TREE_NODE, false);
        if (delta > -1) {
            return (V)super.internalPutIfAbsent(key, value, delta > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int)delta);
        } else {
            evictionCallback.evicted(key, value);
            return null;
        }
    }

    @Override
    public V get(final Object key) {
        final V value = super.get(key);
        if (value != null) {
            hitRate.event();
        } else {
            missRate.event();
        }
        return value;
    }

    @Override
    public void putAll(final Map<? extends K, ? extends V> m) {
        throw new UnsupportedOperationException();
    }

    @Override
    public V put(final K key, final V value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public V computeIfAbsent(final K key, final Fun<? super K, ? extends V> mappingFunction) {
        throw new UnsupportedOperationException();
    }

    @Override
    public V computeIfPresent(final K key, final BiFun<? super K, ? super V, ? extends V> remappingFunction) {
        throw new UnsupportedOperationException();
    }

    @Override
    public V compute(final K key, final BiFun<? super K, ? super V, ? extends V> remappingFunction) {
        throw new UnsupportedOperationException();
    }

    @Override
    public V remove(final Object key) {
        return super.remove(key);
    }

    @Override
    public boolean remove(final Object key, final Object value) {
        return super.remove(key, value);
    }

    @Override
    public boolean replace(final K key, final V oldValue, final V newValue) {
        return super.replace(key, oldValue, newValue);
    }

    @Override
    public V replace(final K key, final V value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        super.clear();
    }

    @Override
    public void forEach(final BiAction<K, V> action) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <U> void forEach(final BiFun<? super K, ? super V, ? extends U> transformer, final Action<U> action) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <U> U search(final BiFun<? super K, ? super V, ? extends U> searchFunction) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <U> U reduce(final BiFun<? super K, ? super V, ? extends U> transformer, final BiFun<? super U, ? super U, ? extends U> reducer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public double reduceToDouble(final ObjectByObjectToDouble<? super K, ? super V> transformer,
                                 final double basis, final DoubleByDoubleToDouble reducer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public long reduceToLong(final ObjectByObjectToLong<? super K, ? super V> transformer, final long basis, final LongByLongToLong reducer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int reduceToInt(final ObjectByObjectToInt<? super K, ? super V> transformer, final int basis, final IntByIntToInt reducer) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void forEachKey(final Action<K> action) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <U> void forEachKey(final Fun<? super K, ? extends U> transformer, final Action<U> action) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void forEachEntry(final Action<Entry<K, V>> action) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <U> void forEachEntry(final Fun<Entry<K, V>, ? extends U> transformer, final Action<U> action) {
        throw new UnsupportedOperationException();
    }

    /**
     * hit rate for this backend
     * @return the hit rate
     */
    public float getHitRate() {
        return hitRate.getRate();
    }

    /**
     * miss rate for this backend
     * @return the miss rate
     */
    public float getMissRate() {
        return missRate.getRate();
    }

    /**
     * tries to evict as many entries as specified
     * @param evictions amount of entries to be evicted
     * @return return true if exactly the right amount of evictions could happen, false otherwise
     */
    public boolean evict(int evictions) {
        while (evictions > 0) {
            final Element evictionCandidate = findEvictionCandidate();
            if (evictionCandidate != null && remove(evictionCandidate.getObjectKey(), evictionCandidate)) {
                evictionCallback.evicted((K)evictionCandidate.getObjectKey(), (V)evictionCandidate);
            } else {
                return false;
            }
        }
        return true;
    }

    private Element findEvictionCandidate() {
        List<V> values = getRandomValues(MAX_EVICTIONS);
        // this can return null. Let the cache get bigger by one.
        List<Element> elements = new ArrayList<Element>(values.size() * 2);
        for (V v : values) {
            if (v instanceof Element) {
                elements.add((Element)v);
            }
        }
        return policy.selectedBasedOnPolicy(elements.toArray(new Element[elements.size()]), null);
    }

    /**
     * Dynamic property to switch the policy out
     * @param policy the new eviction Policy to use
     */
    public void setPolicy(final Policy policy) {
        if (policy == null) {
            throw new NullPointerException("We need a Policy passed in here, null won't cut it!");
        }
        this.policy = policy;
    }

    @Override
    public void registerEvictionCallback(final EvictionCallback<K, V> callback) {
        this.evictionCallback = callback;
    }

    /**
     * Registers the accessor with the backend. This can only happen once!
     * @param poolAccessor the pool accessor to use
     */
    public void registerAccessor(final PoolAccessor poolAccessor) {
        if (poolAccessor == null) {
            throw new NullPointerException("No null poolAccessor allowed here!");
        }
        if (!this.poolAccessor.compareAndSet(null, poolAccessor)) {
            throw new IllegalStateException("Can't set the poolAccessor multiple times!");
        }
        super.setPoolAccessor(poolAccessor);
    }

    /**
     * Returns the size in bytes
     * @return the amount of bytes for this backend
     */
    @Deprecated
    public long getSizeInBytes() {
        return poolAccessor.get().getSize();
    }

    /**
     * A pool participant to use with this Backend
     */
    public static class PoolParticipant implements net.sf.ehcache.pool.PoolParticipant {

        private final PooledBasedBackEnd<Object, Object> pooledBasedBackEnd;

        /**
         * Creates a pool participant
         * @param pooledBasedBackEnd the backend this participant represents
         */
        public PoolParticipant(final PooledBasedBackEnd<Object, Object> pooledBasedBackEnd) {
            this.pooledBasedBackEnd = pooledBasedBackEnd;
        }

        @Override
        public boolean evict(final int count, final long size) {
            return pooledBasedBackEnd.evict(count);
        }

        @Override
        public float getApproximateHitRate() {
            return pooledBasedBackEnd.getHitRate();
        }

        @Override
        public float getApproximateMissRate() {
            return pooledBasedBackEnd.getMissRate();
        }

        @Override
        public long getApproximateCountSize() {
            return pooledBasedBackEnd.mappingCount();
        }
    }
}
