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
package net.sf.ehcache.constructs.refreshahead;

import java.io.Serializable;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheException;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import net.sf.ehcache.Status;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.PersistenceConfiguration;
import net.sf.ehcache.config.PersistenceConfiguration.Strategy;
import net.sf.ehcache.config.TerracottaConfiguration;
import net.sf.ehcache.config.TerracottaConfiguration.Consistency;
import net.sf.ehcache.constructs.EhcacheDecoratorAdapter;
import net.sf.ehcache.constructs.refreshahead.ThreadedWorkQueue.BatchWorker;
import net.sf.ehcache.extension.CacheExtension;
import net.sf.ehcache.loader.CacheLoader;
import net.sf.ehcache.store.MemoryStoreEvictionPolicy;
import net.sf.ehcache.util.VmUtils;

/**
 * A cache decorator which implements read ahead refreshing. Read ahead occurs
 * when a cache entry is accessed prior to its expiration, and triggers a reload of
 * the value in the background.
 * <p>
 * A significant attempt is made to ensure only one node of the cache works on a specific key at a time. There is no guarantee that every
 * triggered refresh ahead case will be processed. As the maximum number of backlog entries is reached, refresh ahead requests will be
 * dropped silently.
 * <p>
 * Provided the Number of threads per node * Number of nodes < the maximum backlog, only one node in the cluster will refresha given key at
 * a time.
 *
 * @author cschanck
 *
 */
public class RefreshAheadCache extends EhcacheDecoratorAdapter {

    private static final Object REFRESH_VALUE = Boolean.TRUE;
    private final AtomicInteger refreshSuccessCount = new AtomicInteger();
    private final RefreshAheadCacheConfiguration refreshAheadConfig;
    private CacheConfiguration supportConfig;

    private volatile Ehcache supportCache;
    private volatile ThreadedWorkQueue<Object> refreshWorkQueue;

    /**
     * Create a Refresh Ahead Cache Adaptor with the specified configuration. An auxiliary EhCache
     * Cache will be created for the purposes of synchronization, so only one node
     * in a clustered environment will refresh a key at a given time.
     *
     * @param adaptedCache
     * @param refreshConfig
     */
    public RefreshAheadCache(Ehcache adaptedCache, RefreshAheadCacheConfiguration refreshConfig) {

        super(adaptedCache);
        this.refreshAheadConfig = refreshConfig;

        // XA transactions cannot actually refresh sensibly. At least not
        // reasonably. GAE doesn't support threads. Other conditions around?
        boolean refreshAllowed = !underlyingCache.getCacheConfiguration().isXaStrictTransactional();
        refreshAllowed = refreshAllowed && !underlyingCache.getCacheConfiguration().isXaTransactional();
        refreshAllowed = refreshAllowed && !underlyingCache.getCacheConfiguration().isLocalTransactional();
        refreshAllowed = refreshAllowed && !VmUtils.isInGoogleAppEngine();

        if (refreshAllowed) {
            initSupportCache();
            initWorkQueue();
        } else {
            throw new UnsupportedOperationException("refresh-ahead not supported under transactions or with GAE");
        }
    }

    private void initSupportCache() {
        // create the support cache
        // make this cache clustered in the same way as the underlying cache,
        this.supportConfig = new CacheConfiguration();
        supportConfig.name(underlyingCache.getName() + "_" + getClass().getName() + "_refreshAheadSupport");
        supportConfig = supportConfig.persistence(new PersistenceConfiguration().strategy(Strategy.NONE));
        supportConfig = supportConfig.maxEntriesLocalHeap(refreshAheadConfig.getMaximumRefreshBacklogItems());
        supportConfig = supportConfig.memoryStoreEvictionPolicy(MemoryStoreEvictionPolicy.FIFO);
        supportConfig = supportConfig.eternal(false);
        // we might want to actually enable these to some reasonable amount
        supportConfig = supportConfig.timeToIdleSeconds(Long.MAX_VALUE);
        supportConfig = supportConfig.timeToLiveSeconds(Long.MAX_VALUE);
        // grab TC stuff
        if (underlyingCache.getCacheConfiguration().isTerracottaClustered()) {
            TerracottaConfiguration underlyingTerracottaConfig = underlyingCache.getCacheConfiguration().getTerracottaConfiguration()
                    .clone();
            // perhaps? Still experimenting with this
            // perhaps we really want to allow the user to specifically override matching
            // the base cache consistency with a param
            // e.g., terracottaConsistency=...
            // what about RMI Distributed EHCache?
            underlyingTerracottaConfig.consistency(Consistency.STRONG);

            // is cacheXA false always going to work?
            underlyingTerracottaConfig.cacheXA(false);
            supportConfig.addTerracotta(underlyingTerracottaConfig);
        }

        // here we try to create the support cache. Realize someone other
        // node may be trying to do it too, so try carefully. Note that this depends on
        //
        this.supportCache = new Cache(supportConfig);

        Ehcache prior = underlyingCache.getCacheManager().addCacheIfAbsent(supportCache);
        if (prior != supportCache) {
            throw new IllegalStateException("Unable to add refresh ahead support cache due to name collision: "
                    + refreshAheadConfig.getName());
        }

        // catch the dispose. not sure this is the best way to do it at all.
        // we could register a listener alternatively
        underlyingCache.registerCacheExtension(new CacheExtension() {

            @Override
            public void init() {
            }

            @Override
            public Status getStatus() {
                return underlyingCache.getStatus();
            }

            @Override
            public void dispose() throws CacheException {
                RefreshAheadCache.this.localDispose();
            }

            @Override
            public CacheExtension clone(Ehcache cache) throws CloneNotSupportedException {
                throw new CloneNotSupportedException();
            }
        });
    }

