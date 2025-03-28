/**
 *  Copyright 2003-2006 Greg Luck
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

package net.sf.ehcache.config;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.CacheException;
import net.sf.ehcache.distribution.CacheManagerPeerProviderFactory;
import net.sf.ehcache.distribution.CacheManagerPeerProvider;
import net.sf.ehcache.distribution.CacheManagerPeerListener;
import net.sf.ehcache.distribution.CacheManagerPeerListenerFactory;
import net.sf.ehcache.event.CacheEventListener;
import net.sf.ehcache.event.CacheEventListenerFactory;
import net.sf.ehcache.event.RegisteredEventListeners;
import net.sf.ehcache.event.CacheManagerEventListener;
import net.sf.ehcache.event.CacheManagerEventListenerFactory;
import net.sf.ehcache.util.ClassLoaderUtil;
import net.sf.ehcache.util.PropertyUtil;

import java.util.Properties;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.Iterator;
import java.util.HashSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * The configuration for ehcache.
 * <p/>
 * This class can be populated through:
 * <ul>
 * <li>introspection by {@link ConfigurationFactory} or
 * <li>programmatically
 * </ul>
 *
 * @author Greg Luck
 * @version $Id$
 */
public class ConfigurationHelper {

    private static final Log LOG = LogFactory.getLog(ConfigurationHelper.class.getName());

    private Configuration configuration;
    private CacheManager cacheManager;

    /**
     * Only Constructor
     *
     * @param cacheManager
     * @param configuration
     */
    public ConfigurationHelper(CacheManager cacheManager, Configuration configuration) {
        if (cacheManager == null || configuration == null) {
            throw new IllegalArgumentException("Cannot have null parameters");
        }
        this.cacheManager = cacheManager;
        this.configuration = configuration;
    }


    /**
     * A factory method to create a RegisteredEventListeners
     */
    protected void registerCacheListeners(Cache cache, CacheConfiguration cacheConfiguration,
                                          RegisteredEventListeners registeredEventListeners) {
        List cacheEventListenerConfigurations = cacheConfiguration.cacheEventListenerConfigurations;
        for (int i = 0; i < cacheEventListenerConfigurations.size(); i++) {
            CacheConfiguration.CacheEventListenerFactoryConfiguration factoryConfiguration =
                    (CacheConfiguration.CacheEventListenerFactoryConfiguration) cacheEventListenerConfigurations.get(i);
            CacheEventListener cacheEventListener = createCacheEventListener(factoryConfiguration);
            registeredEventListeners.registerListener(cacheEventListener);
        }
    }


    /**
     * Tries to load the class specified otherwise defaults to null
     *
     * @param factoryConfiguration
     */
    private CacheEventListener createCacheEventListener(
            CacheConfiguration.CacheEventListenerFactoryConfiguration factoryConfiguration) {
        String className = null;
        CacheEventListener cacheEventListener = null;
        try {
            className = factoryConfiguration.getFullyQualifiedClassPath();
        } catch (Throwable t) {
            //
        }
        if (className == null) {
            LOG.error("CacheEventListener factory not configured. Skipping listener configuration");
        } else {
            CacheEventListenerFactory factory = (CacheEventListenerFactory)
                    ClassLoaderUtil.createNewInstance(className);
            Properties properties = PropertyUtil.parseProperties(factoryConfiguration.getProperties());
            cacheEventListener = factory.createCacheEventListener(properties);
        }
        return cacheEventListener;
    }

    /**
     * Tries to load the class specified otherwise defaults to null
     */
    public CacheManagerPeerProvider createCachePeerProvider() {
        String className = null;
        FactoryConfiguration cachePeerProviderFactoryConfiguration =
                configuration.getCacheManagerPeerProviderFactoryConfiguration();
        try {
            className = cachePeerProviderFactoryConfiguration.fullyQualifiedClassPath;
        } catch (Throwable t) {
            //
        }
        if (className == null) {
            LOG.debug("No CachePeerProviderFactoryConfiguration specified. Not configuring a CacheManagerPeerProvider.");
            return null;
        } else {
            CacheManagerPeerProviderFactory cacheManagerPeerProviderFactory =
                    (CacheManagerPeerProviderFactory)
                            ClassLoaderUtil.createNewInstance(className);
            Properties properties = PropertyUtil.parseProperties(cachePeerProviderFactoryConfiguration.properties);
            return cacheManagerPeerProviderFactory.createCachePeerProvider(cacheManager, properties);
        }
    }

