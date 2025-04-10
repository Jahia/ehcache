/**
 *  Copyright 2003-2009 Terracotta, Inc.
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

package net.sf.ehcache;

import net.sf.ehcache.bootstrap.BootstrapCacheLoader;
import net.sf.ehcache.bootstrap.BootstrapCacheLoaderFactory;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.CacheWriterConfiguration;
import net.sf.ehcache.config.DiskStoreConfiguration;
import net.sf.ehcache.config.TerracottaConfiguration;
import net.sf.ehcache.event.CacheEventListener;
import net.sf.ehcache.event.CacheEventListenerFactory;
import net.sf.ehcache.event.RegisteredEventListeners;
import net.sf.ehcache.exceptionhandler.CacheExceptionHandler;
import net.sf.ehcache.extension.CacheExtension;
import net.sf.ehcache.extension.CacheExtensionFactory;
import net.sf.ehcache.loader.CacheLoader;
import net.sf.ehcache.loader.CacheLoaderFactory;
import net.sf.ehcache.statistics.CacheUsageListener;
import net.sf.ehcache.statistics.LiveCacheStatistics;
import net.sf.ehcache.statistics.LiveCacheStatisticsWrapper;
import net.sf.ehcache.statistics.sampled.SampledCacheStatistics;
import net.sf.ehcache.statistics.sampled.SampledCacheStatisticsWrapper;
import net.sf.ehcache.store.DiskStore;
import net.sf.ehcache.store.LruMemoryStore;
import net.sf.ehcache.store.MemoryStore;
import net.sf.ehcache.store.MemoryStoreEvictionPolicy;
import net.sf.ehcache.store.Policy;
import net.sf.ehcache.store.Store;
import net.sf.ehcache.store.XATransactionalStore;
import net.sf.ehcache.transaction.manager.TransactionManagerLookup;
import net.sf.ehcache.transaction.xa.EhcacheXAResourceImpl;
import net.sf.ehcache.transaction.xa.EhcacheXAStore;
import net.sf.ehcache.util.ClassLoaderUtil;
import net.sf.ehcache.util.NamedThreadFactory;
import net.sf.ehcache.util.PropertyUtil;
import net.sf.ehcache.util.TimeUtil;
import net.sf.ehcache.writer.CacheWriter;
import net.sf.ehcache.writer.CacheWriterFactory;
import net.sf.ehcache.writer.CacheWriterManager;
import net.sf.ehcache.writer.CacheWriterManagerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.transaction.TransactionManager;
import java.io.IOException;
import java.io.Serializable;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Cache is the central class in ehcache. Caches have {@link Element}s and are managed
 * by the {@link CacheManager}. The Cache performs logical actions. It delegates physical
 * implementations to its {@link net.sf.ehcache.store.Store}s.
 * <p/>
 * A reference to a Cache can be obtained through the {@link CacheManager}. A Cache thus obtained
 * is guaranteed to have status {@link Status#STATUS_ALIVE}. This status is checked for any method which
 * throws {@link IllegalStateException} and the same thrown if it is not alive. This would normally
 * happen if a call is made after {@link CacheManager#shutdown} is invoked.
 * <p/>
 * Cache is threadsafe.
 * <p/>
 * Statistics on cache usage are collected and made available through the {@link #getStatistics()} methods.
 * <p/>
 * Various decorators are available for Cache, such as BlockingCache, SelfPopulatingCache and the dynamic proxy
 * ExceptionHandlingDynamicCacheProxy. See each class for details.
 *
 * @author Greg Luck
 * @author Geert Bevin
 * @version $Id$
 */
public class Cache implements Ehcache {

    /**
     * A reserved word for cache names. It denotes a default configuration
     * which is applied to caches created without configuration.
     */
    public static final String DEFAULT_CACHE_NAME = "default";

    /**
     * System Property based method of disabling ehcache. If disabled no elements will be added to a cache.
     * <p/>
     * Set the property "net.sf.ehcache.disabled=true" to disable ehcache.
     * <p/>
     * This can easily be done using <code>java -Dnet.sf.ehcache.disabled=true</code> in the command line.
     */
    public static final String NET_SF_EHCACHE_DISABLED = "net.sf.ehcache.disabled";

    /**
     * System Property based method of selecting the LruMemoryStore in use up to ehcache 1.5. This is provided
     * for ease of migration.
     * <p/>
     * Set the property "net.sf.ehcache.use.classic.lru=true" to use the older LruMemoryStore implementation
     * when LRU is selected as the eviction policy.
     * <p/>
     * This can easily be done using <code>java -Dnet.sf.ehcache.use.classic.lru=true</code> in the command line.
     */
    public static final String NET_SF_EHCACHE_USE_CLASSIC_LRU = "net.sf.ehcache.use.classic.lru";

    /**
     * The default interval between runs of the expiry thread.
     * @see CacheConfiguration#DEFAULT_EXPIRY_THREAD_INTERVAL_SECONDS CacheConfiguration#DEFAULT_EXPIRY_THREAD_INTERVAL_SECONDS for a preferred way of setting
     */
    public static final long DEFAULT_EXPIRY_THREAD_INTERVAL_SECONDS = CacheConfiguration.DEFAULT_EXPIRY_THREAD_INTERVAL_SECONDS;

    private static final Logger LOG = LoggerFactory.getLogger(Cache.class.getName());

    private static InetAddress localhost;

    /**
     * The amount of time to wait if a store gets backed up
     */
    private static final int BACK_OFF_TIME_MILLIS = 50;

    private static final int EXECUTOR_KEEP_ALIVE_TIME = 60000;
    private static final int EXECUTOR_MAXIMUM_POOL_SIZE = Math.min(10, Runtime.getRuntime().availableProcessors());
    private static final int EXECUTOR_CORE_POOL_SIZE = 1;

    static {
        try {
            localhost = InetAddress.getLocalHost();
        } catch (UnknownHostException e) {
            LOG.error("Unable to set localhost. This prevents creation of a GUID. Cause was: " + e.getMessage(), e);
        } catch (java.lang.NoClassDefFoundError e) {
            LOG.debug("InetAddress is being blocked by your runtime environment. e.g. Google App Engine." +
                    " Ehcache will work as a local cache.");
        }
    }

    private volatile boolean disabled = Boolean.getBoolean(NET_SF_EHCACHE_DISABLED);

    private final boolean useClassicLru = Boolean.getBoolean(NET_SF_EHCACHE_USE_CLASSIC_LRU);

    private volatile Store diskStore;

    private volatile String diskStorePath;

    private volatile Status status;

    private volatile CacheConfiguration configuration;

    /**
     * The {@link MemoryStore} of this {@link Cache}. All caches have a memory store.
     */
    private volatile Store memoryStore;

    private volatile RegisteredEventListeners registeredEventListeners;

    private volatile List<CacheExtension> registeredCacheExtensions;

    private volatile String guid;

    private volatile CacheManager cacheManager;

    private volatile BootstrapCacheLoader bootstrapCacheLoader;

    private volatile CacheExceptionHandler cacheExceptionHandler;

    private volatile List<CacheLoader> registeredCacheLoaders;

    private volatile CacheWriterManager cacheWriterManager;

    private final AtomicBoolean cacheWriterManagerInitFlag = new AtomicBoolean(false);

    private final ReentrantLock cacheWriterManagerInitLock = new ReentrantLock();

    private volatile CacheWriter registeredCacheWriter;

    /**
     * A ThreadPoolExecutor which uses a thread pool to schedule loads in the order in which they are requested.
     * <p/>
     * Each cache has its own one of these, if required. Because the Core Thread Pool is zero, no threads
     * are used until actually needed. Threads are added to the pool up to a maximum of 10. The keep alive
     * time is 60 seconds, after which, if they are not required they will be stopped and collected.
     * <p/>
     * The executorService is only used for cache loading, and is created lazily on demand to avoid unnecessary resource
     * usage.
     * <p/>
     * Use {@link #getExecutorService()} to ensure that it is initialised.
     */
    private volatile ExecutorService executorService;

    private volatile LiveCacheStatisticsWrapper liveCacheStatisticsData;

    private volatile SampledCacheStatisticsWrapper sampledCacheStatistics;

    private volatile TransactionManagerLookup transactionManagerLookup;

    private volatile boolean allowDisable = true;

    /**
     * 2.0 and higher Constructor
     * <p/>
     * The {@link net.sf.ehcache.config.ConfigurationFactory} and clients can create these.
     * <p/>
     * A client can specify their own settings here and pass the {@link Cache} object
     * into {@link CacheManager#addCache} to specify parameters other than the defaults.
     * <p/>
     * Only the CacheManager can initialise them.
     *
     * @param cacheConfiguration the configuration that should be used to create the cache with
     */
    public Cache(CacheConfiguration cacheConfiguration) {
        this(cacheConfiguration, null, null);
    }

    /**
     * 2.0 and higher Constructor
     * <p/>
     * The {@link net.sf.ehcache.config.ConfigurationFactory} and clients can create these.
     * <p/>
     * A client can specify their own settings here and pass the {@link Cache} object
     * into {@link CacheManager#addCache} to specify parameters other than the defaults.
     * <p/>
     * Only the CacheManager can initialise them.
     *
     * @param cacheConfiguration the configuration that should be used to create the cache with
     * @param registeredEventListeners  a notification service. Optionally null, in which case a new one with no registered listeners will be created.
     * @param bootstrapCacheLoader      the BootstrapCacheLoader to use to populate the cache when it is first initialised. Null if none is required.
     */
    public Cache(CacheConfiguration cacheConfiguration,
                 RegisteredEventListeners registeredEventListeners,
                 BootstrapCacheLoader bootstrapCacheLoader) {
        changeStatus(Status.STATUS_UNINITIALISED);


        this.configuration = cacheConfiguration.clone();

        guid = createGuid();

        this.diskStorePath = cacheConfiguration.getDiskStorePath();

        if (registeredEventListeners == null) {
            this.registeredEventListeners = new RegisteredEventListeners(this);
        } else {
            this.registeredEventListeners = registeredEventListeners;
        }

        registeredCacheExtensions = new CopyOnWriteArrayList<CacheExtension>();
        registeredCacheLoaders = new CopyOnWriteArrayList<CacheLoader>();

        //initialize statistics
        liveCacheStatisticsData = new LiveCacheStatisticsWrapper(this);
        sampledCacheStatistics = new SampledCacheStatisticsWrapper();

        RegisteredEventListeners listeners = getCacheEventNotificationService();
        registerCacheListeners(configuration, listeners);
        registerCacheExtensions(configuration, this);

        if (null == bootstrapCacheLoader) {
            this.bootstrapCacheLoader = createBootstrapCacheLoader(configuration.getBootstrapCacheLoaderFactoryConfiguration());
        } else {
            this.bootstrapCacheLoader = bootstrapCacheLoader;
        }
        registerCacheLoaders(configuration, this);
        registerCacheWriter(configuration, this);
    }



    /**
     * 1.0 Constructor.
     * <p/>
     * The {@link net.sf.ehcache.config.ConfigurationFactory} and clients can create these.
     * <p/>
     * A client can specify their own settings here and pass the {@link Cache} object
     * into {@link CacheManager#addCache} to specify parameters other than the defaults.
     * <p/>
     * Only the CacheManager can initialise them.
     * <p/>
     * This constructor creates disk stores, if specified, that do not persist between restarts.
     * <p/>
     * The default expiry thread interval of 120 seconds is used. This is the interval between runs
     * of the expiry thread, where it checks the disk store for expired elements. It is not the
     * the timeToLiveSeconds.
     *
     * @param name                the name of the cache. Note that "default" is a reserved name for the defaultCache.
     * @param maxElementsInMemory the maximum number of elements in memory, before they are evicted (0 == no limit)
     * @param overflowToDisk      whether to use the disk store
     * @param eternal             whether the elements in the cache are eternal, i.e. never expire
     * @param timeToLiveSeconds   the default amount of time to live for an element from its creation date
     * @param timeToIdleSeconds   the default amount of time to live for an element from its last accessed or modified date
     * @since 1.0
     * @see #Cache(CacheConfiguration, RegisteredEventListeners, BootstrapCacheLoader) Cache(CacheConfiguration, RegisteredEventListeners, BootstrapCacheLoader),
     * for full construction support of version 2.0 and higher features.
     */
    public Cache(String name, int maxElementsInMemory, boolean overflowToDisk,
                 boolean eternal, long timeToLiveSeconds, long timeToIdleSeconds) {

        this(new CacheConfiguration(name, maxElementsInMemory)
                    .overflowToDisk(overflowToDisk)
                    .eternal(eternal)
                    .timeToLiveSeconds(timeToLiveSeconds)
                    .timeToIdleSeconds(timeToIdleSeconds));
    }


    /**
     * 1.1 Constructor.
     * <p/>
     * The {@link net.sf.ehcache.config.ConfigurationFactory} and clients can create these.
     * <p/>
     * A client can specify their own settings here and pass the {@link Cache} object
     * into {@link CacheManager#addCache} to specify parameters other than the defaults.
     * <p/>
     * Only the CacheManager can initialise them.
     *
     * @param name                the name of the cache. Note that "default" is a reserved name for the defaultCache.
     * @param maxElementsInMemory the maximum number of elements in memory, before they are evicted (0 == no limit)
     * @param overflowToDisk      whether to use the disk store
     * @param eternal             whether the elements in the cache are eternal, i.e. never expire
     * @param timeToLiveSeconds   the default amount of time to live for an element from its creation date
     * @param timeToIdleSeconds   the default amount of time to live for an element from its last accessed or modified date
     * @param diskPersistent      whether to persist the cache to disk between JVM restarts
     * @param diskExpiryThreadIntervalSeconds
     *                            how often to run the disk store expiry thread. A large number of 120 seconds plus is recommended
     * @since 1.1
     * @see #Cache(CacheConfiguration, RegisteredEventListeners, BootstrapCacheLoader) Cache(CacheConfiguration, RegisteredEventListeners, BootstrapCacheLoader),
     * for full construction support of version 2.0 and higher features.
     */
    public Cache(String name,
                 int maxElementsInMemory,
                 boolean overflowToDisk,
                 boolean eternal,
                 long timeToLiveSeconds,
                 long timeToIdleSeconds,
                 boolean diskPersistent,
                 long diskExpiryThreadIntervalSeconds) {

        this(new CacheConfiguration(name, maxElementsInMemory)
                    .overflowToDisk(overflowToDisk)
                    .eternal(eternal)
                    .timeToLiveSeconds(timeToLiveSeconds)
                    .timeToIdleSeconds(timeToIdleSeconds)
                    .diskPersistent(diskPersistent)
                    .diskExpiryThreadIntervalSeconds(diskExpiryThreadIntervalSeconds));

        LOG.warn("An API change between ehcache-1.1 and ehcache-1.2 results in the persistence path being set to " +
                DiskStoreConfiguration.getDefaultPath() + " when the ehcache-1.1 constructor is used. " +
                "Please change to the 1.2 constructor.");
    }


