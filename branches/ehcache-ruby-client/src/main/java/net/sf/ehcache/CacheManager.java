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


package net.sf.ehcache;

import net.sf.ehcache.config.Configuration;
import net.sf.ehcache.config.ConfigurationFactory;
import net.sf.ehcache.config.ConfigurationHelper;
import net.sf.ehcache.distribution.CacheManagerPeerListener;
import net.sf.ehcache.distribution.CacheManagerPeerProvider;
import net.sf.ehcache.event.CacheManagerEventListener;
import net.sf.ehcache.store.DiskStore;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A container for {@link Ehcache}s that maintain all aspects of their lifecycle.
 * <p/>
 * CacheManager is meant to have one singleton per virtual machine. Its creational methods are implemented so as to
 * make it a singleton. The design reasons for one CacheManager per VM are:
 * <ol>
 * <li>The CacheManager will by default look for a resource named ehcache.xml, or failing that ehcache-failsafe.xml
 * <li>Persistent stores write files to a directory
 * <li>Event listeners are given cache names as arguments. They are assured the cache is referenceable through a single
 * CacheManager.
 * </ol>
 * <p/>
 * @author Greg Luck
 * @version $Id$
 */
public class CacheManager {

    /**
     * Keeps track of all known CacheManagers. Used to check on conflicts.
     * CacheManagers should remove themselves from this list during shut down.
     */
    public static final List ALL_CACHE_MANAGERS = Collections.synchronizedList(new ArrayList());

    private static final Log LOG = LogFactory.getLog(CacheManager.class.getName());

    /**
     * The Singleton Instance.
     */
    private static CacheManager singleton;

    /**
     * Caches managed by this manager.
     */
    protected final Map caches = new HashMap();

    /**
     * Default cache cache.
     */
    private Ehcache defaultCache;

    /**
     * The path for the directory in which disk caches are created.
     */
    private String diskStorePath;

    /**
     * The CacheManagerEventListener which will be notified of significant events.
     */
    private CacheManagerEventListener cacheManagerEventListener;

    private Status status;

    private CacheManagerPeerProvider cacheManagerPeerProvider;
    private CacheManagerPeerListener cacheManagerPeerListener;

    /**
     * An constructor for CacheManager, which takes a configuration object, rather than one created by parsing
     * an ehcache.xml file. This constructor gives complete control over the creation of the CacheManager.
     * <p/>
     * Care should be taken to ensure that, if multiple CacheManages are created, they do now overwrite each others
     * disk store files, as would happend if two were created which used the same diskStore path.
     * <p/>
     * This method does not act as a singleton. Callers must maintain their own reference to it.
     * <p/>
     * Note that if one of the {@link #create()}  methods are called, a new singleton instance will be created,
     * separate from any instances created in this method.
     *
     * @param configuration
     * @throws CacheException
     */
    public CacheManager(Configuration configuration) throws CacheException {
        status = Status.STATUS_UNINITIALISED;
        init(configuration, null, null, null);
    }

    /**
     * An ordinary constructor for CacheManager.
     * This method does not act as a singleton. Callers must maintain a reference to it.
     * Note that if one of the {@link #create()}  methods are called, a new singleton will be created,
     * separate from any instances created in this method.
     *
     * @param configurationFileName an xml configuration file available through a file name. The configuration
     *                              {@link File} is created
     *                              using new <code>File(configurationFileName)</code>
     * @throws CacheException
     * @see #create(String)
     */
    public CacheManager(String configurationFileName) throws CacheException {
        status = Status.STATUS_UNINITIALISED;
        init(null, configurationFileName, null, null);
    }

    /**
     * An ordinary constructor for CacheManager.
     * This method does not act as a singleton. Callers must maintain a reference to it.
     * Note that if one of the {@link #create()}  methods are called, a new singleton will be created,
     * separate from any instances created in this method.
     * <p/>
     * This method can be used to specify a configuration resource in the classpath other
     * than the default of \"/ehcache.xml\":
     * <pre>
     * URL url = this.getClass().getResource("/ehcache-2.xml");
     * </pre>
     * Note that {@link Class#getResource} will look for resources in the same package unless a leading "/"
     * is used, in which case it will look in the root of the classpath.
     * <p/>
     * You can also load a resource using other class loaders. e.g. {@link Thread#getContextClassLoader()}
     *
     * @param configurationURL an xml configuration available through a URL.
     * @throws CacheException
     * @see #create(java.net.URL)
     * @since 1.2
     */
    public CacheManager(URL configurationURL) throws CacheException {
        status = Status.STATUS_UNINITIALISED;
        init(null, null, configurationURL, null);
    }