    /**
     * Tries to load the class specified otherwise defaults to null
     */
    public CacheManagerPeerListener createCachePeerListener() {
        String className = null;
        FactoryConfiguration cachePeerListenerFactoryConfiguration =
                configuration.getCacheManagerPeerListenerFactoryConfiguration();
        try {
            className = cachePeerListenerFactoryConfiguration.fullyQualifiedClassPath;
        } catch (Throwable t) {
            //
        }
        if (className == null) {
            LOG.debug("No CachePeerListenerFactoryConfiguration specified. Not configuring a CacheManagerPeerListener.");
            return null;
        } else {
            CacheManagerPeerListenerFactory cacheManagerPeerListenerFactory = (CacheManagerPeerListenerFactory)
                    ClassLoaderUtil.createNewInstance(className);
            Properties properties = PropertyUtil.parseProperties(cachePeerListenerFactoryConfiguration.properties);
            return cacheManagerPeerListenerFactory.createCachePeerListener(cacheManager, properties);
        }
    }

    /**
     * Tries to load the class specified.
     *
     * @return If there is none returns null.
     */
    public CacheManagerEventListener createCacheManagerEventListener() throws CacheException {
        String className = null;
        FactoryConfiguration cacheManagerEventListenerFactoryConfiguration =
                configuration.getCacheManagerEventListenerFactoryConfiguration();
        try {
            className = cacheManagerEventListenerFactoryConfiguration.fullyQualifiedClassPath;
        } catch (Throwable t) {
            //No class created because the config was missing
        }
        if (className == null || className.length() == 0) {
            LOG.debug("No CacheManagerEventListenerFactory class specified. Skipping...");
            return null;
        } else {
            CacheManagerEventListenerFactory factory = (CacheManagerEventListenerFactory)
                    ClassLoaderUtil.createNewInstance(className);
            Properties properties = PropertyUtil.parseProperties(cacheManagerEventListenerFactoryConfiguration.properties);
            return factory.createCacheManagerEventListener(properties);
        }
    }

    /**
     * @return the disk store path, or null if not set.
     */
    public String getDiskStorePath() {
        DiskStoreConfiguration diskStoreConfiguration = configuration.getDiskStoreConfiguration();
        if (diskStoreConfiguration == null) {
            return null;
        } else {
            return diskStoreConfiguration.getPath();
        }
    }

    /**
     * @return the Default Cache
     * @throws net.sf.ehcache.CacheException if there is no default cache
     */
    public Cache createDefaultCache() throws CacheException {
        CacheConfiguration cacheConfiguration = configuration.getDefaultCacheConfiguration();
        if (cacheConfiguration == null) {
            throw new CacheException("Illegal configuration. No default cache is configured.");
        } else {
            cacheConfiguration.name = Cache.DEFAULT_CACHE_NAME;
            return createCache(cacheConfiguration);
        }
    }

    /**
     * Creates unitialised caches for each cache configuration found
     *
     * @return an empty set if there are none,
     */
    public Set createCaches() {
        Set caches = new HashSet();
        Set cacheConfigurations = configuration.getCacheConfigurations().entrySet();
        for (Iterator iterator = cacheConfigurations.iterator(); iterator.hasNext();) {
            Map.Entry entry = (Map.Entry) iterator.next();
            CacheConfiguration cacheConfiguration = (CacheConfiguration) entry.getValue();
            Cache cache = createCache(cacheConfiguration);
            caches.add(cache);
        }
        return caches;
    }

    /**
     * Creates a cache from configuration where the configuration cache name matches the given name
     *
     * @return the cache, or null if there is no match
     */
    Cache createCacheFromName(String name) {
        CacheConfiguration cacheConfiguration = null;
        Set cacheConfigurations = configuration.getCacheConfigurations().entrySet();
        for (Iterator iterator = cacheConfigurations.iterator(); iterator.hasNext();) {
            Map.Entry entry = (Map.Entry) iterator.next();
            CacheConfiguration cacheConfigurationCandidate = (CacheConfiguration) entry.getValue();
            if (cacheConfigurationCandidate.name.equals(name)) {
                cacheConfiguration = cacheConfigurationCandidate;
                break;
            }
        }
        if (cacheConfiguration == null) {
            return null;
        } else {
            return createCache(cacheConfiguration);
        }
    }

    /**
     * Create a cache given a cache configuration
     *
     * @param cacheConfiguration
     */
    Cache createCache(CacheConfiguration cacheConfiguration) {
        Cache cache = new Cache(cacheConfiguration.name,
                cacheConfiguration.maxElementsInMemory,
                cacheConfiguration.memoryStoreEvictionPolicy,
                cacheConfiguration.overflowToDisk,
                getDiskStorePath(),
                cacheConfiguration.eternal,
                cacheConfiguration.timeToLiveSeconds,
                cacheConfiguration.timeToIdleSeconds,
                cacheConfiguration.diskPersistent,
                cacheConfiguration.diskExpiryThreadIntervalSeconds,
                null);
        RegisteredEventListeners listeners = cache.getCacheEventNotificationService();
        registerCacheListeners(cache, cacheConfiguration, listeners);
        return cache;
    }

    /**
     * @return the Configuration used
     */
    public Configuration getConfigurationBean() {
        return configuration;
    }
}