    /**
     * 1.2 Constructor
     * <p/>
     * The {@link net.sf.ehcache.config.ConfigurationFactory} and clients can create these.
     * <p/>
     * A client can specify their own settings here and pass the {@link Cache} object
     * into {@link CacheManager#addCache} to specify parameters other than the defaults.
     * <p/>
     * Only the CacheManager can initialise them.
     *
     * @param name                      the name of the cache. Note that "default" is a reserved name for the defaultCache.
     * @param maxElementsInMemory       the maximum number of elements in memory, before they are evicted (0 == no limit)
     * @param memoryStoreEvictionPolicy one of LRU, LFU and FIFO. Optionally null, in which case it will be set to LRU.
     * @param overflowToDisk            whether to use the disk store
     * @param diskStorePath             this parameter is ignored. CacheManager sets it using setter injection.
     * @param eternal                   whether the elements in the cache are eternal, i.e. never expire
     * @param timeToLiveSeconds         the default amount of time to live for an element from its creation date
     * @param timeToIdleSeconds         the default amount of time to live for an element from its last accessed or modified date
     * @param diskPersistent            whether to persist the cache to disk between JVM restarts
     * @param diskExpiryThreadIntervalSeconds
     *                                  how often to run the disk store expiry thread. A large number of 120 seconds plus is recommended
     * @param registeredEventListeners  a notification service. Optionally null, in which case a new
     *                                  one with no registered listeners will be created.
     * @since 1.2
     * @see #Cache(CacheConfiguration, RegisteredEventListeners, BootstrapCacheLoader) Cache(CacheConfiguration, RegisteredEventListeners, BootstrapCacheLoader),
     * for full construction support of version 2.0 and higher features.
     */
    public Cache(String name,
                 int maxElementsInMemory,
                 MemoryStoreEvictionPolicy memoryStoreEvictionPolicy,
                 boolean overflowToDisk,
                 String diskStorePath,
                 boolean eternal,
                 long timeToLiveSeconds,
                 long timeToIdleSeconds,
                 boolean diskPersistent,
                 long diskExpiryThreadIntervalSeconds,
                 RegisteredEventListeners registeredEventListeners) {

        this(new CacheConfiguration(name, maxElementsInMemory)
                    .memoryStoreEvictionPolicy(memoryStoreEvictionPolicy)
                    .overflowToDisk(overflowToDisk)
                    .diskStorePath(diskStorePath)
                    .eternal(eternal)
                    .timeToLiveSeconds(timeToLiveSeconds)
                    .timeToIdleSeconds(timeToIdleSeconds)
                    .diskPersistent(diskPersistent)
                    .diskExpiryThreadIntervalSeconds(diskExpiryThreadIntervalSeconds),
                registeredEventListeners,
                null);

    }

    /**
     * 1.2.1 Constructor
     * <p/>
     * The {@link net.sf.ehcache.config.ConfigurationFactory} and clients can create these.
     * <p/>
     * A client can specify their own settings here and pass the {@link Cache} object
     * into {@link CacheManager#addCache} to specify parameters other than the defaults.
     * <p/>
     * Only the CacheManager can initialise them.
     *
     * @param name                      the name of the cache. Note that "default" is a reserved name for the defaultCache.
     * @param maxElementsInMemory       the maximum number of elements in memory, before they are evicted (0 == no limit)
     * @param memoryStoreEvictionPolicy one of LRU, LFU and FIFO. Optionally null, in which case it will be set to LRU.
     * @param overflowToDisk            whether to use the disk store
     * @param diskStorePath             this parameter is ignored. CacheManager sets it using setter injection.
     * @param eternal                   whether the elements in the cache are eternal, i.e. never expire
     * @param timeToLiveSeconds         the default amount of time to live for an element from its creation date
     * @param timeToIdleSeconds         the default amount of time to live for an element from its last accessed or modified date
     * @param diskPersistent            whether to persist the cache to disk between JVM restarts
     * @param diskExpiryThreadIntervalSeconds
     *                                  how often to run the disk store expiry thread. A large number of 120 seconds plus is recommended
     * @param registeredEventListeners  a notification service. Optionally null, in which case a new one with no registered listeners will be created.
     * @param bootstrapCacheLoader      the BootstrapCacheLoader to use to populate the cache when it is first initialised. Null if none is required.
     * @since 1.2.1
     * @see #Cache(CacheConfiguration, RegisteredEventListeners, BootstrapCacheLoader) Cache(CacheConfiguration, RegisteredEventListeners, BootstrapCacheLoader),
     * for full construction support of version 2.0 and higher features.
     */
    public Cache(String name,
                 int maxElementsInMemory,
                 MemoryStoreEvictionPolicy memoryStoreEvictionPolicy,
                 boolean overflowToDisk,
                 String diskStorePath,
                 boolean eternal,
                 long timeToLiveSeconds,
                 long timeToIdleSeconds,
                 boolean diskPersistent,
                 long diskExpiryThreadIntervalSeconds,
                 RegisteredEventListeners registeredEventListeners,
                 BootstrapCacheLoader bootstrapCacheLoader) {

        this(new CacheConfiguration(name, maxElementsInMemory)
                    .memoryStoreEvictionPolicy(memoryStoreEvictionPolicy)
                    .overflowToDisk(overflowToDisk)
                    .diskStorePath(diskStorePath)
                    .eternal(eternal)
                    .timeToLiveSeconds(timeToLiveSeconds)
                    .timeToIdleSeconds(timeToIdleSeconds)
                    .diskPersistent(diskPersistent)
                    .diskExpiryThreadIntervalSeconds(diskExpiryThreadIntervalSeconds),
                registeredEventListeners,
                bootstrapCacheLoader);
    }

    /**
     * 1.2.4 Constructor
     * <p/>
     * The {@link net.sf.ehcache.config.ConfigurationFactory} and clients can create these.
     * <p/>
     * A client can specify their own settings here and pass the {@link Cache} object
     * into {@link CacheManager#addCache} to specify parameters other than the defaults.
     * <p/>
     * Only the CacheManager can initialise them.
     *
     * @param name                      the name of the cache. Note that "default" is a reserved name for the defaultCache.
     * @param maxElementsInMemory       the maximum number of elements in memory, before they are evicted (0 == no limit)
     * @param memoryStoreEvictionPolicy one of LRU, LFU and FIFO. Optionally null, in which case it will be set to LRU.
     * @param overflowToDisk            whether to use the disk store
     * @param diskStorePath             this parameter is ignored. CacheManager sets it using setter injection.
     * @param eternal                   whether the elements in the cache are eternal, i.e. never expire
     * @param timeToLiveSeconds         the default amount of time to live for an element from its creation date
     * @param timeToIdleSeconds         the default amount of time to live for an element from its last accessed or modified date
     * @param diskPersistent            whether to persist the cache to disk between JVM restarts
     * @param diskExpiryThreadIntervalSeconds
     *                                  how often to run the disk store expiry thread. A large number of 120 seconds plus is recommended
     * @param registeredEventListeners  a notification service. Optionally null, in which case a new one with no registered listeners will be created.
     * @param bootstrapCacheLoader      the BootstrapCacheLoader to use to populate the cache when it is first initialised. Null if none is required.
     * @param maxElementsOnDisk         the maximum number of Elements to allow on the disk. 0 means unlimited.
     * @since 1.2.4
     * @see #Cache(CacheConfiguration, RegisteredEventListeners, BootstrapCacheLoader) Cache(CacheConfiguration, RegisteredEventListeners, BootstrapCacheLoader),
     * for full construction support of version 2.0 and higher features.
     */
    public Cache(String name,
                 int maxElementsInMemory,
                 MemoryStoreEvictionPolicy memoryStoreEvictionPolicy,
                 boolean overflowToDisk,
                 String diskStorePath,
                 boolean eternal,
                 long timeToLiveSeconds,
                 long timeToIdleSeconds,
                 boolean diskPersistent,
                 long diskExpiryThreadIntervalSeconds,
                 RegisteredEventListeners registeredEventListeners,
                 BootstrapCacheLoader bootstrapCacheLoader,
                 int maxElementsOnDisk) {

        this(new CacheConfiguration(name, maxElementsInMemory)
                    .memoryStoreEvictionPolicy(memoryStoreEvictionPolicy)
                    .overflowToDisk(overflowToDisk)
                    .diskStorePath(diskStorePath)
                    .eternal(eternal)
                    .timeToLiveSeconds(timeToLiveSeconds)
                    .timeToIdleSeconds(timeToIdleSeconds)
                    .diskPersistent(diskPersistent)
                    .diskExpiryThreadIntervalSeconds(diskExpiryThreadIntervalSeconds)
                    .maxElementsOnDisk(maxElementsOnDisk),
                registeredEventListeners,
                bootstrapCacheLoader);
    }

    /**
     * 1.3 Constructor
     * <p/>
     * The {@link net.sf.ehcache.config.ConfigurationFactory} and clients can create these.
     * <p/>
     * A client can specify their own settings here and pass the {@link Cache} object
     * into {@link CacheManager#addCache} to specify parameters other than the defaults.
     * <p/>
     * Only the CacheManager can initialise them.
     *
     * @param name                      the name of the cache. Note that "default" is a reserved name for the defaultCache.
     * @param maxElementsInMemory       the maximum number of elements in memory, before they are evicted (0 == no limit)
     * @param memoryStoreEvictionPolicy one of LRU, LFU and FIFO. Optionally null, in which case it will be set to LRU.
     * @param overflowToDisk            whether to use the disk store
     * @param diskStorePath             this parameter is ignored. CacheManager sets it using setter injection.
     * @param eternal                   whether the elements in the cache are eternal, i.e. never expire
     * @param timeToLiveSeconds         the default amount of time to live for an element from its creation date
     * @param timeToIdleSeconds         the default amount of time to live for an element from its last accessed or modified date
     * @param diskPersistent            whether to persist the cache to disk between JVM restarts
     * @param diskExpiryThreadIntervalSeconds
     *                                  how often to run the disk store expiry thread. A large number of 120 seconds plus is recommended
     * @param registeredEventListeners  a notification service. Optionally null, in which case a new one with no registered listeners will be created.
     * @param bootstrapCacheLoader      the BootstrapCacheLoader to use to populate the cache when it is first initialised. Null if none is required.
     * @param maxElementsOnDisk         the maximum number of Elements to allow on the disk. 0 means unlimited.
     * @param diskSpoolBufferSizeMB     the amount of memory to allocate the write buffer for puts to the DiskStore.
     * @since 1.3
     * @see #Cache(CacheConfiguration, RegisteredEventListeners, BootstrapCacheLoader) Cache(CacheConfiguration, RegisteredEventListeners, BootstrapCacheLoader),
     * for full construction support of version 2.0 and higher features.
     */
    public Cache(String name,
                 int maxElementsInMemory,
                 MemoryStoreEvictionPolicy memoryStoreEvictionPolicy,
                 boolean overflowToDisk,
                 String diskStorePath,
                 boolean eternal,
                 long timeToLiveSeconds,
                 long timeToIdleSeconds,
                 boolean diskPersistent,
                 long diskExpiryThreadIntervalSeconds,
                 RegisteredEventListeners registeredEventListeners,
                 BootstrapCacheLoader bootstrapCacheLoader,
                 int maxElementsOnDisk,
                 int diskSpoolBufferSizeMB) {

        this(new CacheConfiguration(name, maxElementsInMemory)
                    .memoryStoreEvictionPolicy(memoryStoreEvictionPolicy)
                    .overflowToDisk(overflowToDisk)
                    .diskStorePath(diskStorePath)
                    .eternal(eternal)
                    .timeToLiveSeconds(timeToLiveSeconds)
                    .timeToIdleSeconds(timeToIdleSeconds)
                    .diskPersistent(diskPersistent)
                    .diskExpiryThreadIntervalSeconds(diskExpiryThreadIntervalSeconds)
                    .maxElementsOnDisk(maxElementsOnDisk)
                    .diskSpoolBufferSizeMB(diskSpoolBufferSizeMB),
                registeredEventListeners,
                bootstrapCacheLoader);
    }

    /**
     * 1.6.0 Constructor
     * <p/>
     * The {@link net.sf.ehcache.config.ConfigurationFactory} and clients can create these.
     * <p/>
     * A client can specify their own settings here and pass the {@link Cache} object
     * into {@link CacheManager#addCache} to specify parameters other than the defaults.
     * <p/>
     * Only the CacheManager can initialise them.
     *
     * @param name                      the name of the cache. Note that "default" is a reserved name for the defaultCache.
     * @param maxElementsInMemory       the maximum number of elements in memory, before they are evicted (0 == no limit)
     * @param memoryStoreEvictionPolicy one of LRU, LFU and FIFO. Optionally null, in which case it will be set to LRU.
     * @param overflowToDisk            whether to use the disk store
     * @param diskStorePath             this parameter is ignored. CacheManager sets it using setter injection.
     * @param eternal                   whether the elements in the cache are eternal, i.e. never expire
     * @param timeToLiveSeconds         the default amount of time to live for an element from its creation date
     * @param timeToIdleSeconds         the default amount of time to live for an element from its last accessed or modified date
     * @param diskPersistent            whether to persist the cache to disk between JVM restarts
     * @param diskExpiryThreadIntervalSeconds
     *                                  how often to run the disk store expiry thread. A large number of 120 seconds plus is recommended
     * @param registeredEventListeners  a notification service. Optionally null, in which case a new one with no registered listeners will be created.
     * @param bootstrapCacheLoader      the BootstrapCacheLoader to use to populate the cache when it is first initialised. Null if none is required.
     * @param maxElementsOnDisk         the maximum number of Elements to allow on the disk. 0 means unlimited.
     * @param diskSpoolBufferSizeMB     the amount of memory to allocate the write buffer for puts to the DiskStore.
     * @param clearOnFlush              whether the MemoryStore should be cleared when {@link #flush flush()} is called on the cache
     * @since 1.6.0
     * @see #Cache(CacheConfiguration, RegisteredEventListeners, BootstrapCacheLoader) Cache(CacheConfiguration, RegisteredEventListeners, BootstrapCacheLoader),
     * for full construction support of version 2.0 and higher features.
     */
    public Cache(String name,
                 int maxElementsInMemory,
                 MemoryStoreEvictionPolicy memoryStoreEvictionPolicy,
                 boolean overflowToDisk,
                 String diskStorePath,
                 boolean eternal,
                 long timeToLiveSeconds,
                 long timeToIdleSeconds,
                 boolean diskPersistent,
                 long diskExpiryThreadIntervalSeconds,
                 RegisteredEventListeners registeredEventListeners,
                 BootstrapCacheLoader bootstrapCacheLoader,
                 int maxElementsOnDisk,
                 int diskSpoolBufferSizeMB,
                 boolean clearOnFlush) {

        this(new CacheConfiguration(name, maxElementsInMemory)
                    .memoryStoreEvictionPolicy(memoryStoreEvictionPolicy)
                    .overflowToDisk(overflowToDisk)
                    .diskStorePath(diskStorePath)
                    .eternal(eternal)
                    .timeToLiveSeconds(timeToLiveSeconds)
                    .timeToIdleSeconds(timeToIdleSeconds)
                    .diskPersistent(diskPersistent)
                    .diskExpiryThreadIntervalSeconds(diskExpiryThreadIntervalSeconds)
                    .maxElementsOnDisk(maxElementsOnDisk)
                    .diskSpoolBufferSizeMB(diskSpoolBufferSizeMB)
                    .clearOnFlush(clearOnFlush),
                registeredEventListeners,
                bootstrapCacheLoader);
    }