    /**
     * An ordinary constructor for CacheManager.
     * This method does not act as a singleton. Callers must maintain a reference to it.
     * Note that if one of the {@link #create()}  methods are called, a new singleton will be created,
     * separate from any instances created in this method.
     *
     * @param configurationInputStream an xml configuration file available through an inputstream
     * @throws CacheException
     * @see #create(java.io.InputStream)
     */
    public CacheManager(InputStream configurationInputStream) throws CacheException {
        status = Status.STATUS_UNINITIALISED;
        init(null, null, null, configurationInputStream);
    }

    /**
     * Constructor.
     *
     * @throws CacheException
     */
    public CacheManager() throws CacheException {
        //default config will be done
        status = Status.STATUS_UNINITIALISED;
        init(null, null, null, null);
    }

    private void init(Configuration configuration, String configurationFileName, URL configurationURL,
                      InputStream configurationInputStream) {
        Configuration localConfiguration = configuration;
        if (configuration == null) {
            localConfiguration = parseConfiguration(configurationFileName, configurationURL, configurationInputStream);
        } else {
            localConfiguration.setSource("Programmatically configured.");
        }

        ConfigurationHelper configurationHelper = new ConfigurationHelper(this, localConfiguration);
        configure(configurationHelper);
        addConfiguredCaches(configurationHelper);

        status = Status.STATUS_ALIVE;
        if (cacheManagerPeerListener != null) {
            cacheManagerPeerListener.init();
        }
        if (cacheManagerPeerProvider != null) {
            cacheManagerPeerProvider.init();
        }
    }

