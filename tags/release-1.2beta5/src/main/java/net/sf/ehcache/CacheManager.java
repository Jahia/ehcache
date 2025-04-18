/* ====================================================================
 * The Apache Software License, Version 1.1
 *
 * Copyright (c) 2003 - 2004 Greg Luck.  All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. The end-user documentation included with the redistribution, if
 *    any, must include the following acknowlegement:
 *       "This product includes software developed by Greg Luck
 *       (http://sourceforge.net/users/gregluck) and contributors.
 *       See http://sourceforge.net/project/memberlist.php?group_id=93232
 *       for a list of contributors"
 *    Alternately, this acknowledgement may appear in the software itself,
 *    if and wherever such third-party acknowlegements normally appear.
 *
 * 4. The names "EHCache" must not be used to endorse or promote products
 *    derived from this software without prior written permission. For written
 *    permission, please contact Greg Luck (gregluck at users.sourceforge.net).
 *
 * 5. Products derived from this software may not be called "EHCache"
 *    nor may "EHCache" appear in their names without prior written
 *    permission of Greg Luck.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL GREG LUCK OR OTHER
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by contributors
 * individuals on behalf of the EHCache project.  For more
 * information on EHCache, please see <http://ehcache.sourceforge.net/>.
 *
 */

package net.sf.ehcache;

import net.sf.ehcache.config.ConfigurationHelper;
import net.sf.ehcache.config.ConfigurationFactory;
import net.sf.ehcache.config.Configuration;
import net.sf.ehcache.distribution.CacheManagerPeerListener;
import net.sf.ehcache.distribution.CacheManagerPeerProvider;
import net.sf.ehcache.event.CacheManagerEventListener;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Set;

/**
 * A container for {@link Cache}s that maintain all aspects of their lifecycle.
 * <p/>
 * CacheManager is meant to have one singleton per virtual machine. Its creational methods are implemented so as to
 * make it a singleton. The design reasons for one CacheManager per VM are:
 * <ol>
 * <li>The CacheManager will by default look for a resource named ehcache.xml, or failing that ehcache-failsafe.xml
 * <li>Persistent stores write files to a directory
 * <li>Event listeners are given cache names as arguments. They are assured the cache is referenceable through a single
 * CacheManager.
 * </ol>
 *
 * @author Greg Luck
 * @version $Id: CacheManager.java,v 1.1 2006/03/09 06:38:19 gregluck Exp $
 */
public final class CacheManager {

    private static final Log LOG = LogFactory.getLog(CacheManager.class.getName());

    /**
     * Keeps track of the disk store paths of all CacheManagers.
     * Can be checked before letting a new CacheManager start up.
     */
    private static Set allCacheManagersDiskStorePaths = Collections.synchronizedSet(new HashSet());

    /**
     * The Singleton Instance
     */
    private static CacheManager singleton;

    /**
     * Caches managed by this manager
     */
    private Hashtable caches = new Hashtable();

    /**
     * Default cache cache
     */
    private Cache defaultCache;

    /**
     * The path for the directory in which disk caches are created
     */
    private String diskStorePath;

    /**
     * The CacheManagerEventListener which will be notified of significant events
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
     * @param configurationFileName
     * @param configurationURL
     * @param configurationInputStream
     */
    private synchronized Configuration parseConfiguration(String configurationFileName, URL configurationURL,
        InputStream configurationInputStream) throws CacheException {
        reinitialisationCheck();
        Configuration configuration = null;
        String configurationSource = null;
        if (configurationFileName != null) {
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
        if (!allCacheManagersDiskStorePaths.add(diskStorePath)) {
            throw new CacheException("Cannot parseConfiguration CacheManager. Attempt to create a new instance" +
                    " of CacheManager using the diskStorePath \"" + diskStorePath + "\" which is already used" +
                    " by an existing CacheManager. The source of the configuration was "
                    + configurationHelper.getConfigurationBean().getConfigurationSource() + ".");
        }

        cacheManagerEventListener = configurationHelper.createCacheManagerEventListener();
        cacheManagerPeerListener = configurationHelper.createCachePeerListener();
        cacheManagerPeerProvider = configurationHelper.createCachePeerProvider();
        defaultCache = configurationHelper.createDefaultCache();

        Set unitialisedCaches = configurationHelper.createCaches();
        for (Iterator iterator = unitialisedCaches.iterator(); iterator.hasNext();) {
            Cache unitialisedCache = (Cache) iterator.next();
            addCacheNoCheck(unitialisedCache);
        }
    }

    private void reinitialisationCheck() throws IllegalStateException {
        if (defaultCache != null || diskStorePath != null || caches.size() != 0
                || status.equals(Status.STATUS_SHUTDOWN)) {
            throw new IllegalStateException("Attempt to reinitialise the Cache Manager");
        }
    }

    /**
     * A factory method to create a singleton CacheManager with default config, or return it if it exists.
     * <p/>
     * The configuration will be read, {@link Cache}s created and required stores initialized.
     * When the {@link CacheManager} is no longer required, call shutdown to free resources.
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
     */
    public static CacheManager getInstance() throws CacheException {
        return CacheManager.create();
    }

    /**
     * A factory method to create a singleton CacheManager with a specified configuration.
     *
     * @param configurationFileName an xml file compliant with the ehcache.xsd schema
     *                              <p/>
     *                              The configuration will be read, {@link Cache}s created and required stores initialized.
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
     *                             The configuration will be read, {@link Cache}s created and required stores initialized.
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
     *                    The configuration will be read, {@link Cache}s created and required stores initialized.
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
     * Gets a Cache
     *
     * @throws IllegalStateException if the cache is not {@link Status#STATUS_ALIVE}
     */
    public synchronized Cache getCache(String name) throws IllegalStateException {
        checkStatus();
        return (Cache) caches.get(name);
    }

    /**
     * Adds a {@link Cache} based on the defaultCache with the given name.
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
        if (caches.get(cacheName) != null) {
            throw new ObjectExistsException("Cache " + cacheName + " already exists");
        }
        Cache cache = null;
        try {
            cache = (Cache) defaultCache.clone();
        } catch (CloneNotSupportedException e) {
            LOG.error("Failure adding cache. Error was " + e.getMessage());
        }
        cache.setName(cacheName);
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
        checkStatus();
        addCacheNoCheck(cache);
    }

    private synchronized void addCacheNoCheck(Cache cache) throws IllegalStateException,
            ObjectExistsException, CacheException {
        if (caches.get(cache.getName()) != null) {
            throw new ObjectExistsException("Cache " + cache.getName() + " already exists");
        }
        cache.initialise();
        cache.setCacheManager(this);
        caches.put(cache.getName(), cache);
        if (cacheManagerEventListener != null) {
            cacheManagerEventListener.notifyCacheAdded(cache.getName());
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
        Cache cache = (Cache) caches.remove(cacheName);
        if (cache != null) {
            cache.dispose();
        }
        if (cacheManagerEventListener != null) {
            cacheManagerEventListener.notifyCacheRemoved(cache.getName());
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
            allCacheManagersDiskStorePaths.remove(diskStorePath);
            Enumeration allCaches = caches.elements();
            while (allCaches.hasMoreElements()) {
                Cache cache = (Cache) allCaches.nextElement();
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
     * Gets the status attribute of the Cache
     *
     * @return The status value from the Status enum class
     */
    public Status getStatus() {
        return status;
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




}