    /**
     * 1.7.0 Constructor
     * <p/>
     * The {@link net.sf.ehcache.config.ConfigurationFactory} and clients can create these.
     * <p/>
     * A client can specify their own settings here and pass the {@link Cache} object
     * into {@link CacheManager#addCache} to specify parameters other than the defaults.
     * <p/>
     * Only the CacheManager can initialise them.
     *
     * @param name                      the name of the cache. Note that "default" is a reserved name for the defaultCache.
     * @param maxElementsInMemory       the maximum number of elements in memory, before they are evicted (0 == no limit)
     * @param memoryStoreEvictionPolicy one of LRU, LFU and FIFO. Optionally null, in which case it will be set to LRU.
     * @param overflowToDisk            whether to use the disk store
     * @param diskStorePath             this parameter is ignored. CacheManager sets it using setter injection.
     * @param eternal                   whether the elements in the cache are eternal, i.e. never expire
     * @param timeToLiveSeconds         the default amount of time to live for an element from its creation date
     * @param timeToIdleSeconds         the default amount of time to live for an element from its last accessed or modified date
     * @param diskPersistent            whether to persist the cache to disk between JVM restarts
     * @param diskExpiryThreadIntervalSeconds
     *                                  how often to run the disk store expiry thread. A large number of 120 seconds plus is recommended
     * @param registeredEventListeners  a notification service. Optionally null, in which case a new one with no registered listeners will be created.
     * @param bootstrapCacheLoader      the BootstrapCacheLoader to use to populate the cache when it is first initialised. Null if none is required.
     * @param maxElementsOnDisk         the maximum number of Elements to allow on the disk. 0 means unlimited.
     * @param diskSpoolBufferSizeMB     the amount of memory to allocate the write buffer for puts to the DiskStore.
     * @param clearOnFlush              whether the MemoryStore should be cleared when {@link #flush flush()} is called on the cache
     * @param isTerracottaClustered     whether to cluster this cache with Terracotta
     * @param terracottaValueMode       either "SERIALIZATION" or "IDENTITY" mode, only used if isTerracottaClustered=true
     * @param terracottaCoherentReads   whether this cache should use coherent reads (usually should be true) unless optimizing for read-only
     * @since 1.7.0
     * @see #Cache(CacheConfiguration, RegisteredEventListeners, BootstrapCacheLoader) Cache(CacheConfiguration, RegisteredEventListeners, BootstrapCacheLoader),
     * for full construction support of version 2.0 and higher features.
     */
    public Cache(String name, int maxElementsInMemory, MemoryStoreEvictionPolicy memoryStoreEvictionPolicy, boolean overflowToDisk,
                 String diskStorePath, boolean eternal, long timeToLiveSeconds, long timeToIdleSeconds, boolean diskPersistent,
                 long diskExpiryThreadIntervalSeconds, RegisteredEventListeners registeredEventListeners,
                 BootstrapCacheLoader bootstrapCacheLoader, int maxElementsOnDisk, int diskSpoolBufferSizeMB, boolean clearOnFlush,
                 boolean isTerracottaClustered, String terracottaValueMode, boolean terracottaCoherentReads) {

        this(new CacheConfiguration(name, maxElementsInMemory)
                    .memoryStoreEvictionPolicy(memoryStoreEvictionPolicy)
                    .overflowToDisk(overflowToDisk)
                    .diskStorePath(diskStorePath)
                    .eternal(eternal)
                    .timeToLiveSeconds(timeToLiveSeconds)
                    .timeToIdleSeconds(timeToIdleSeconds)
                    .diskPersistent(diskPersistent)
                    .diskExpiryThreadIntervalSeconds(diskExpiryThreadIntervalSeconds)
                    .maxElementsOnDisk(maxElementsOnDisk)
                    .diskSpoolBufferSizeMB(diskSpoolBufferSizeMB)
                    .clearOnFlush(clearOnFlush)
                    .terracotta(new TerracottaConfiguration()
                        .clustered(isTerracottaClustered)
                        .valueMode(terracottaValueMode)
                        .coherentReads(terracottaCoherentReads)),
                registeredEventListeners,
                bootstrapCacheLoader);
    }


    /**
     * A factory method to create a RegisteredEventListeners
     */
    private static void registerCacheListeners(CacheConfiguration cacheConfiguration,
                                                 RegisteredEventListeners registeredEventListeners) {
        List cacheEventListenerConfigurations = cacheConfiguration.getCacheEventListenerConfigurations();
        for (Object cacheEventListenerConfiguration : cacheEventListenerConfigurations) {
            CacheConfiguration.CacheEventListenerFactoryConfiguration factoryConfiguration =
                    (CacheConfiguration.CacheEventListenerFactoryConfiguration) cacheEventListenerConfiguration;
            CacheEventListener cacheEventListener = createCacheEventListener(factoryConfiguration);
            registeredEventListeners.registerListener(cacheEventListener, factoryConfiguration.getListenFor());
        }
    }

    /**
     * A factory method to register cache extensions
     *
     * @param cacheConfiguration the cache configuration
     * @param cache              the cache
     */
    private static void registerCacheExtensions(CacheConfiguration cacheConfiguration, Ehcache cache) {
        List cacheExtensionConfigurations = cacheConfiguration.getCacheExtensionConfigurations();
        for (Object cacheExtensionConfiguration : cacheExtensionConfigurations) {
            CacheConfiguration.CacheExtensionFactoryConfiguration factoryConfiguration =
                    (CacheConfiguration.CacheExtensionFactoryConfiguration) cacheExtensionConfiguration;
            CacheExtension cacheExtension = createCacheExtension(factoryConfiguration, cache);
            cache.registerCacheExtension(cacheExtension);
        }
    }

    /**
     * A factory method to register cache Loaders
     *
     * @param cacheConfiguration the cache configuration
     * @param cache              the cache
     */
    private static void registerCacheLoaders(CacheConfiguration cacheConfiguration, Ehcache cache) {
        List cacheLoaderConfigurations = cacheConfiguration.getCacheLoaderConfigurations();
        for (Object cacheLoaderConfiguration : cacheLoaderConfigurations) {
            CacheConfiguration.CacheLoaderFactoryConfiguration factoryConfiguration =
                    (CacheConfiguration.CacheLoaderFactoryConfiguration) cacheLoaderConfiguration;
            CacheLoader cacheLoader = createCacheLoader(factoryConfiguration, cache);
            cache.registerCacheLoader(cacheLoader);
        }
    }

    /**
     * A factory method to register cache writers
     *
     * @param cacheConfiguration the cache configuration
     * @param cache              the cache
     */
    private static void registerCacheWriter(CacheConfiguration cacheConfiguration, Ehcache cache) {
        CacheWriterConfiguration config = cacheConfiguration.getCacheWriterConfiguration();
        if (config != null) {
            CacheWriter cacheWriter = createCacheWriter(config, cache);
            cache.registerCacheWriter(cacheWriter);
        }
    }


    /**
     * Tries to load the class specified otherwise defaults to null.
     *
     * @param factoryConfiguration
     */
    private static CacheEventListener createCacheEventListener(
            CacheConfiguration.CacheEventListenerFactoryConfiguration factoryConfiguration) {
        String className = null;
        CacheEventListener cacheEventListener = null;
        if (factoryConfiguration != null) {
            className = factoryConfiguration.getFullyQualifiedClassPath();
        }
        if (className == null) {
            LOG.debug("CacheEventListener factory not configured. Skipping...");
        } else {
            CacheEventListenerFactory factory = (CacheEventListenerFactory)
                    ClassLoaderUtil.createNewInstance(className);
            Properties properties =

                    PropertyUtil.parseProperties(factoryConfiguration.getProperties(),
                            factoryConfiguration.getPropertySeparator());
            cacheEventListener =
                    factory.createCacheEventListener(properties);
        }
        return cacheEventListener;
    }

    /**
     * Tries to load the class specified otherwise defaults to null.
     *
     * @param factoryConfiguration
     */
    private static CacheExtension createCacheExtension(
            CacheConfiguration.CacheExtensionFactoryConfiguration factoryConfiguration, Ehcache cache) {
        String className = null;
        CacheExtension cacheExtension = null;
        if (factoryConfiguration != null) {
            className = factoryConfiguration.getFullyQualifiedClassPath();
        }
        if (className == null) {
            LOG.debug("CacheExtension factory not configured. Skipping...");
        } else {
            CacheExtensionFactory factory = (CacheExtensionFactory) ClassLoaderUtil.createNewInstance(className);
            Properties properties = PropertyUtil.parseProperties(factoryConfiguration.getProperties(),
                    factoryConfiguration.getPropertySeparator());
            cacheExtension = factory.createCacheExtension(cache, properties);
        }
        return cacheExtension;
    }

    /**
     * Tries to load the class specified otherwise defaults to null.
     *
     * @param factoryConfiguration
     */
    private static CacheLoader createCacheLoader(
            CacheConfiguration.CacheLoaderFactoryConfiguration factoryConfiguration, Ehcache cache) {
        String className = null;
        CacheLoader cacheLoader = null;
        if (factoryConfiguration != null) {
            className = factoryConfiguration.getFullyQualifiedClassPath();
        }
        if (className == null) {
            LOG.debug("CacheLoader factory not configured. Skipping...");
        } else {
            CacheLoaderFactory factory = (CacheLoaderFactory) ClassLoaderUtil.createNewInstance(className);
            Properties properties = PropertyUtil.parseProperties(factoryConfiguration.getProperties(),
                    factoryConfiguration.getPropertySeparator());
            cacheLoader = factory.createCacheLoader(cache, properties);
        }
        return cacheLoader;
    }

    /**
     * Tries to load the class specified otherwise defaults to null.
     *
     * @param config
     */
    private static CacheWriter createCacheWriter(CacheWriterConfiguration config, Ehcache cache) {
        String className = null;
        CacheWriter cacheWriter = null;
        CacheWriterConfiguration.CacheWriterFactoryConfiguration factoryConfiguration = config.getCacheWriterFactoryConfiguration();
        if (factoryConfiguration != null) {
            className = factoryConfiguration.getFullyQualifiedClassPath();
        }
        if (null == className) {
            LOG.debug("CacheWriter factory not configured. Skipping...");
        } else {
            CacheWriterFactory factory = (CacheWriterFactory) ClassLoaderUtil.createNewInstance(className);
            Properties properties = PropertyUtil.parseProperties(factoryConfiguration.getProperties(),
                    factoryConfiguration.getPropertySeparator());
            if (null == properties) {
                properties = new Properties();
            }
            cacheWriter = factory.createCacheWriter(cache, properties);
        }
        return cacheWriter;
    }

    /**
     * Tries to load a BootstrapCacheLoader from the class specified.
     *
     * @return If there is none returns null.
     */
    private static final BootstrapCacheLoader createBootstrapCacheLoader(
            CacheConfiguration.BootstrapCacheLoaderFactoryConfiguration factoryConfiguration) throws CacheException {
        String className = null;
        BootstrapCacheLoader bootstrapCacheLoader = null;
        if (factoryConfiguration != null) {
            className = factoryConfiguration.getFullyQualifiedClassPath();
        }
        if (className == null || className.length() == 0) {
            LOG.debug("No BootstrapCacheLoaderFactory class specified. Skipping...");
        } else {
            BootstrapCacheLoaderFactory factory = (BootstrapCacheLoaderFactory)
                    ClassLoaderUtil.createNewInstance(className);
            Properties properties = PropertyUtil.parseProperties(factoryConfiguration.getProperties(),
                    factoryConfiguration.getPropertySeparator());
            return factory.createBootstrapCacheLoader(properties);
        }
        return bootstrapCacheLoader;
    }

    /**
     * Get the TransactionManagerLookup implementation used to lookup the TransactionManager.
     * This is generally only set for XA transactional caches
     * @return The {@link net.sf.ehcache.transaction.manager.TransactionManagerLookup} instance
     */
    public TransactionManagerLookup getTransactionManagerLookup() {
       return transactionManagerLookup;
    }

    /**
     * Sets the TransactionManagerLookup that needs to be used for this cache to lookup the TransactionManager
     * This needs to be set before {@link Cache#initialise()} is called
     * @param lookup The {@link net.sf.ehcache.transaction.manager.TransactionManagerLookup} instance
     */
    public void setTransactionManagerLookup(TransactionManagerLookup lookup) {
        this.transactionManagerLookup = lookup;
    }