    private void initWorkQueue() {
        BatchWorker<Object> batchWorker = new BatchWorker<Object>() {

            @Override
            public void process(Collection<? extends Object> collection) {

                // only fetch this once for each process() call
                long accessTime = System.currentTimeMillis();

                HashSet<Object> keysToProcess = new HashSet<Object>();
                for (Object key : collection) {

                    // check if it was loaded by someone else in the meantime -- does it still qualify for refresh ahead?
                    Element quickTest = underlyingCache.getQuiet(key);
                    if (quickTest == null || checkForRefresh(quickTest, accessTime, refreshAheadConfig.getTimeToRefreshMillis())) {
                        final Element ersatz = new Element(key, REFRESH_VALUE);

                        if (supportCache.putIfAbsent(ersatz) == null) {
                            // work, work, work
                            keysToProcess.add(key);
                        }
                    }
                }
                try {
                    // iterate through the loaders
                    for (CacheLoader loader : underlyingCache.getRegisteredCacheLoaders()) {
                        // if we are out of keys, punt
                        if (keysToProcess.isEmpty()) {
                            break;
                        }

                        // try and load them all
                        Map<? extends Object, ? extends Object> values = loader.loadAll(keysToProcess);
                        // subtract the ones that were loaded
                        keysToProcess.removeAll(values.keySet());
                        try {
                            for (Map.Entry<? extends Object, ? extends Object> entry : values.entrySet()) {
                                Element newElement = new Element(entry.getKey(), entry.getValue());
                                underlyingCache.put(newElement);
                                refreshSuccessCount.incrementAndGet();
                            }
                        } finally {
                            // subtract from the support cache
                            supportCache.removeAll(values.keySet());
                        }
                    }
                    // assume we got here ok, now evict any that don't evict
                    if (refreshAheadConfig.isLoadMissEvicts() && !keysToProcess.isEmpty()) {
                        underlyingCache.removeAll(keysToProcess);
                    }
                } finally {
                    // this is utterly paranoid. but still.
                    supportCache.removeAll(keysToProcess);
                }

            }
        };

        this.refreshWorkQueue = new ThreadedWorkQueue<Object>(batchWorker, refreshAheadConfig.getNumberOfThreads(), new ThreadFactory() {

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setDaemon(true);
                return t;
            }
        }, refreshAheadConfig.getMaximumRefreshBacklogItems(), refreshAheadConfig.getBatchSize());
    }

    private boolean checkForRefresh(Element elem, long accessTime, long timeToRefreshMillis) {
        if (elem == null) {
            return false;
        }

        long minAccessForRefreshTime = elem.getCreationTime() + timeToRefreshMillis;

        return (accessTime >= minAccessForRefreshTime);
    }

    private void possiblyTriggerRefresh(Element elem, long timeToRefreshMillis) {
        if (checkForRefresh(elem, System.currentTimeMillis(), timeToRefreshMillis)) {
            // now add the key to the queue. smallest overhead we could get.
            refreshWorkQueue.offer(elem.getObjectKey());
        }
    }

    @Override
    public Element get(Object key) throws IllegalStateException, CacheException {
        Element elem = super.get(key);
        possiblyTriggerRefresh(elem, refreshAheadConfig.getTimeToRefreshMillis());
        return elem;
    }

    @Override
    public Element get(Serializable key) throws IllegalStateException, CacheException {
        Element elem = super.get(key);
        possiblyTriggerRefresh(elem, refreshAheadConfig.getTimeToRefreshMillis());
        return elem;
    }

    /**
     * number of refreshes processed locally.
     */
    public AtomicInteger getRefreshSuccessCount() {
        return refreshSuccessCount;
    }

    private void localDispose() throws IllegalStateException {
        synchronized (this) {
            if (refreshWorkQueue != null) {
                refreshWorkQueue.shutdown();
                refreshWorkQueue = null;
            }
            if (supportCache != null) {
                try {
                    supportCache.getCacheManager().removeCache(getName());
                } catch (Throwable t) {
                }
                supportCache = null;
            }
        }
    }

    @Override
    public String getName() {
        if (refreshAheadConfig.getName() != null) {
            return refreshAheadConfig.getName();
        }
        return super.getName();
    }

}