    /**
     * Loads configuration, either from the supplied {@link ConfigurationHelper} or by creating a new Configuration instance
     * from the configuration file referred to by file, inputstream or URL.
     * <p/>
     * Should only be called once.
     *
     * @param configurationFileName     the file name to parse, or null
     * @param configurationURL          the URL to pass, or null
     * @param configurationInputStream, the InputStream to parse, or null
     * @return the loaded configuration
     * @throws CacheException if the configuration cannot be parsed
     */
    private synchronized Configuration parseConfiguration(String configurationFileName, URL configurationURL,
                                                          InputStream configurationInputStream) throws CacheException {
        reinitialisationCheck();
        Configuration configuration;
        String configurationSource;
        if (configurationFileName != null) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Configuring CacheManager from " + configurationFileName);
            }
            configuration = ConfigurationFactory.parseConfiguration(new File(configurationFileName));
            configurationSource = "file located at " + configurationFileName;
        } else if (configurationURL != null) {
            configuration = ConfigurationFactory.parseConfiguration(configurationURL);
            configurationSource = "URL of " + configurationURL;
        } else if (configurationInputStream != null) {
            configuration = ConfigurationFactory.parseConfiguration(configurationInputStream);
            configurationSource = "InputStream " + configurationInputStream;
        } else {
            if (LOG.isDebugEnabled()) {
                LOG.debug("Configuring ehcache from classpath.");
            }
            configuration = ConfigurationFactory.parseConfiguration();
            configurationSource = "classpath";
        }
        configuration.setSource(configurationSource);
        return configuration;

    }

    private void configure(ConfigurationHelper configurationHelper) {

        diskStorePath = configurationHelper.getDiskStorePath();
        detectAndFixDiskStorePathConflict(configurationHelper);

        cacheManagerEventListener = configurationHelper.createCacheManagerEventListener();

        cacheManagerPeerListener = configurationHelper.createCachePeerListener();
        detectAndFixCacheManagerPeerListenerConflict(configurationHelper);

        ALL_CACHE_MANAGERS.add(this);

        cacheManagerPeerProvider = configurationHelper.createCachePeerProvider();
        defaultCache = configurationHelper.createDefaultCache();

    }

    private void detectAndFixDiskStorePathConflict(ConfigurationHelper configurationHelper) {
        for (int i = 0; i < ALL_CACHE_MANAGERS.size(); i++) {
            CacheManager cacheManager = (CacheManager) ALL_CACHE_MANAGERS.get(i);
            if (diskStorePath.equals(cacheManager.diskStorePath)) {
                String newDiskStorePath = diskStorePath + File.separator + DiskStore.generateUniqueDirectory();
                LOG.warn("Creating a new instance of CacheManager using the diskStorePath \""
                        + diskStorePath + "\" which is already used" +
                        " by an existing CacheManager.\nThe source of the configuration was "
                        + configurationHelper.getConfigurationBean().getConfigurationSource() + ".\n" +
                        "The diskStore path for this CacheManager will be set to " + newDiskStorePath + ".\nTo avoid this" +
                        " warning consider using the CacheManager factory methods to create a singleton CacheManager " +
                        "or specifying a separate ehcache configuration (ehcache.xml) for each CacheManager instance.");
                diskStorePath = newDiskStorePath;
                break;
            }

        }
    }

    private void detectAndFixCacheManagerPeerListenerConflict(ConfigurationHelper configurationHelper) {
        if (cacheManagerPeerListener == null) {
            return;
        }
        String uniqueResourceIdentifier = cacheManagerPeerListener.getUniqueResourceIdentifier();
        for (int i = 0; i < ALL_CACHE_MANAGERS.size(); i++) {
            CacheManager cacheManager = (CacheManager) ALL_CACHE_MANAGERS.get(i);
            CacheManagerPeerListener otherCacheManagerPeerListener = cacheManager.cacheManagerPeerListener;
            if (otherCacheManagerPeerListener == null) {
                continue;
            }
            String otherUniqueResourceIdentifier = otherCacheManagerPeerListener.getUniqueResourceIdentifier();
            if (uniqueResourceIdentifier.equals(otherUniqueResourceIdentifier)) {
                LOG.warn("Creating a new instance of CacheManager with a CacheManagerPeerListener which " +
                        "has a conflict on a resource that must be unique.\n" +
                        "The resource is " + uniqueResourceIdentifier + ".\n" +
                        "Attempting automatic resolution. The source of the configuration was "
                        + configurationHelper.getConfigurationBean().getConfigurationSource() + ".\n"
                        + "To avoid this warning consider using the CacheManager factory methods to create a " +
                        "singleton CacheManager " +
                        "or specifying a separate ehcache configuration (ehcache.xml) for each CacheManager instance.");
                cacheManagerPeerListener.attemptResolutionOfUniqueResourceConflict();
                break;
            }

        }
    }

    private void addConfiguredCaches(ConfigurationHelper configurationHelper) {
        Set unitialisedCaches = configurationHelper.createCaches();
        for (Iterator iterator = unitialisedCaches.iterator(); iterator.hasNext();) {
            Ehcache unitialisedCache = (Ehcache) iterator.next();
            addCacheNoCheck(unitialisedCache);
        }
    }

    private void reinitialisationCheck() throws IllegalStateException {
        if (defaultCache != null || diskStorePath != null || caches.size() != 0
                || status.equals(Status.STATUS_SHUTDOWN)) {
            throw new IllegalStateException("Attempt to reinitialise the CacheManager");
        }
    }

    /**
     * A factory method to create a singleton CacheManager with default config, or return it if it exists.
     * <p/>
     * The configuration will be read, {@link Ehcache}s created and required stores initialized.
     * When the {@link CacheManager} is no longer required, call shutdown to free resources.
     *
     * @return the singleton CacheManager
     * @throws CacheException if the CacheManager cannot be created
     */
    public static CacheManager create() throws CacheException {
        synchronized (CacheManager.class) {
            if (singleton == null) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Creating new CacheManager with default config");
                }
                singleton = new CacheManager();
            } else {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Attempting to create an existing singleton. Existing singleton returned.");
                }
            }
            return singleton;
        }
    }

    /**
     * A factory method to create a singleton CacheManager with default config, or return it if it exists.
     * <p/>
     * This has the same effect as {@link CacheManager#create}
     * <p/>
     * Same as {@link #create()}
     *
     * @return the singleton CacheManager
     * @throws CacheException if the CacheManager cannot be created
     */
    public static CacheManager getInstance() throws CacheException {
        return CacheManager.create();
    }

    /**
     * A factory method to create a singleton CacheManager with a specified configuration.
     *
     * @param configurationFileName an xml file compliant with the ehcache.xsd schema
     *                              <p/>
     *                              The configuration will be read, {@link Ehcache}s created and required stores initialized.
     *                              When the {@link CacheManager} is no longer required, call shutdown to free resources.
     */
    public static CacheManager create(String configurationFileName) throws CacheException {
        synchronized (CacheManager.class) {
            if (singleton == null) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Creating new CacheManager with config file: " + configurationFileName);
                }
                singleton = new CacheManager(configurationFileName);
            }
            return singleton;
        }
    }

    /**
     * A factory method to create a singleton CacheManager from an URL.
     * <p/>
     * This method can be used to specify a configuration resource in the classpath other
     * than the default of \"/ehcache.xml\":
     * This method can be used to specify a configuration resource in the classpath other
     * than the default of \"/ehcache.xml\":
     * <pre>
     * URL url = this.getClass().getResource("/ehcache-2.xml");
     * </pre>
     * Note that {@link Class#getResource} will look for resources in the same package unless a leading "/"
     * is used, in which case it will look in the root of the classpath.
     * <p/>
     * You can also load a resource using other class loaders. e.g. {@link Thread#getContextClassLoader()}
     *
     * @param configurationFileURL an URL to an xml file compliant with the ehcache.xsd schema
     *                             <p/>
     *                             The configuration will be read, {@link Ehcache}s created and required stores initialized.
     *                             When the {@link CacheManager} is no longer required, call shutdown to free resources.
     */
    public static CacheManager create(URL configurationFileURL) throws CacheException {
        synchronized (CacheManager.class) {
            if (singleton == null) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Creating new CacheManager with config URL: " + configurationFileURL);
                }
                singleton = new CacheManager(configurationFileURL);

            }
            return singleton;
        }
    }

    /**
     * A factory method to create a singleton CacheManager from a java.io.InputStream.
     * <p/>
     * This method makes it possible to use an inputstream for configuration.
     * Note: it is the clients responsibility to close the inputstream.
     * <p/>
     *
     * @param inputStream InputStream of xml compliant with the ehcache.xsd schema
     *                    <p/>
     *                    The configuration will be read, {@link Ehcache}s created and required stores initialized.
     *                    When the {@link CacheManager} is no longer required, call shutdown to free resources.
     */
    public static CacheManager create(InputStream inputStream) throws CacheException {
        synchronized (CacheManager.class) {
            if (singleton == null) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Creating new CacheManager with InputStream");
                }
                singleton = new CacheManager(inputStream);
            }
            return singleton;
        }
    }

    /**
     * Returns a concrete implementation of Cache.
     * Consider using the {@link #getEhcache(String)} method which returns an interface
     *
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     * @throws ClassCastException is the Ehcache found is not a Cache
     * @see #getEhcache(String)
     *
     */
    public synchronized Cache getCache(String name) throws IllegalStateException, ClassCastException {
        checkStatus();
        return (Cache) caches.get(name);
    }

    /**
     * Gets an Ehcache
     *
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     */
    public synchronized Ehcache getEhcache(String name) throws IllegalStateException {
        checkStatus();
        return (Ehcache) caches.get(name);
    }

    /**
     * Adds a {@link Ehcache} based on the defaultCache with the given name.
     * <p/>
     * Memory and Disk stores will be configured for it and it will be added
     * to the map of caches.
     * <p/>
     * Also notifies the CacheManagerEventListener after the cache was initialised and added.
     * <p/>
     * It will be created with the defaultCache attributes specified in ehcache.xml
     *
     * @param cacheName the name for the cache
     * @throws ObjectExistsException if the cache already exists
     * @throws CacheException        if there was an error creating the cache.
     */
    public synchronized void addCache(String cacheName) throws IllegalStateException,
            ObjectExistsException, CacheException {
        checkStatus();

        //NPE guard
        if (cacheName == null || cacheName.length() == 0) {
            return;
        }

        if (caches.get(cacheName) != null) {
            throw new ObjectExistsException("Cache " + cacheName + " already exists");
        }
        Ehcache cache = null;
        try {
            cache = (Ehcache) defaultCache.clone();
        } catch (CloneNotSupportedException e) {
            LOG.error("Failure adding cache. Initial cause was " + e.getMessage(), e);
        }
        if (cache != null) {
            cache.setName(cacheName);
        }
        addCache(cache);
    }

    /**
     * Adds a {@link Cache} to the CacheManager.
     * <p/>
     * Memory and Disk stores will be configured for it and it will be added to the map of caches.
     * Also notifies the CacheManagerEventListener after the cache was initialised and added.
     *
     * @param cache
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_UNINITIALISED} before this method is called.
     * @throws ObjectExistsException if the cache already exists in the CacheManager
     * @throws CacheException        if there was an error adding the cache to the CacheManager
     */
    public synchronized void addCache(Cache cache) throws IllegalStateException,
            ObjectExistsException, CacheException {
        addCache((Ehcache)cache);
    }

    /**
     * Adds an {@link Ehcache} to the CacheManager.
     * <p/>
     * Memory and Disk stores will be configured for it and it will be added to the map of caches.
     * Also notifies the CacheManagerEventListener after the cache was initialised and added.
     *
     * @param cache
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_UNINITIALISED} before this method is called.
     * @throws ObjectExistsException if the cache already exists in the CacheManager
     * @throws CacheException        if there was an error adding the cache to the CacheManager
     */
    public synchronized void addCache(Ehcache cache) throws IllegalStateException,
            ObjectExistsException, CacheException {
        checkStatus();
        addCacheNoCheck(cache);
    }

    private synchronized void addCacheNoCheck(Ehcache cache) throws IllegalStateException,
            ObjectExistsException, CacheException {
        if (caches.get(cache.getName()) != null) {
            throw new ObjectExistsException("Cache " + cache.getName() + " already exists");
        }
        cache.setCacheManager(this);
        cache.setDiskStorePath(diskStorePath);
        cache.initialise();
        cache.bootstrap();
        caches.put(cache.getName(), cache);
        if (cacheManagerEventListener != null) {
            cacheManagerEventListener.notifyCacheAdded(cache.getName());
        }
        if (cacheManagerPeerListener != null && status.equals(Status.STATUS_ALIVE)) {
            cacheManagerPeerListener.notifyCacheAdded(cache.getName());
        }
    }

    /**
     * Checks whether a cache exists.
     * <p/>
     *
     * @param cacheName the cache name to check for
     * @return true if it exists
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     */
    public synchronized boolean cacheExists(String cacheName) throws IllegalStateException {
        checkStatus();
        return (caches.get(cacheName) != null);
    }

    /**
     * Removes all caches using {@link #removeCache} for each cache.
     */
    public synchronized void removalAll() {
        String[] cacheNames = getCacheNames();
        for (int i = 0; i < cacheNames.length; i++) {
            String cacheName = cacheNames[i];
            removeCache(cacheName);
        }
    }

    /**
     * Remove a cache from the CacheManager. The cache is disposed of.
     *
     * @param cacheName the cache name
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     */
    public synchronized void removeCache(String cacheName) throws IllegalStateException {
        checkStatus();

        //NPE guard
        if (cacheName == null || cacheName.length() == 0) {
            return;
        }

        Ehcache cache = (Ehcache) caches.remove(cacheName);
        if (cache != null && cache.getStatus().equals(Status.STATUS_ALIVE)) {
            cache.dispose();
            if (cacheManagerEventListener != null) {
                cacheManagerEventListener.notifyCacheRemoved(cache.getName());
            }
            if (cacheManagerPeerListener != null && status.equals(Status.STATUS_ALIVE)) {
                cacheManagerPeerListener.notifyCacheRemoved(cache.getName());
            }
        }
    }

    /**
     * Shuts down the CacheManager.
     * <p/>
     * If the shutdown occurs on the singleton, then the singleton is removed, so that if a singleton access method
     * is called, a new singleton will be created.
     */
    public void shutdown() {
        if (status.equals(Status.STATUS_SHUTDOWN)) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("CacheManager already shutdown");
            }
            return;
        }
        if (cacheManagerPeerProvider != null) {
            cacheManagerPeerProvider.dispose();
        }
        if (cacheManagerPeerListener != null) {
            cacheManagerPeerListener.dispose();
        }
        synchronized (CacheManager.class) {
            ALL_CACHE_MANAGERS.remove(this);

            Collection cacheSet = caches.values();
            for (Iterator iterator = cacheSet.iterator(); iterator.hasNext();) {
                Ehcache cache = (Ehcache) iterator.next();
                if (cache != null) {
                    cache.dispose();
                }
            }
            status = Status.STATUS_SHUTDOWN;

            //only delete singleton if the singleton is shutting down.
            if (this == singleton) {
                singleton = null;
            }
        }
    }

    /**
     * Returns a list of the current cache names.
     *
     * @return an array of {@link String}s
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     */
    public synchronized String[] getCacheNames() throws IllegalStateException {
        checkStatus();
        String[] list = new String[caches.size()];
        return (String[]) caches.keySet().toArray(list);
    }


    private void checkStatus() {
        if (!(status.equals(Status.STATUS_ALIVE))) {
            throw new IllegalStateException("The CacheManager is not alive.");
        }
    }


    /**
     * Gets the status attribute of the Ehcache
     *
     * @return The status value from the Status enum class
     */
    public Status getStatus() {
        return status;
    }

    /**
     * Clears  the contents of all caches in the CacheManager, but without
     * removing any caches.
     * <p/>
     * This method is not synchronized. It only guarantees to clear those elements in a cache
     * at the time that the {@link Ehcache#removeAll()} mehod  on each cache is called.
     */
    public void clearAll() throws CacheException {
        String[] cacheNames = getCacheNames();
        if (LOG.isDebugEnabled()) {
            LOG.debug("Clearing all caches");
        }
        for (int i = 0; i < cacheNames.length; i++) {
            String cacheName = cacheNames[i];
            Ehcache cache = getEhcache(cacheName);
            cache.removeAll();
        }
    }

    /**
     * Gets the <code>CacheManagerPeerProvider</code>
     * For distributed caches, the peer provider finds other cache managers and their caches in the same cluster
     *
     * @return the provider, or null if one does not exist
     */
    public CacheManagerPeerProvider getCachePeerProvider() {
        return cacheManagerPeerProvider;
    }

    /**
     * When CacheManage is configured as part of a cluster, a CacheManagerPeerListener will
     * be registered in it. Use this to access the individual cache listeners
     *
     * @return the listener, or null if one does not exist
     */
    public CacheManagerPeerListener getCachePeerListener() {
        return cacheManagerPeerListener;
    }

    /**
     * Gets the CacheManager event listener.
     *
     * @return null if none
     */
    public CacheManagerEventListener getCacheManagerEventListener() {
        return cacheManagerEventListener;
    }

    /**
     * Sets the CacheManager event listener. Any existing listener is disposed and removed first.
     *
     * @param cacheManagerEventListener the listener to set.
     */
    public void setCacheManagerEventListener(CacheManagerEventListener cacheManagerEventListener) {
        this.cacheManagerEventListener = cacheManagerEventListener;
    }

    /**
     * Gets the CacheManagerPeerProvider, which can be useful for programmatically adding peers. Adding peers
     * will only be useful if the peer providers are manually provided rather than automatically discovered, otherwise
     * they will go stale.
     *
     * @return the CacheManagerPeerProvider, or null if there is not one.
     */
    public CacheManagerPeerProvider getCacheManagerPeerProvider() {
        return cacheManagerPeerProvider;
    }

    /**
     * Replaces in the map of Caches managed by this CacheManager an Ehcache with a decorated version of the same
     * Ehcache. CacheManager can operate fully with a decorated Ehcache.
     * <p/>
     * Decorators can be used to obtain different behaviour from an Ehcache in a very flexible way. Some examples in
     * ehcache are:
     * <ol>
     * <li>{@link net.sf.ehcache.constructs.blocking.BlockingCache} - A cache that blocks other threads from getting a null element until the first thread
     * has placed a value in it.
     * <li>{@link net.sf.ehcache.constructs.blocking.SelfPopulatingCache} - A BlockingCache that has the additional
     * property of knowing how to load its own entries.
     * </ol>
     * Many other kinds are possible.
     * <p/>
     * It is generally required that a decorated cache, once constructed, is made available to other execution threads.
     * The simplest way of doing this is to substitute the original cache for the decorated one here.
     * <p/>
     * Note that any overwritten Ehcache methods will take on new behaviours without casting. Casting is only required
     * for new methods that the decorator introduces.
     * For more information see the well known Gang of Four Decorator pattern.
     * @param cache
     * @param decoratedCache An implementation of Ehcache that wraps the original cache.
     * @throws CacheException if the two caches do not equal each other.
     */
    public synchronized void replaceCacheWithDecoratedCache(Ehcache cache, Ehcache decoratedCache) throws CacheException {

        if (!cache.equals(decoratedCache)) {
            throw new CacheException("Cannot replace " + decoratedCache.getName()
                    + " It does not equal the incumbent cache.");
        } else {
            caches.remove(cache.getName());
            caches.put(cache.getName(), decoratedCache);
        }

    }
}