    /**
     * Newly created caches do not have a {@link net.sf.ehcache.store.MemoryStore} or a {@link net.sf.ehcache.store.DiskStore}.
     * <p/>
     * This method creates those and makes the cache ready to accept elements
     */
    public void initialise() {
        synchronized (this) {
            if (!status.equals(Status.STATUS_UNINITIALISED)) {
                throw new IllegalStateException("Cannot initialise the " + configuration.getName()
                        + " cache because its status is not STATUS_UNINITIALISED");
            }

            if (configuration.getMaxElementsInMemory() == 0) {
                LOG.warn("Cache: " + configuration.getName() + " has a maxElementsInMemory of 0.  " +
                        "In Ehcache 2.0 this has been changed to mean a store with no capacity limit.");
            }

            this.diskStore = createDiskStore();

            if (configuration.isTransactional()) {
                //set copy on read
                configuration.getTerracottaConfiguration().setCopyOnRead(true);
            }

            final Store memStore;
            if (isTerracottaClustered()) {
                memStore = cacheManager.createTerracottaStore(this);
                boolean unlockedReads = !this.configuration.getTerracottaConfiguration().getCoherentReads();
                // if coherentReads=false, make coherent=false
                boolean coherent = unlockedReads ? false : this.configuration.getTerracottaConfiguration().isCoherent();
                memStore.setNodeCoherent(coherent);
            } else {
                if (useClassicLru && configuration.getMemoryStoreEvictionPolicy().equals(MemoryStoreEvictionPolicy.LRU)) {
                    memStore = new LruMemoryStore(this, diskStore);
                } else {
                    memStore = MemoryStore.create(this, diskStore);
                }
            }

            if (configuration.isTransactional()) {
                if (!configuration.isTerracottaClustered()
                    || configuration.getTerracottaConfiguration().getValueMode() == TerracottaConfiguration.ValueMode.IDENTITY) {
                    throw new CacheException("To be transactional, the cache needs to be Terracotta clustered in Serialization value mode");
                }

                TransactionManager txnManager = transactionManagerLookup.getTransactionManager();
                if (txnManager == null) {
                    throw new CacheException("You've configured cache " + cacheManager.getName() + "."
                                             + configuration.getName() + " to be transactional, but no TransactionManager could be found!");
                }
                //set xa enabled
                configuration.getTerracottaConfiguration().setCacheXA(true);

                EhcacheXAStore ehcacheXAStore = cacheManager.createEhcacheXAStore(this, memStore);

                // this xaresource is for initial registration and recovery
                EhcacheXAResourceImpl xaResource = new EhcacheXAResourceImpl(this, txnManager, ehcacheXAStore);
                transactionManagerLookup.register(xaResource);


                this.memoryStore = new XATransactionalStore(this, ehcacheXAStore, transactionManagerLookup);
            } else {
                this.memoryStore = memStore;
            }
            this.cacheWriterManager = configuration.getCacheWriterConfiguration().getWriteMode().createWriterManager(this);
            initialiseCacheWriterManager(false);

            changeStatus(Status.STATUS_ALIVE);
            initialiseRegisteredCacheExtensions();
            initialiseRegisteredCacheLoaders();
            initialiseRegisteredCacheWriter();

            // initialize live statistics
            // register to get notifications of
            // put/update/removeInternal/expiry/eviction
            getCacheEventNotificationService().registerListener(liveCacheStatisticsData);
            // set up default values
            liveCacheStatisticsData.setStatisticsAccuracy(Statistics.STATISTICS_ACCURACY_BEST_EFFORT);
            liveCacheStatisticsData.setStatisticsEnabled(configuration.getStatistics());

            // register the sampled cache statistics
            this.registerCacheUsageListener(sampledCacheStatistics);

            if (isTerracottaClustered()) {
                // create this to be sure that it's present on each node to receive clustered events,
                // even if this node is not sending out its events
                cacheManager.createTerracottaEventReplicator(this);
            }
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("Initialised cache: " + configuration.getName());
        }

        if (disabled) {
            LOG.warn("Cache: " + configuration.getName() + " is disabled because the " + NET_SF_EHCACHE_DISABLED
                    + " property was set to true. No elements will be added to the cache.");
        }
    }

    /**
     * The CacheWriterManager's initialisation can be deferred until an actual CacheWriter has been registered.
     * <p/>
     * This allows users to register a cache through XML in the cache manager and still specify the CacheWriter manually through Java code, possibly referencing local resources.
     *
     * @param imperative indicates whether it's imperative for the cache writer manager to be initialised before operations can continue
     * @throws CacheException when the CacheWriterManager couldn't be initialised but it was imperative to do so
     */
    private void initialiseCacheWriterManager(boolean imperative) throws CacheException {
        if (!cacheWriterManagerInitFlag.get()) {
            cacheWriterManagerInitLock.lock();
            try {
                if (!cacheWriterManagerInitFlag.get()) {
                    if (cacheWriterManager != null && registeredCacheWriter != null) {
                        cacheWriterManager.init(this);
                        cacheWriterManagerInitFlag.set(true);
                    } else if (imperative) {
                        throw new CacheException("Cache: " + configuration.getName() + " was being used with cache writer " +
                                "features, but it wasn't properly registered beforehand.");
                    }
                }
            } finally {
                cacheWriterManagerInitLock.unlock();
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    public CacheWriterManager getWriterManager() {
        return cacheWriterManager;
    }

    /**
     * Creates a disk store when either:
     * <ol>
     * <li>overflowToDisk is enabled
     * <li>diskPersistent is enabled
     * </ol>
     *
     * @return the disk store
     */
    protected Store createDiskStore() {
        if (isDiskStore()) {
            return DiskStore.create(this, diskStorePath);
        } else {
            return null;
        }
    }

    /**
     * Whether this cache uses a disk store
     *
     * @return true if the cache either overflows to disk or is disk persistent
     */
    protected boolean isDiskStore() {
        return configuration.isOverflowToDisk() || configuration.isDiskPersistent();
    }

    /**
     * Indicates whether this cache is clustered by Terracotta
     *
     * @return {@code true} when the cache is clustered by Terracotta; or {@code false} otherwise
     */
    public boolean isTerracottaClustered() {
        return configuration.isTerracottaClustered();
    }

    /**
     * Bootstrap command. This must be called after the Cache is initialised, during
     * CacheManager initialisation. If loads are synchronous, they will complete before the CacheManager
     * initialise completes, otherwise they will happen in the background.
     */
    public void bootstrap() {
        if (!disabled && bootstrapCacheLoader != null) {
            bootstrapCacheLoader.load(this);
        }

    }

    private void changeStatus(Status status) {
        this.status = status;
    }


    /**
     * Put an element in the cache.
     * <p/>
     * Resets the access statistics on the element, which would be the case if it has previously been
     * gotten from a cache, and is now being put back.
     * <p/>
     * Also notifies the CacheEventListener that:
     * <ul>
     * <li>the element was put, but only if the Element was actually put.
     * <li>if the element exists in the cache, that an update has occurred, even if the element would be expired
     * if it was requested
     * </ul>
     * <p/>
     * Caches which use synchronous replication can throw RemoteCacheException here if the replication to the cluster fails.
     * This exception should be caught in those circumstances.
     *
     * @param element A cache Element. If Serializable it can fully participate in replication and the DiskStore. If it is
     *                <code>null</code> or the key is <code>null</code>, it is ignored as a NOOP.
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     * @throws CacheException
     */
    public final void put(Element element) throws IllegalArgumentException, IllegalStateException,
            CacheException {
        put(element, false);
    }


    /**
     * Put an element in the cache.
     * <p/>
     * Resets the access statistics on the element, which would be the case if it has previously been
     * gotten from a cache, and is now being put back.
     * <p/>
     * Also notifies the CacheEventListener that:
     * <ul>
     * <li>the element was put, but only if the Element was actually put.
     * <li>if the element exists in the cache, that an update has occurred, even if the element would be expired
     * if it was requested
     * </ul>
     * Caches which use synchronous replication can throw RemoteCacheException here if the replication to the cluster fails.
     * This exception should be caught in those circumstances.
     *
     * @param element                     A cache Element. If Serializable it can fully participate in replication and the DiskStore. If it is
     *                                    <code>null</code> or the key is <code>null</code>, it is ignored as a NOOP.
     * @param doNotNotifyCacheReplicators whether the put is coming from a doNotNotifyCacheReplicators cache peer, in which case this put should not initiate a
     *                                    further notification to doNotNotifyCacheReplicators cache peers
     * @throws IllegalStateException    if the cache is not {@link Status#STATUS_ALIVE}
     * @throws IllegalArgumentException if the element is null
     */
    public final void put(Element element, boolean doNotNotifyCacheReplicators) throws IllegalArgumentException,
            IllegalStateException, CacheException {
        putInternal(element, doNotNotifyCacheReplicators, false);
    }

    /**
     * {@inheritDoc}
     */
    public void putWithWriter(Element element) throws IllegalArgumentException, IllegalStateException, CacheException {
        putInternal(element, false, true);
    }

    private void putInternal(Element element, boolean doNotNotifyCacheReplicators, boolean useCacheWriter) {
        if (useCacheWriter) {
            initialiseCacheWriterManager(true);
        }

        checkStatus();

        if (disabled) {
            return;
        }

        if (element == null) {
            if (doNotNotifyCacheReplicators) {

                LOG.debug("Element from replicated put is null. This happens because the element is a SoftReference" +
                        " and it has been collected. Increase heap memory on the JVM or set -Xms to be the same as " +
                        "-Xmx to avoid this problem.");

            }
            //nulls are ignored
            return;
        }


        if (element.getObjectKey() == null) {
            //nulls are ignored
            return;
        }

        element.resetAccessStatistics();
        
        applyDefaultsToElementWithoutLifespanSet(element);

        backOffIfDiskSpoolFull();

        if (useCacheWriter) {
            boolean elementExists = false;
            try {
                elementExists = isElementOnDisk(element.getObjectKey());
                if (!elementExists) {
                    elementExists = memoryStore.containsKey(element.getObjectKey());
                }
                elementExists = !memoryStore.putWithWriter(element, cacheWriterManager) || elementExists;
                if (elementExists) {
                    element.updateUpdateStatistics();
                }
                notifyPutInternalListeners(element, doNotNotifyCacheReplicators, elementExists);
            } catch (CacheWriterManagerException e) {
                if (configuration.getCacheWriterConfiguration().getNotifyListenersOnException()) {
                    notifyPutInternalListeners(element, doNotNotifyCacheReplicators, elementExists);
                }
                throw e.getCause();
            }
        } else {
            boolean elementExists = isElementOnDisk(element.getObjectKey());
            elementExists = !memoryStore.put(element) || elementExists;
            if (elementExists) {
                element.updateUpdateStatistics();
            }
            notifyPutInternalListeners(element, doNotNotifyCacheReplicators, elementExists);
        }
    }

    private void notifyPutInternalListeners(Element element, boolean doNotNotifyCacheReplicators, boolean elementExists) {
        if (elementExists) {
            registeredEventListeners.notifyElementUpdated(element, doNotNotifyCacheReplicators);
        } else {
            registeredEventListeners.notifyElementPut(element, doNotNotifyCacheReplicators);
        }
    }

    /**
     * wait outside of synchronized block so as not to block readers
     * If the disk store spool is full wait a short time to give it a chance to
     * catch up.
     * todo maybe provide a warning if this is continually happening or monitor via JMX
     */
    private void backOffIfDiskSpoolFull() {

        if (diskStore != null && diskStore.bufferFull()) {
            //back off to avoid OutOfMemoryError
            try {
                Thread.sleep(BACK_OFF_TIME_MILLIS);
            } catch (InterruptedException e) {
                //do not care if this happens
            }
        }
    }

    private void applyDefaultsToElementWithoutLifespanSet(Element element) {
        if (!element.isLifespanSet()) {
            element.setLifespanDefaults(TimeUtil.convertTimeToInt(configuration.getTimeToIdleSeconds()),
                    TimeUtil.convertTimeToInt(configuration.getTimeToLiveSeconds()),
                    configuration.isEternal());
        }
    }

    /**
     * Put an element in the cache, without updating statistics, or updating listeners. This is meant to be used
     * in conjunction with {@link #getQuiet}.
     * Synchronization is handled within the method.
     * <p/>
     * Caches which use synchronous replication can throw RemoteCacheException here if the replication to the cluster fails.
     * This exception should be caught in those circumstances.
     * <p/>
     *
     * @param element A cache Element. If Serializable it can fully participate in replication and the DiskStore. If it is
     *                <code>null</code> or the key is <code>null</code>, it is ignored as a NOOP.
     * @throws IllegalStateException    if the cache is not {@link Status#STATUS_ALIVE}
     * @throws IllegalArgumentException if the element is null
     */
    public final void putQuiet(Element element) throws IllegalArgumentException, IllegalStateException,
            CacheException {
        checkStatus();

        if (disabled) {
            return;
        }

        if (element == null || element.getObjectKey() == null) {
            //nulls are ignored
            return;
        }

        applyDefaultsToElementWithoutLifespanSet(element);

        memoryStore.put(element);
    }

    /**
     * Gets an element from the cache. Updates Element Statistics
     * <p/>
     * Note that the Element's lastAccessTime is always the time of this get.
     * Use {@link #getQuiet(Object)} to peak into the Element to see its last access time with get
     * <p/>
     * Synchronization is handled within the method.
     *
     * @param key a serializable value. Null keys are not stored so get(null) always returns null
     * @return the element, or null, if it does not exist.
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     * @see #isExpired
     */
    public final Element get(Serializable key) throws IllegalStateException, CacheException {
        return get((Object) key);
    }


    /**
     * Gets an element from the cache. Updates Element Statistics
     * <p/>
     * Note that the Element's lastAccessTime is always the time of this get.
     * Use {@link #getQuiet(Object)} to peak into the Element to see its last access time with get
     * <p/>
     * Synchronization is handled within the method.
     *
     * @param key an Object value
     * @return the element, or null, if it does not exist.
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     * @see #isExpired
     * @since 1.2
     */
    public final Element get(Object key) throws IllegalStateException, CacheException {
        checkStatus();

        if (disabled) {
            return null;
        }

        if (isStatisticsEnabled()) {
            Element element;
            long start = System.currentTimeMillis();

            element = searchInMemoryStoreWithStats(key, false, true);
            if (element == null && isDiskStore()) {
                element = searchInDiskStoreWithStats(key, false, true);
            }
            if (element == null) {
                liveCacheStatisticsData.cacheMissNotFound();
                if (LOG.isDebugEnabled()) {
                    LOG.debug(configuration.getName() + " cache - Miss");
                }
            }
            //todo is this expensive. Maybe ditch.
            long end = System.currentTimeMillis();
            liveCacheStatisticsData.addGetTimeMillis(end - start);
            return element;
        } else {
            Element element = searchInMemoryStoreWithoutStats(key, false, true);
            if (element == null && isDiskStore()) {
                element = searchInDiskStoreWithoutStats(key, false, true);
            }
            return element;
        }
    }

    /**
     * This method will return, from the cache, the Element associated with the argument "key".
     * <p/>
     * If the Element is not in the cache, the associated cache loader will be called. That is either the CacheLoader passed in, or if null,
     * the one associated with the cache. If both are null, no load is performed and null is returned.
     * <p/>
     * If the loader decides to assign a null value to the Element, an Element with a null value is created and stored in the cache.
     * <p/>
     * Because this method may take a long time to complete, it is not synchronized. The underlying cache operations
     * are synchronized.
     *
     * @param key            key whose associated value is to be returned.
     * @param loader         the override loader to use. If null, the cache's default loader will be used
     * @param loaderArgument an argument to pass to the CacheLoader.
     * @return an element if it existed or could be loaded, otherwise null
     * @throws CacheException
     */
    public Element getWithLoader(Object key, CacheLoader loader, Object loaderArgument) throws CacheException {

        Element element = get(key);
        if (element != null) {
            return element;
        }

        if (registeredCacheLoaders.size() == 0 && loader == null) {
            return null;
        }

        try {
            //check again in case the last thread loaded it
            element = getQuiet(key);
            if (element != null) {
                return element;
            }
            Future future = asynchronousLoad(key, loader, loaderArgument);
            //wait for result
            future.get();
        } catch (Exception e) {
            throw new CacheException("Exception on load for key " + key, e);
        }
        return getQuiet(key);
    }

    /**
     * The load method provides a means to "pre load" the cache. This method will, asynchronously, load the specified
     * object into the cache using the associated CacheLoader. If the object already exists in the cache, no action is
     * taken. If no loader is associated with the object, no object will be loaded into the cache. If a problem is
     * encountered during the retrieving or loading of the object, an exception should be logged. If the "arg" argument
     * is set, the arg object will be passed to the CacheLoader.load method. The cache will not dereference the object.
     * If no "arg" value is provided a null will be passed to the load method. The storing of null values in the cache
     * is permitted, however, the get method will not distinguish returning a null stored in the cache and not finding
     * the object in the cache. In both cases a null is returned.
     * <p/>
     * The Ehcache native API provides similar functionality to loaders using the
     * decorator {@link net.sf.ehcache.constructs.blocking.SelfPopulatingCache}
     *
     * @param key key whose associated value to be loaded using the associated CacheLoader if this cache doesn't contain it.
     * @throws CacheException
     */
    public void load(final Object key) throws CacheException {
        if (registeredCacheLoaders.size() == 0) {

            LOG.debug("The CacheLoader is null. Returning.");
            return;
        }

        boolean existsOnCall = isKeyInCache(key);
        if (existsOnCall) {

            LOG.debug("The key {} exists in the cache. Returning.", key);
            return;
        }

        asynchronousLoad(key, null, null);
    }

    /**
     * The getAll method will return, from the cache, a Map of the objects associated with the Collection of keys in argument "keys".
     * If the objects are not in the cache, the associated cache loader will be called. If no loader is associated with an object,
     * a null is returned. If a problem is encountered during the retrieving or loading of the objects, an exception will be thrown.
     * If the "arg" argument is set, the arg object will be passed to the CacheLoader.loadAll method. The cache will not dereference
     * the object. If no "arg" value is provided a null will be passed to the loadAll method. The storing of null values in the cache
     * is permitted, however, the get method will not distinguish returning a null stored in the cache and not finding the object in
     * the cache. In both cases a null is returned.
     * <p/>
     * <p/>
     * Note. If the getAll exceeds the maximum cache size, the returned map will necessarily be less than the number specified.
     * <p/>
     * Because this method may take a long time to complete, it is not synchronized. The underlying cache operations
     * are synchronized.
     * <p/>
     * The constructs package provides similar functionality using the
     * decorator {@link net.sf.ehcache.constructs.blocking.SelfPopulatingCache}
     *
     * @param keys           a collection of keys to be returned/loaded
     * @param loaderArgument an argument to pass to the CacheLoader.
     * @return a Map populated from the Cache. If there are no elements, an empty Map is returned.
     * @throws CacheException
     */
    public Map getAllWithLoader(Collection keys, Object loaderArgument) throws CacheException {
        if (keys == null) {
            return new HashMap(0);
        }
        Map<Object, Object> map = new HashMap<Object, Object>(keys.size());

        List<Object> missingKeys = new ArrayList<Object>(keys.size());

        if (registeredCacheLoaders.size() > 0) {
            Object key = null;
            try {
                map = new HashMap<Object, Object>(keys.size());

                for (Object key1 : keys) {
                    key = key1;

                    if (isKeyInCache(key)) {
                        Element element = get(key);
                        if (element != null) {
                            map.put(key, element.getObjectValue());
                        } else {
                            map.put(key, null);
                        }
                    } else {
                        missingKeys.add(key);
                    }
                }

                //now load everything that's missing.
                Future future = asynchronousLoadAll(missingKeys, loaderArgument);
                future.get();


                for (Object missingKey : missingKeys) {
                    key = missingKey;
                    Element element = get(key);
                    if (element != null) {
                        map.put(key, element.getObjectValue());
                    } else {
                        map.put(key, null);
                    }
                }

            } catch (InterruptedException e) {
                throw new CacheException(e.getMessage() + " for key " + key, e);
            } catch (ExecutionException e) {
                throw new CacheException(e.getMessage() + " for key " + key, e);
            }
        } else {
            for (Object key : keys) {
                Element element = get(key);
                if (element != null) {
                    map.put(key, element.getObjectValue());
                } else {
                    map.put(key, null);
                }
            }
        }
        return map;
    }


    /**
     * The loadAll method provides a means to "pre load" objects into the cache. This method will, asynchronously, load
     * the specified objects into the cache using the associated cache loader(s). If the an object already exists in the
     * cache, no action is taken. If no loader is associated with the object, no object will be loaded into the cache.
     * If a problem is encountered during the retrieving or loading of the objects, an exception (to be defined)
     * should be logged. The getAll method will return, from the cache, a Map of the objects associated with the
     * Collection of keys in argument "keys". If the objects are not in the cache, the associated cache loader will be
     * called. If no loader is associated with an object, a null is returned. If a problem is encountered during the
     * retrieving or loading of the objects, an exception (to be defined) will be thrown. If the "arg" argument is set,
     * the arg object will be passed to the CacheLoader.loadAll method. The cache will not dereference the object.
     * If no "arg" value is provided a null will be passed to the loadAll method.
     * <p/>
     * keys - collection of the keys whose associated values to be loaded into this cache by using the associated
     * CacheLoader if this cache doesn't contain them.
     * <p/>
     * The Ehcache native API provides similar functionality to loaders using the
     * decorator {@link net.sf.ehcache.constructs.blocking.SelfPopulatingCache}
     */
    public void loadAll(final Collection keys, final Object argument) throws CacheException {

        if (registeredCacheLoaders.size() == 0) {

            LOG.debug("The CacheLoader is null. Returning.");
            return;
        }
        if (keys == null) {
            return;
        }
        asynchronousLoadAll(keys, argument);
    }

    /**
     * Gets an element from the cache, without updating Element statistics. Cache statistics are
     * still updated. Listeners are not called.
     * <p/>
     *
     * @param key a serializable value
     * @return the element, or null, if it does not exist.
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     * @see #isExpired
     */
    public final Element getQuiet(Serializable key) throws IllegalStateException, CacheException {
        return getQuiet((Object) key);
    }

    /**
     * Gets an element from the cache, without updating Element statistics. Cache statistics are
     * not updated.
     * <p/>
     * Listeners are not called.
     *
     * @param key a serializable value
     * @return the element, or null, if it does not exist.
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     * @see #isExpired
     * @since 1.2
     */
    public final Element getQuiet(Object key) throws IllegalStateException, CacheException {
        checkStatus();
        Element element = searchInMemoryStoreWithoutStats(key, true, false);
        if (element == null && isDiskStore()) {
            element = searchInDiskStoreWithoutStats(key, true, false);
        }
        return element;
    }

    /**
     * Returns a list of all element keys in the cache, whether or not they are expired.
     * <p/>
     * The returned keys are unique and can be considered a set.
     * <p/>
     * The List returned is not live. It is a copy.
     * <p/>
     * The time taken is O(n). On a single CPU 1.8Ghz P4, approximately 8ms is required
     * for each 1000 entries.
     *
     * @return a list of {@link Object} keys
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     */
    public final List getKeys() throws IllegalStateException, CacheException {
        checkStatus();
        /* An element with the same key can exist in both the memory store and the
            disk store at the same time. Because the memory store is always searched first
            these duplicates do not cause problems when getting elements/

            This method removes these duplicates before returning the list of keys*/
        List<Object> allKeyList = new ArrayList<Object>();
        List<Object> keyList = Arrays.asList(memoryStore.getKeyArray());
        allKeyList.addAll(keyList);
        if (isDiskStore()) {
            Set<Object> allKeys = new HashSet<Object>();
            //within the store keys will be unique
            allKeys.addAll(keyList);
            Object[] diskKeys = diskStore.getKeyArray();
            for (Object diskKey : diskKeys) {
                if (allKeys.add(diskKey)) {
                    //Unique, so add it to the list
                    allKeyList.add(diskKey);
                }
            }
        }
        return allKeyList;
    }

    /**
     * Returns a list of all element keys in the cache. Only keys of non-expired
     * elements are returned.
     * <p/>
     * The returned keys are unique and can be considered a set.
     * <p/>
     * The List returned is not live. It is a copy.
     * <p/>
     * The time taken is O(n), where n is the number of elements in the cache. On
     * a 1.8Ghz P4, the time taken is approximately 200ms per 1000 entries. This method
     * is not synchronized, because it relies on a non-live list returned from {@link #getKeys()}
     * , which is synchronised, and which takes 8ms per 1000 entries. This way
     * cache liveness is preserved, even if this method is very slow to return.
     * <p/>
     * Consider whether your usage requires checking for expired keys. Because
     * this method takes so long, depending on cache settings, the list could be
     * quite out of date by the time you get it.
     *
     * @return a list of {@link Object} keys
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     */
    public final List getKeysWithExpiryCheck() throws IllegalStateException, CacheException {
        List allKeyList = getKeys();
        //removeInternal keys of expired elements
        ArrayList<Object> nonExpiredKeys = new ArrayList<Object>(allKeyList.size());
        int allKeyListSize = allKeyList.size();
        for (int i = 0; i < allKeyListSize; i++) {
            Object key = allKeyList.get(i);
            Element element = getQuiet(key);
            if (element != null) {
                nonExpiredKeys.add(key);
            }
        }
        nonExpiredKeys.trimToSize();
        return nonExpiredKeys;
    }


    /**
     * Returns a list of all elements in the cache, whether or not they are expired.
     * <p/>
     * The returned keys are not unique and may contain duplicates. If the cache is only
     * using the memory store, the list will be unique. If the disk store is being used
     * as well, it will likely contain duplicates, because of the internal store design.
     * <p/>
     * The List returned is not live. It is a copy.
     * <p/>
     * The time taken is O(log n). On a single CPU 1.8Ghz P4, approximately 6ms is required
     * for 1000 entries and 36 for 50000.
     * <p/>
     * This is the fastest getKeys method
     *
     * @return a list of {@link Object} keys
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     */
    public final List getKeysNoDuplicateCheck() throws IllegalStateException {
        checkStatus();
        ArrayList<Object> allKeys = new ArrayList<Object>();
        List<Object> memoryKeySet = Arrays.asList(memoryStore.getKeyArray());
        allKeys.addAll(memoryKeySet);
        if (isDiskStore()) {
            List<Object> diskKeySet = Arrays.asList(diskStore.getKeyArray());
            allKeys.addAll(diskKeySet);
        }
        return allKeys;
    }

    private Element searchInMemoryStoreWithStats(Object key, boolean quiet, boolean notifyListeners) {
        Element element;
        if (quiet) {
            element = memoryStore.getQuiet(key);
        } else {
            element = memoryStore.get(key);
        }

        if (element != null) {
            if (isExpired(element)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug(configuration.getName() + " Memory cache hit, but element expired");
                }
                if (!quiet) {
                    liveCacheStatisticsData.cacheMissExpired();
                }
                removeInternal(key, true, notifyListeners, false, false);
                element = null;
            } else if (!quiet) {
                element.updateAccessStatistics();
                if (LOG.isDebugEnabled()) {
                    LOG.debug(getName() + "Cache: " + getName() + "MemoryStore hit for " + key);
                }
                liveCacheStatisticsData.cacheHitInMemory();
            }
        } else if (LOG.isDebugEnabled()) {
            LOG.debug(getName() + "Cache: " + getName() + "MemoryStore miss for " + key);
        }
        return element;
    }

    private Element searchInMemoryStoreWithoutStats(Object key, boolean quiet, boolean notifyListeners) {
        Element element;
        if (quiet) {
            element = memoryStore.getQuiet(key);
        } else {
            element = memoryStore.get(key);
        }

        if (element != null) {
            if (isExpired(element)) {
                removeInternal(key, true, notifyListeners, false, false);
                element = null;
            } else if (!(quiet || skipUpdateAccessStatistics(element))) {
                element.updateAccessStatistics();
            }
        }
        return element;
    }

    private boolean skipUpdateAccessStatistics(Element element) {
      return configuration.isFrozen() && element.isEternal()
              && (configuration.getMaxElementsInMemory() == 0)
              && (!configuration.isOverflowToDisk() || configuration.getMaxElementsOnDisk() == 0);
    }

    private Element searchInDiskStoreWithStats(Object key, boolean quiet, boolean notifyListeners) {
        if (!(key instanceof Serializable)) {
            return null;
        }
        Serializable serializableKey = (Serializable) key;
        Element element;
        if (quiet) {
            element = diskStore.getQuiet(serializableKey);
        } else {
            element = diskStore.get(serializableKey);
        }

        if (element != null) {
            if (isExpired(element)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug(configuration.getName() + " cache - Disk Store hit, but element expired");
                }
                liveCacheStatisticsData.cacheMissExpired();
                removeInternal(key, true, notifyListeners, false, false);
                element = null;
            } else {
                if (!quiet) {
                    element.updateAccessStatistics();
                }
                liveCacheStatisticsData.cacheHitOnDisk();
                //Put the item back into memory to preserve policies in the memory store and to save updated statistics
                //todo - maybe make the DiskStore a one-way evict. i.e. Do not replace See testGetSpeedMostlyDisk for speed comp.
                memoryStore.put(element);
            }
        }
        return element;
    }

    private Element searchInDiskStoreWithoutStats(Object key, boolean quiet, boolean notifyListeners) {
        if (!(key instanceof Serializable)) {
            return null;
        }
        Serializable serializableKey = (Serializable) key;
        Element element;
        if (quiet) {
            element = diskStore.getQuiet(serializableKey);
        } else {
            element = diskStore.get(serializableKey);
        }

        if (element != null) {
            if (isExpired(element)) {
                removeInternal(key, true, notifyListeners, false, false);
                element = null;
            } else {
                if (!quiet) {
                    element.updateAccessStatistics();
                }
                //Put the item back into memory to preserve policies in the memory store and to save updated statistics
                //todo - maybe make the DiskStore a one-way evict. i.e. Do not replace See testGetSpeedMostlyDisk for speed comp.
                memoryStore.put(element);
            }
        }
        return element;
    }
    /**
     * Removes an {@link Element} from the Cache. This also removes it from any
     * stores it may be in.
     * <p/>
     * Also notifies the CacheEventListener after the element was removed.
     * <p/>
     * Synchronization is handled within the method.
     * <p/>
     * Caches which use synchronous replication can throw RemoteCacheException here if the replication to the cluster fails.
     * This exception should be caught in those circumstances.
     *
     * @param key the element key to operate on
     * @return true if the element was removed, false if it was not found in the cache
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     */
    public final boolean remove(Serializable key) throws IllegalStateException {
        return remove((Object) key);
    }

    /**
     * Removes an {@link Element} from the Cache. This also removes it from any
     * stores it may be in.
     * <p/>
     * Also notifies the CacheEventListener after the element was removed, but only if an Element
     * with the key actually existed.
     * <p/>
     * Synchronization is handled within the method.
     * <p/>
     * Caches which use synchronous replication can throw RemoteCacheException here if the replication to the cluster fails.
     * This exception should be caught in those circumstances.
     * <p/>
     *
     * @param key the element key to operate on
     * @return true if the element was removed, false if it was not found in the cache
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     * @since 1.2
     */
    public final boolean remove(Object key) throws IllegalStateException {
        return remove(key, false);
    }


    /**
     * Removes an {@link Element} from the Cache. This also removes it from any
     * stores it may be in.
     * <p/>
     * Also notifies the CacheEventListener after the element was removed, but only if an Element
     * with the key actually existed.
     * <p/>
     * Synchronization is handled within the method.
     * <p/>
     * Caches which use synchronous replication can throw RemoteCacheException here if the replication to the cluster fails.
     * This exception should be caught in those circumstances.
     *
     * @param key                         the element key to operate on
     * @param doNotNotifyCacheReplicators whether the put is coming from a doNotNotifyCacheReplicators cache peer, in which case this put should not initiate a
     *                                    further notification to doNotNotifyCacheReplicators cache peers
     * @return true if the element was removed, false if it was not found in the cache
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     */
    public final boolean remove(Serializable key, boolean doNotNotifyCacheReplicators) throws IllegalStateException {
        return remove((Object) key, doNotNotifyCacheReplicators);
    }

    /**
     * Removes an {@link Element} from the Cache. This also removes it from any
     * stores it may be in.
     * <p/>
     * Also notifies the CacheEventListener after the element was removed, but only if an Element
     * with the key actually existed.
     * <p/>
     * Synchronization is handled within the method.
     *
     * @param key                         the element key to operate on
     * @param doNotNotifyCacheReplicators whether the put is coming from a doNotNotifyCacheReplicators cache peer, in which case this put should not initiate a
     *                                    further notification to doNotNotifyCacheReplicators cache peers
     * @return true if the element was removed, false if it was not found in the cache
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     */
    public final boolean remove(Object key, boolean doNotNotifyCacheReplicators) throws IllegalStateException {
        return removeInternal(key, false, true, doNotNotifyCacheReplicators, false);
    }

    /**
     * Removes an {@link Element} from the Cache, without notifying listeners. This also removes it from any
     * stores it may be in.
     * <p/>
     * Listeners are not called.
     *
     * @param key the element key to operate on
     * @return true if the element was removed, false if it was not found in the cache
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     */
    public final boolean removeQuiet(Serializable key) throws IllegalStateException {
        return removeInternal(key, false, false, false, false);
    }

    /**
     * Removes an {@link Element} from the Cache, without notifying listeners. This also removes it from any
     * stores it may be in.
     * <p/>
     * Listeners are not called.
     * <p/>
     * Caches which use synchronous replication can throw RemoteCacheException here if the replication to the cluster fails.
     * This exception should be caught in those circumstances.
     *
     * @param key the element key to operate on
     * @return true if the element was removed, false if it was not found in the cache
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     * @since 1.2
     */
    public final boolean removeQuiet(Object key) throws IllegalStateException {
        return removeInternal(key, false, false, false, false);
    }

    /**
     * {@inheritDoc}
     */
    public boolean removeWithWriter(Object key) throws IllegalStateException {
        return removeInternal(key, false, true, false, true);
    }

    /**
     * Removes or expires an {@link Element} from the Cache after an attempt to get it determined that it should be expired.
     * This also removes it from any stores it may be in.
     * <p/>
     * Also notifies the CacheEventListener after the element has expired, but only if an Element
     * with the key actually existed.
     * <p/>
     * Synchronization is handled within the method.
     * <p/>
     * If a remove was called, listeners are notified, regardless of whether the element existed or not.
     * This allows distributed cache listeners to remove elements from a cluster regardless of whether they
     * existed locally.
     * <p/>
     * Caches which use synchronous replication can throw RemoteCacheException here if the replication to the cluster fails.
     * This exception should be caught in those circumstances.
     *
     * @param key                         the element key to operate on
     * @param expiry                      if the reason this method is being called is to expire the element
     * @param notifyListeners             whether to notify listeners
     * @param doNotNotifyCacheReplicators whether not to notify cache replicators
     * @param useCacheWriter              if the element should else be removed from the cache writer
     * @return true if the element was removed, false if it was not found in the cache
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     */
    private boolean removeInternal(Object key, boolean expiry, boolean notifyListeners,
                           boolean doNotNotifyCacheReplicators, boolean useCacheWriter)
            throws IllegalStateException {

        if (useCacheWriter) {
            initialiseCacheWriterManager(true);
        }

        checkStatus();
        Element elementFromMemoryStore = null;
        Element elementFromDiskStore = null;

        if (useCacheWriter) {
            try {
                elementFromMemoryStore = memoryStore.removeWithWriter(key, cacheWriterManager);
            } catch (CacheWriterManagerException e) {
                if (configuration.getCacheWriterConfiguration().getNotifyListenersOnException()) {
                    notifyRemoveInternalListeners(key, expiry, notifyListeners, doNotNotifyCacheReplicators,
                            elementFromMemoryStore, elementFromDiskStore);
                }
                throw e.getCause();
            }
        } else {
            elementFromMemoryStore = memoryStore.remove(key);
        }

        // TODO check if it might not makes sense to reverse the memory store and disk store removal to have predictable
        // remove operations in case a CacheWriterManagerException occurs

        //could have been removed from both places, if there are two copies in the cache
        if (isDiskStore()) {
            if ((key instanceof Serializable)) {
                Serializable serializableKey = (Serializable) key;
                elementFromDiskStore = diskStore.remove(serializableKey);
            }

        }

        return notifyRemoveInternalListeners(key, expiry, notifyListeners, doNotNotifyCacheReplicators,
                elementFromMemoryStore, elementFromDiskStore);
    }

    private boolean notifyRemoveInternalListeners(Object key, boolean expiry, boolean notifyListeners, boolean doNotNotifyCacheReplicators,
                                                  Element elementFromMemoryStore, Element elementFromDiskStore) {
        boolean removed = false;
        boolean removeNotified = false;

        //Elements may be in both places. Always notify the MemoryStore version if there are two
        if (elementFromMemoryStore != null) {
            if (expiry) {
                //always notify expire which is lazy regardless of the removeQuiet
                registeredEventListeners.notifyElementExpiry(elementFromMemoryStore, doNotNotifyCacheReplicators);
            } else if (notifyListeners) {
                removeNotified = true;
                registeredEventListeners.notifyElementRemoved(elementFromMemoryStore, doNotNotifyCacheReplicators);
            }
            removed = true;
        } else if (elementFromDiskStore != null) {
            if (expiry) {
                registeredEventListeners.notifyElementExpiry(elementFromDiskStore, doNotNotifyCacheReplicators);
            } else if (notifyListeners) {
                removeNotified = true;
                registeredEventListeners.notifyElementRemoved(elementFromDiskStore, doNotNotifyCacheReplicators);
            }
            removed = true;
        }

        //If we are trying to remove an element which does not exist locally, we should still notify so that
        //cluster invalidations work.
        if (notifyListeners && !expiry && !removeNotified) {
            Element syntheticElement = new Element(key, null);
            registeredEventListeners.notifyElementRemoved(syntheticElement, doNotNotifyCacheReplicators);
        }

        return removed;
    }

    /**
     * Removes all cached items.
     * Synchronization is handled within the method.
     * <p/>
     * Caches which use synchronous replication can throw RemoteCacheException here if the replication to the cluster fails.
     * This exception should be caught in those circumstances.
     *
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     */
    public void removeAll() throws IllegalStateException, CacheException {
        removeAll(false);
    }


    /**
     * Removes all cached items.
     * Synchronization is handled within the method.
     * <p/>
     * Caches which use synchronous replication can throw RemoteCacheException here if the replication to the cluster fails.
     * This exception should be caught in those circumstances.
     *
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     */
    public void removeAll(boolean doNotNotifyCacheReplicators) throws IllegalStateException, CacheException {
        checkStatus();
        memoryStore.removeAll();
        if (isDiskStore()) {
            diskStore.removeAll();
        }
        registeredEventListeners.notifyRemoveAll(doNotNotifyCacheReplicators);
    }

    /**
     * Starts an orderly shutdown of the Cache. Steps are:
     * <ol>
     * <li>Completes any outstanding CacheLoader loads.
     * <li>Completes any outstanding CacheWriter operations.
     * <li>Disposes any cache extensions.
     * <li>Disposes any cache event listeners. The listeners normally complete, so for example distributed caching operations will complete.
     * <li>Flushes all cache items from memory to the disk store, if any
     * <li>changes status to shutdown, so that any cache operations after this point throw IllegalStateException
     * </ol>
     * This method should be invoked only by CacheManager, as a cache's lifecycle is bound into that of it's cache manager.
     *
     * @throws IllegalStateException if the cache is already {@link Status#STATUS_SHUTDOWN}
     */
    public synchronized void dispose() throws IllegalStateException {
        checkStatusNotDisposed();

        if (executorService != null) {
            executorService.shutdown();
        }

        disposeRegisteredCacheExtensions();
        disposeRegisteredCacheLoaders();
        disposeRegisteredCacheWriter();
        registeredEventListeners.dispose();

        if (cacheWriterManager != null) {
            cacheWriterManager.dispose();
        }

        if (memoryStore != null) {
            memoryStore.dispose();
        }
        if (diskStore != null) {
            diskStore.dispose();
        }
        changeStatus(Status.STATUS_SHUTDOWN);
    }

    private void initialiseRegisteredCacheExtensions() {
        for (CacheExtension cacheExtension : registeredCacheExtensions) {
            cacheExtension.init();
        }
    }

    private void disposeRegisteredCacheExtensions() {
        for (CacheExtension cacheExtension : registeredCacheExtensions) {
            cacheExtension.dispose();
        }
    }

    private void initialiseRegisteredCacheLoaders() {
        for (CacheLoader cacheLoader : registeredCacheLoaders) {
            cacheLoader.init();
        }
    }

    private void disposeRegisteredCacheLoaders() {
        for (CacheLoader cacheLoader : registeredCacheLoaders) {
            cacheLoader.dispose();
        }
    }

    private void initialiseRegisteredCacheWriter() {
        CacheWriter writer = registeredCacheWriter;
        if (writer != null) {
            writer.init();
        }
    }

    private void disposeRegisteredCacheWriter() {
        CacheWriter writer = registeredCacheWriter;
        if (writer != null) {
            writer.dispose();
        }
    }

    /**
     * Gets the cache configuration this cache was created with.
     * <p/>
     * Things like listeners that are added dynamically are excluded.
     */
    public CacheConfiguration getCacheConfiguration() {
        return configuration;
    }


    /**
     * Flushes all cache items from memory to the disk store, and from the DiskStore to disk.
     *
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     */
    public final synchronized void flush() throws IllegalStateException, CacheException {
        checkStatus();
        try {
            memoryStore.flush();
            if (isDiskStore()) {
                diskStore.flush();
            }
        } catch (IOException e) {
            throw new CacheException("Unable to flush cache: " + configuration.getName()
                    + ". Initial cause was " + e.getMessage(), e);
        }
    }

    /**
     * Gets the size of the cache. This is a subtle concept. See below.
     * <p/>
     * The size is the number of {@link Element}s in the {@link MemoryStore}
     * plus the number of {@link Element}s in the {@link DiskStore}. However, if
     * the cache is Terracotta clustered, the underlying store has a coherent
     * view of the all the elements in the cache and doesn't have to be
     * aggregated from an underlying {@code MemoryStore} and {@code DiskStore}.
     * <p/>
     * This number is the actual number of elements, including expired elements
     * that have not been removed.
     * <p/>
     * Expired elements are removed from the the memory store when getting an
     * expired element, or when attempting to spool an expired element to disk.
     * <p/>
     * Expired elements are removed from the disk store when getting an expired
     * element, or when the expiry thread runs, which is once every five
     * minutes.
     * <p/>
     * To get an exact size, which would exclude expired elements, use
     * {@link #getKeysWithExpiryCheck()}.size(), although see that method for
     * the approximate time that would take.
     * <p/>
     * To get a very fast result, use {@link #getKeysNoDuplicateCheck()}.size().
     * If the disk store is being used, there will be some duplicates.
     *
     * @return The size value
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     */
    public final int getSize() throws IllegalStateException, CacheException {
        checkStatus();

        if (memoryStore.isCacheCoherent()) {
            return memoryStore.getTerracottaClusteredSize();
        } else {
            /* The memory store and the disk store can simultaneously contain elements with the same key
                Cache size is the size of the union of the two key sets.*/
            return getKeys().size();
        }
    }

    /**
     * {@inheritDoc}
     */
    public int getSizeBasedOnAccuracy(int statisticsAccuracy)
            throws IllegalStateException, CacheException {
        if (statisticsAccuracy == Statistics.STATISTICS_ACCURACY_BEST_EFFORT) {
            return getSize();
        } else if (statisticsAccuracy == Statistics.STATISTICS_ACCURACY_GUARANTEED) {
            return getKeysWithExpiryCheck().size();
        } else if (statisticsAccuracy == Statistics.STATISTICS_ACCURACY_NONE) {
            return getKeysNoDuplicateCheck().size();
        }
        throw new IllegalArgumentException("Unknown statistics accuracy: "
                + statisticsAccuracy);
    }

    /**
     * Gets the size of the memory store for this cache. This method relies on calculating
     * Serialized sizes. If the Element values are not Serializable they will show as zero.
     * <p/>
     * Warning: This method can be very expensive to run. Allow approximately 1 second
     * per 1MB of entries. Running this method could create liveness problems
     * because the object lock is held for a long period
     * <p/>
     *
     * @return the approximate size of the memory store in bytes
     * @throws IllegalStateException
     */
    public final long calculateInMemorySize() throws IllegalStateException, CacheException {
        checkStatus();
        return memoryStore.getSizeInBytes();
    }


    /**
     * Returns the number of elements in the memory store.
     *
     * @return the number of elements in the memory store
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     */
    public final long getMemoryStoreSize() throws IllegalStateException {
        checkStatus();
        return memoryStore.getSize();
    }

    /**
     * Returns the number of elements in the disk store.
     *
     * @return the number of elements in the disk store.
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     */
    public final int getDiskStoreSize() throws IllegalStateException {
        checkStatus();
        if (isTerracottaClustered()) {
            return memoryStore.getTerracottaClusteredSize();
        }
        if (isDiskStore()) {
            return diskStore.getSize();
        } else {
            return 0;
        }
    }

    /**
     * Gets the status attribute of the Cache.
     *
     * @return The status value from the Status enum class
     */
    public final Status getStatus() {
        return status;
    }


    private void checkStatus() throws IllegalStateException {
        if (!status.equals(Status.STATUS_ALIVE)) {
            throw new IllegalStateException("The " + configuration.getName() + " Cache is not alive.");
        }
    }

    private void checkStatusNotDisposed() throws IllegalStateException {
        if (status.equals(Status.STATUS_SHUTDOWN)) {
            throw new IllegalStateException("The " + configuration.getName() + " Cache is disposed.");
        }
    }


    /**
     * Gets the cache name.
     */
    public final String getName() {
        return configuration.getName();
    }

    /**
     * Sets the cache name which will name.
     *
     * @param name the name of the cache. Should not be null. Should also not contain any '/' characters, as these interfere
     *             with distribution
     * @throws IllegalArgumentException if an illegal name is used.
     */
    public final void setName(String name) throws IllegalArgumentException {
        if (!status.equals(Status.STATUS_UNINITIALISED)) {
            throw new IllegalStateException("Only uninitialised caches can have their names set.");
        }
        configuration.setName(name);
    }

    /**
     * Returns a {@link String} representation of {@link Cache}.
     */
    @Override
    public String toString() {
        StringBuilder dump = new StringBuilder();

        dump.append("[")
                .append(" name = ").append(configuration.getName())
                .append(" status = ").append(status)
                .append(" eternal = ").append(configuration.isEternal())
                .append(" overflowToDisk = ").append(configuration.isOverflowToDisk())
                .append(" maxElementsInMemory = ").append(configuration.getMaxElementsInMemory())
                .append(" maxElementsOnDisk = ").append(configuration.getMaxElementsOnDisk())
                .append(" memoryStoreEvictionPolicy = ").append(configuration.getMemoryStoreEvictionPolicy())
                .append(" timeToLiveSeconds = ").append(configuration.getTimeToLiveSeconds())
                .append(" timeToIdleSeconds = ").append(configuration.getTimeToIdleSeconds())
                .append(" diskPersistent = ").append(configuration.isDiskPersistent())
                .append(" diskExpiryThreadIntervalSeconds = ").append(configuration.getDiskExpiryThreadIntervalSeconds())
                .append(registeredEventListeners)
                .append(" hitCount = ").append(getLiveCacheStatisticsNoCheck().getCacheHitCount())
                .append(" memoryStoreHitCount = ").append(getLiveCacheStatisticsNoCheck().getInMemoryHitCount())
                .append(" diskStoreHitCount = ").append(getLiveCacheStatisticsNoCheck().getOnDiskHitCount())
                .append(" missCountNotFound = ").append(getLiveCacheStatisticsNoCheck().getCacheMissCount())
                .append(" missCountExpired = ").append(getLiveCacheStatisticsNoCheck().getCacheMissCountExpired())
                .append(" ]");

        return dump.toString();
    }


    /**
     * Checks whether this cache element has expired.
     * <p/>
     * The element is expired if:
     * <ol>
     * <li> the idle time is non-zero and has elapsed, unless the cache is eternal; or
     * <li> the time to live is non-zero and has elapsed, unless the cache is eternal; or
     * <li> the value of the element is null.
     * </ol>
     *
     * @return true if it has expired
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     * @throws NullPointerException  if the element is null
     *                               todo this does not need to be synchronized
     */
    public final boolean isExpired(Element element) throws IllegalStateException, NullPointerException {
        checkStatus();
        return element.isExpired(configuration);
    }


    /**
     * Clones a cache. This is only legal if the cache has not been
     * initialized. At that point only primitives have been set and no
     * stores have been created.
     * <p/>
     * A new, empty, RegisteredEventListeners is created on clone.
     * <p/>
     *
     * @return an object of type {@link Cache}
     * @throws CloneNotSupportedException
     */
    @Override
    public final Cache clone() throws CloneNotSupportedException {
        if (!(memoryStore == null && diskStore == null)) {
            throw new CloneNotSupportedException("Cannot clone an initialized cache.");
        }
        Cache copy = (Cache) super.clone();
        // create new copies of the statistics
        copy.liveCacheStatisticsData = new LiveCacheStatisticsWrapper(copy);
        copy.sampledCacheStatistics = new SampledCacheStatisticsWrapper();

        copy.configuration = configuration.clone();
        copy.guid = createGuid();

        RegisteredEventListeners registeredEventListenersFromCopy = copy.getCacheEventNotificationService();
        if (registeredEventListenersFromCopy == null || registeredEventListenersFromCopy.getCacheEventListeners().size() == 0) {
            copy.registeredEventListeners = new RegisteredEventListeners(copy);
        } else {
            copy.registeredEventListeners = new RegisteredEventListeners(copy);
            Set cacheEventListeners = registeredEventListeners.getCacheEventListeners();
            for (Object cacheEventListener1 : cacheEventListeners) {
                CacheEventListener cacheEventListener = (CacheEventListener) cacheEventListener1;
                CacheEventListener cacheEventListenerClone = (CacheEventListener) cacheEventListener.clone();
                copy.registeredEventListeners.registerListener(cacheEventListenerClone);
            }
        }


        copy.registeredCacheExtensions = new CopyOnWriteArrayList<CacheExtension>();
        for (CacheExtension registeredCacheExtension : registeredCacheExtensions) {
            copy.registerCacheExtension(registeredCacheExtension.clone(copy));
        }

        copy.registeredCacheLoaders = new CopyOnWriteArrayList<CacheLoader>();
        for (CacheLoader registeredCacheLoader : registeredCacheLoaders) {
            copy.registerCacheLoader(registeredCacheLoader.clone(copy));
        }

        if (registeredCacheWriter != null) {
            copy.registerCacheWriter(registeredCacheWriter.clone(copy));
        }

        if (bootstrapCacheLoader != null) {
            BootstrapCacheLoader bootstrapCacheLoaderClone = (BootstrapCacheLoader) bootstrapCacheLoader.clone();
            copy.setBootstrapCacheLoader(bootstrapCacheLoaderClone);
        }

        return copy;
    }

    /**
     * Gets the internal DiskStore.
     *
     * @return the DiskStore referenced by this cache
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     */
    final Store getDiskStore() throws IllegalStateException {
        checkStatus();
        return diskStore;
    }

    /**
     * Gets the internal MemoryStore.
     *
     * @return the MemoryStore referenced by this cache
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     */
    final Store getMemoryStore() throws IllegalStateException {
        checkStatus();
        return memoryStore;
    }


    /**
     * Use this to access the service in order to register and unregister listeners
     *
     * @return the RegisteredEventListeners instance for this cache.
     */
    public final RegisteredEventListeners getCacheEventNotificationService() {
        return registeredEventListeners;
    }


    /**
     * Whether an Element is stored in the cache in Memory, indicating a very low cost of retrieval.
     *
     * @return true if an element matching the key is found in memory
     */
    public final boolean isElementInMemory(Serializable key) {
        return isElementInMemory((Object) key);
    }

    /**
     * Whether an Element is stored in the cache in Memory, indicating a very low cost of retrieval.
     *
     * @return true if an element matching the key is found in memory
     * @since 1.2
     */
    public final boolean isElementInMemory(Object key) {
        return memoryStore.containsKey(key);
    }

    /**
     * Whether an Element is stored in the cache on Disk, indicating a higher cost of retrieval.
     *
     * @return true if an element matching the key is found in the diskStore
     */
    public final boolean isElementOnDisk(Serializable key) {
        return isElementOnDisk((Object) key);
    }

    /**
     * Whether an Element is stored in the cache on Disk, indicating a higher cost of retrieval.
     *
     * @return true if an element matching the key is found in the diskStore
     * @since 1.2
     */
    public final boolean isElementOnDisk(Object key) {
        if (!(key instanceof Serializable)) {
            return false;
        }
        Serializable serializableKey = (Serializable) key;
        if (isDiskStore()) {
            return diskStore != null && diskStore.containsKey(serializableKey);
        } else {
            return false;
        }
    }

    /**
     * The GUID for this cache instance can be used to determine whether two cache instance references
     * are pointing to the same cache.
     *
     * @return the globally unique identifier for this cache instance. This is guaranteed to be unique.
     * @since 1.2
     */
    public final String getGuid() {
        return guid;
    }

    /**
     * Gets the CacheManager managing this cache. For a newly created cache this will be null until
     * it has been added to a CacheManager.
     *
     * @return the manager or null if there is none
     */
    public final CacheManager getCacheManager() {
        return cacheManager;
    }


    /**
     * Resets statistics counters back to 0.
     *
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     */
    public void clearStatistics() throws IllegalStateException {
        checkStatus();
        liveCacheStatisticsData.clearStatistics();
        sampledCacheStatistics.clearStatistics();
        registeredEventListeners.clearCounters();
    }

    /**
     * Accurately measuring statistics can be expensive. Returns the current accuracy setting.
     *
     * @return one of {@link Statistics#STATISTICS_ACCURACY_BEST_EFFORT}, {@link Statistics#STATISTICS_ACCURACY_GUARANTEED}, {@link Statistics#STATISTICS_ACCURACY_NONE}
     */
    public int getStatisticsAccuracy() {
        return getLiveCacheStatistics().getStatisticsAccuracy();
    }

    /**
     * Sets the statistics accuracy.
     *
     * @param statisticsAccuracy one of {@link Statistics#STATISTICS_ACCURACY_BEST_EFFORT}, {@link Statistics#STATISTICS_ACCURACY_GUARANTEED}, {@link Statistics#STATISTICS_ACCURACY_NONE}
     */
    public void setStatisticsAccuracy(int statisticsAccuracy) {
        liveCacheStatisticsData.setStatisticsAccuracy(statisticsAccuracy);
    }

    /**
     * Causes all elements stored in the Cache to be synchronously checked for expiry, and if expired, evicted.
     */
    public void evictExpiredElements() {
        Object[] keys = memoryStore.getKeyArray();

        for (Object key : keys) {
            searchInMemoryStoreWithoutStats(key, true, true);
        }

        //This is called regularly by the expiry thread, but call it here synchronously
        if (isDiskStore()) {
            diskStore.expireElements();
        }
    }

    /**
     * An inexpensive check to see if the key exists in the cache.
     * <p/>
     * This method is not synchronized. It is possible that an element may exist in the cache and be removed
     * before the check gets to it, or vice versa.
     *
     * @param key the key to check.
     * @return true if an Element matching the key is found in the cache. No assertions are made about the state of the Element.
     */
    public boolean isKeyInCache(Object key) {
        if (key == null) {
            return false;
        }
        return isElementInMemory(key) || isElementOnDisk(key);
    }

    /**
     * An extremely expensive check to see if the value exists in the cache. This implementation is O(n). Ehcache
     * is not designed for efficient access in this manner.
     * <p/>
     * This method is not synchronized. It is possible that an element may exist in the cache and be removed
     * before the check gets to it, or vice versa. Because it is slow to execute the probability of that this will
     * have happened.
     *
     * @param value to check for
     * @return true if an Element matching the key is found in the cache. No assertions are made about the state of the Element.
     */
    public boolean isValueInCache(Object value) {
        boolean isSerializable = value instanceof Serializable;
        List keys;
        if (isSerializable) {
            keys = getKeys();
        } else {
            keys = Arrays.asList(memoryStore.getKeyArray());
        }

        for (Object key : keys) {
            Element element = get(key);
            if (element != null) {
                Object elementValue = element.getValue();
                if (elementValue == null) {
                    if (value == null) {
                        return true;
                    }
                } else {
                    if (elementValue.equals(value)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * {@inheritDoc}
     * <p/>
     * Note, the {@link #getSize} method will have the same value as the size
     * reported by Statistics for the statistics accuracy of
     * {@link Statistics#STATISTICS_ACCURACY_BEST_EFFORT}.
     */
    public Statistics getStatistics() throws IllegalStateException {
        int size = getSizeBasedOnAccuracy(getLiveCacheStatistics()
                .getStatisticsAccuracy());
        return new Statistics(this, getLiveCacheStatistics()
                .getStatisticsAccuracy(), getLiveCacheStatistics()
                .getCacheHitCount(), getLiveCacheStatistics()
                .getOnDiskHitCount(), getLiveCacheStatistics()
                .getInMemoryHitCount(), getLiveCacheStatistics()
                .getCacheMissCount(), size, getAverageGetTime(),
                getLiveCacheStatistics().getEvictedCount(),
                getMemoryStoreSize(), getDiskStoreSize());
    }

    /**
     * For use by CacheManager.
     *
     * @param cacheManager the CacheManager for this cache to use.
     */
    public void setCacheManager(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /**
     * Accessor for the BootstrapCacheLoader associated with this cache. For testing purposes.
     */
    public BootstrapCacheLoader getBootstrapCacheLoader() {
        return bootstrapCacheLoader;
    }

    /**
     * Sets the bootstrap cache loader.
     *
     * @param bootstrapCacheLoader the loader to be used
     * @throws CacheException if this method is called after the cache is initialized
     */
    public void setBootstrapCacheLoader(BootstrapCacheLoader bootstrapCacheLoader) throws CacheException {
        if (!status.equals(Status.STATUS_UNINITIALISED)) {
            throw new CacheException("A bootstrap cache loader can only be set before the cache is initialized. "
                    + configuration.getName());
        }
        this.bootstrapCacheLoader = bootstrapCacheLoader;
    }

    /**
     * DiskStore paths can conflict between CacheManager instances. This method allows the path to be changed.
     *
     * @param diskStorePath the new path to be used.
     * @throws CacheException if this method is called after the cache is initialized
     */
    public void setDiskStorePath(String diskStorePath) throws CacheException {
        if (!status.equals(Status.STATUS_UNINITIALISED)) {
            throw new CacheException("A DiskStore path can only be set before the cache is initialized. "
                    + configuration.getName());
        }
        this.diskStorePath = diskStorePath;
    }

    /**
     * An equals method which follows the contract of {@link Object#equals(Object)}
     * <p/>
     * An Cache is equal to another one if it implements Ehcache and has the same GUID.
     *
     * @param object the reference object with which to compare.
     * @return <code>true</code> if this object is the same as the obj
     *         argument; <code>false</code> otherwise.
     * @see #hashCode()
     * @see java.util.Hashtable
     */
    @Override
    public boolean equals(Object object) {
        if (object == null) {
            return false;
        }
        if (!(object instanceof Ehcache)) {
            return false;
        }
        Ehcache other = (Ehcache) object;
        return guid.equals(other.getGuid());
    }

    /**
     * Returns a hash code value for the object. This method is
     * supported for the benefit of hashtables such as those provided by
     * <code>java.util.Hashtable</code>.
     * <p/>
     * The general contract of <code>hashCode</code> is:
     * <ul>
     * <li>Whenever it is invoked on the same object more than once during
     * an execution of a Java application, the <tt>hashCode</tt> method
     * must consistently return the same integer, provided no information
     * used in <tt>equals</tt> comparisons on the object is modified.
     * This integer need not remain consistent from one execution of an
     * application to another execution of the same application.
     * <li>If two objects are equal according to the <tt>equals(Object)</tt>
     * method, then calling the <code>hashCode</code> method on each of
     * the two objects must produce the same integer result.
     * <li>It is <em>not</em> required that if two objects are unequal
     * according to the {@link Object#equals(Object)}
     * method, then calling the <tt>hashCode</tt> method on each of the
     * two objects must produce distinct integer results.  However, the
     * programmer should be aware that producing distinct integer results
     * for unequal objects may improve the performance of hashtables.
     * </ul>
     * <p/>
     * As much as is reasonably practical, the hashCode method defined by
     * class <tt>Object</tt> does return distinct integers for distinct
     * objects. (This is typically implemented by converting the internal
     * address of the object into an integer, but this implementation
     * technique is not required by the
     * Java<font size="-2"><sup>TM</sup></font> programming language.)
     * <p/>
     * This implementation use the GUID of the cache.
     *
     * @return a hash code value for this object.
     * @see Object#equals(Object)
     * @see java.util.Hashtable
     */
    @Override
    public int hashCode() {
        return guid.hashCode();
    }


    /**
     * Create globally unique ID for this cache.
     */
    private String createGuid() {
        StringBuilder buffer = new StringBuilder().append(localhost).append("-").append(UUID.randomUUID());
        return buffer.toString();
    }

    /**
     * Register a {@link CacheExtension} with the cache. It will then be tied into the cache lifecycle.
     * <p/>
     * If the CacheExtension is not initialised, initialise it.
     */
    public void registerCacheExtension(CacheExtension cacheExtension) {
        registeredCacheExtensions.add(cacheExtension);
    }

    /**
     * @return the cache extensions as a live list
     */
    public List<CacheExtension> getRegisteredCacheExtensions() {
        return registeredCacheExtensions;
    }


    /**
     * Unregister a {@link CacheExtension} with the cache. It will then be detached from the cache lifecycle.
     */
    public void unregisterCacheExtension(CacheExtension cacheExtension) {
        cacheExtension.dispose();
        registeredCacheExtensions.remove(cacheExtension);
    }


    /**
     * The average get time in ms.
     */
    public float getAverageGetTime() {
        return getLiveCacheStatistics().getAverageGetTimeMillis();
    }

    /**
     * Sets an ExceptionHandler on the Cache. If one is already set, it is overwritten.
     * <p/>
     * The ExceptionHandler is only used if this Cache's methods are accessed using
     * {@link net.sf.ehcache.exceptionhandler.ExceptionHandlingDynamicCacheProxy}.
     *
     * @see net.sf.ehcache.exceptionhandler.ExceptionHandlingDynamicCacheProxy
     */
    public void setCacheExceptionHandler(CacheExceptionHandler cacheExceptionHandler) {
        this.cacheExceptionHandler = cacheExceptionHandler;
    }

    /**
     * Gets the ExceptionHandler on this Cache, or null if there isn't one.
     * <p/>
     * The ExceptionHandler is only used if this Cache's methods are accessed using
     * {@link net.sf.ehcache.exceptionhandler.ExceptionHandlingDynamicCacheProxy}.
     *
     * @see net.sf.ehcache.exceptionhandler.ExceptionHandlingDynamicCacheProxy
     */
    public CacheExceptionHandler getCacheExceptionHandler() {
        return cacheExceptionHandler;
    }

    /**
     * Register a {@link CacheLoader} with the cache. It will then be tied into the cache lifecycle.
     * <p/>
     * If the CacheLoader is not initialised, initialise it.
     *
     * @param cacheLoader A Cache Loader to register
     */
    public void registerCacheLoader(CacheLoader cacheLoader) {
        registeredCacheLoaders.add(cacheLoader);
    }

    /**
     * Unregister a {@link CacheLoader} with the cache. It will then be detached from the cache lifecycle.
     *
     * @param cacheLoader A Cache Loader to unregister
     */
    public void unregisterCacheLoader(CacheLoader cacheLoader) {
        registeredCacheLoaders.remove(cacheLoader);
    }


    /**
     * @return the cache loaders as a live list
     */
    public List<CacheLoader> getRegisteredCacheLoaders() {
        return registeredCacheLoaders;
    }

    /**
     * {@inheritDoc}
     */
    public void registerCacheWriter(CacheWriter cacheWriter) {
        this.registeredCacheWriter = cacheWriter;
        initialiseCacheWriterManager(false);
    }

    /**
     * {@inheritDoc}
     */
    public void unregisterCacheWriter() {
        if (cacheWriterManagerInitFlag.get()) {
            throw new CacheException("Cache: " + configuration.getName() + " has its cache writer being unregistered " +
                    "after it was already initialised.");
        }
        this.registeredCacheWriter = null;
    }

    /**
     * {@inheritDoc}
     */
    public CacheWriter getRegisteredCacheWriter() {
        return this.registeredCacheWriter;
    }

    /**
     * Does the asynchronous loading.
     *
     * @param key
     * @param specificLoader a specific loader to use. If null the default loader is used.
     * @param argument
     * @return a Future which can be used to monitor execution
     */
    Future asynchronousLoad(final Object key, final CacheLoader specificLoader, final Object argument) {
        return getExecutorService().submit(new Runnable() {

            /**
             * Calls the CacheLoader and puts the result in the Cache
             */
            public void run() throws CacheException {
                try {
                    //Test to see if it has turned up in the meantime
                    boolean existsOnRun = isKeyInCache(key);
                    if (!existsOnRun) {
                        Object value;
                        if (specificLoader == null) {
                            if (registeredCacheLoaders.size() == 0) {
                                return;
                            }
                            value = loadWithRegisteredLoaders(argument, key);
                        } else {
                            if (argument == null) {
                                value = specificLoader.load(key);
                            } else {
                                value = specificLoader.load(key, argument);
                            }
                        }
                        if (value != null) {
                            put(new Element(key, value), false);
                        }
                    }
                } catch (Throwable e) {
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("Problem during load. Load will not be completed. Cause was " + e.getCause(), e);
                    }
                    throw new CacheException("Problem during load. Load will not be completed. Cause was " + e.getCause(), e);
                }
            }
        });
    }

    private Object loadWithRegisteredLoaders(Object argument, Object key) throws CacheException {

        Object value = null;

        if (argument == null) {
            for (CacheLoader registeredCacheLoader : registeredCacheLoaders) {
                value = registeredCacheLoader.load(key);
                if (value != null) {
                    break;
                }
            }
        } else {
            for (CacheLoader registeredCacheLoader : registeredCacheLoaders) {
                value = registeredCacheLoader.load(key, argument);
                if (value != null) {
                    break;
                }
            }
        }
        return value;
    }


    /**
     * Creates a future to perform the load
     *
     * @param keys
     * @param argument the loader argument
     * @return a Future which can be used to monitor execution
     */
    Future asynchronousLoadAll(final Collection keys, final Object argument) {
        return getExecutorService().submit(new Runnable() {
            /**
             * Calls the CacheLoader and puts the result in the Cache
             */
            public void run() {
                try {
                    Set<Object> nonLoadedKeys = new HashSet<Object>();
                    for (Object key : keys) {
                        if (!isKeyInCache(key)) {
                            nonLoadedKeys.add(key);
                        }
                    }
                    Map map = loadWithRegisteredLoaders(argument, nonLoadedKeys);
                    for (Object key : map.keySet()) {
                        put(new Element(key, map.get(key)));
                    }
                } catch (Throwable e) {
                    if (LOG.isErrorEnabled()) {
                        LOG.error("Problem during load. Load will not be completed. Cause was " + e.getCause(), e);
                    }
                }
            }
        });
    }

    /**
     * Does the asynchronous loading.
     *
     * @param argument      the loader argument
     * @param nonLoadedKeys the Set of keys that are already in the Cache
     * @return A map of loaded elements
     */
    Map loadWithRegisteredLoaders(Object argument, Set<Object> nonLoadedKeys) {
        Map result = new HashMap();
        for (CacheLoader registeredCacheLoader : registeredCacheLoaders) {
            if (nonLoadedKeys.isEmpty()) {
                break;
            }

            Map resultForThisCacheLoader = null;
            if (argument == null) {
                resultForThisCacheLoader = registeredCacheLoader.loadAll(nonLoadedKeys);
            } else {
                resultForThisCacheLoader = registeredCacheLoader.loadAll(nonLoadedKeys, argument);
            }
            if (resultForThisCacheLoader != null) {
                nonLoadedKeys.removeAll(resultForThisCacheLoader.keySet());
                result.putAll(resultForThisCacheLoader);
            }
        }
        return result;
    }

    /**
     * @return Gets the executor service. This is not publically accessible.
     */
    ExecutorService getExecutorService() {
        if (executorService == null) {
            synchronized (this) {
                boolean inGoogleAppEngine;

                try {
                    Class.forName("com.google.apphosting.api.DeadlineExceededException");
                    inGoogleAppEngine = true;
                } catch (ClassNotFoundException cnfe) {
                    inGoogleAppEngine = false;
                }

                if (inGoogleAppEngine) {

                    // no Thread support. Run all tasks on the caller thread
                    executorService = new AbstractExecutorService() {
                        /** {@inheritDoc} */
                        public void execute(Runnable command) {
                            command.run();
                        }

                        /** {@inheritDoc} */
                        public List<Runnable> shutdownNow() {
                            return Collections.emptyList();
                        }

                        /** {@inheritDoc} */
                        public void shutdown() {
                        }

                        /** {@inheritDoc} */
                        public boolean isTerminated() {
                            return isShutdown();
                        }

                        /** {@inheritDoc} */
                        public boolean isShutdown() {
                            return false;
                        }

                        /** {@inheritDoc} */
                        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                            return true;
                        }
                    };
                } else {
                    // we can create Threads
                    executorService = new ThreadPoolExecutor(EXECUTOR_CORE_POOL_SIZE, EXECUTOR_MAXIMUM_POOL_SIZE, EXECUTOR_KEEP_ALIVE_TIME,
                            TimeUnit.MILLISECONDS, new LinkedBlockingQueue(), new NamedThreadFactory("Cache Executor Service"));
                }
            }
        }
        return executorService;
    }


    /**
     * Whether this cache is disabled. "Disabled" means:
     * <ol>
     * <li>bootstrap is disabled</li>
     * <li>puts are discarded</li>
     * <li>putQuiets are discarded</li>
     * <li>gets return null</li>
     * </ol>
     * In all other respects the cache continues as it is.
     * <p/>
     * You can disable and enable a cache programmatically through the {@link #setDisabled(boolean)} method.
     * <p/>
     * By default caches are enabled on creation, unless the <code>net.sf.ehcache.disabled</code> system
     * property is set.
     *
     * @return true if the cache is disabled.
     * @see #NET_SF_EHCACHE_DISABLED ?
     */
    public boolean isDisabled() {
        return disabled;
    }

    /**
     * Disables or enables this cache. This call overrides the previous value of disabled, even if the
     * <code>net.sf.ehcache.disabled</code> system property is set
     * <p/>
     *
     * @param disabled true if you wish to disable, false to enable
     * @see #isDisabled()
     */
    public void setDisabled(boolean disabled) {
        if (allowDisable) {
            this.disabled = disabled;
        } else {
            throw new CacheException("Dynamic cache features are disabled");
        }
    }

    /**
     * @return the current MemoryStore policy. This may not be the configured policy, if it has been
     *         dynamically set.
     */
    public Policy getMemoryStoreEvictionPolicy() {
        return memoryStore.getEvictionPolicy();
    }

    /**
     * Sets the eviction policy strategy. The Cache will use a policy at startup. There
     * are three policies which can be configured: LRU, LFU and FIFO. However many other
     * policies are possible. That the policy has access to the whole element enables policies
     * based on the key, value, metadata, statistics, or a combination of any of the above.
     * It is safe to change the policy of a store at any time. The new policy takes effect
     * immediately.
     *
     * @param policy the new policy
     */
    public void setMemoryStoreEvictionPolicy(Policy policy) {
        memoryStore.setEvictionPolicy(policy);
    }

    /**
     * {@inheritDoc}
     */
    public LiveCacheStatistics getLiveCacheStatistics()
            throws IllegalStateException {
        checkStatus();
        return liveCacheStatisticsData;
    }

    private LiveCacheStatistics getLiveCacheStatisticsNoCheck() {
        return liveCacheStatisticsData;
    }

    /**
     * {@inheritDoc}
     */
    public void registerCacheUsageListener(CacheUsageListener cacheUsageListener)
            throws IllegalStateException {
        checkStatus();
        liveCacheStatisticsData.registerCacheUsageListener(cacheUsageListener);
    }

    /**
     * {@inheritDoc}
     */
    public void removeCacheUsageListener(CacheUsageListener cacheUsageListener)
            throws IllegalStateException {
        checkStatus();
        liveCacheStatisticsData.removeCacheUsageListener(cacheUsageListener);
    }

    /**
     * {@inheritDoc}
     */
    public boolean isStatisticsEnabled() {
        return getLiveCacheStatistics().isStatisticsEnabled();
    }

    /**
     * {@inheritDoc}
     */
    public void setStatisticsEnabled(boolean enableStatistics) {
        liveCacheStatisticsData.setStatisticsEnabled(enableStatistics);
        if (!enableStatistics) {
            setSampledStatisticsEnabled(false);
        }
    }

    /**
     * {@inheritDoc}
     */
    public SampledCacheStatistics getSampledCacheStatistics() {
        return sampledCacheStatistics;
    }

    /**
     * {@inheritDoc}
     */
    public void setSampledStatisticsEnabled(final boolean enableStatistics) {
        if (cacheManager == null) {
            throw new IllegalStateException(
                    "You must add the cache to a CacheManager before enabling/disabling sampled statistics.");
        }
        if (enableStatistics) {
            setStatisticsEnabled(true);
            sampledCacheStatistics.enableSampledStatistics(cacheManager.getTimer());
        } else {
            sampledCacheStatistics.disableSampledStatistics();
        }
    }

    /**
     * {@inheritDoc}
     *
     * @see net.sf.ehcache.Ehcache#isSampledStatisticsEnabled()
     */
    public boolean isSampledStatisticsEnabled() {
        return sampledCacheStatistics.isSampledStatisticsEnabled();
    }

    /**
     * {@inheritDoc}
     */
    public Object getInternalContext() {
        return memoryStore.getInternalContext();
    }

    /**
     * {@inheritDoc}
     */
    public void disableDynamicFeatures() {
        configuration.freezeConfiguration();
        allowDisable = false;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isClusterCoherent() {
        return memoryStore.isClusterCoherent();
    }

    /**
     * {@inheritDoc}
     */
    public boolean isNodeCoherent() {
        return memoryStore.isNodeCoherent();
    }

    /**
     * {@inheritDoc}
     */
    public void setNodeCoherent(boolean coherent) {
        memoryStore.setNodeCoherent(coherent);
    }

    /**
     * {@inheritDoc}
     */
    public void waitUntilClusterCoherent() {
        memoryStore.waitUntilClusterCoherent();
    }
}
