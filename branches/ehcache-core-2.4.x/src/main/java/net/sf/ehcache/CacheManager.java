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
package net.sf.ehcache;

import java.io.File;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;

import net.sf.ehcache.cluster.CacheCluster;
import net.sf.ehcache.cluster.ClusterScheme;
import net.sf.ehcache.cluster.ClusterSchemeNotAvailableException;
import net.sf.ehcache.cluster.NoopCacheCluster;
import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.config.Configuration;
import net.sf.ehcache.config.ConfigurationFactory;
import net.sf.ehcache.config.ConfigurationHelper;
import net.sf.ehcache.config.DiskStoreConfiguration;
import net.sf.ehcache.config.FactoryConfiguration;
import net.sf.ehcache.config.InvalidConfigurationException;
import net.sf.ehcache.config.NonstopConfiguration;
import net.sf.ehcache.config.TerracottaClientConfiguration;
import net.sf.ehcache.config.TerracottaConfiguration.Consistency;
import net.sf.ehcache.config.TerracottaConfiguration.StorageStrategy;
import net.sf.ehcache.config.generator.ConfigurationUtil;
import net.sf.ehcache.constructs.nonstop.CacheManagerExecutorServiceFactory;
import net.sf.ehcache.constructs.nonstop.NonStopCacheException;
import net.sf.ehcache.constructs.nonstop.NonstopActiveDelegateHolder;
import net.sf.ehcache.constructs.nonstop.NonstopExecutorService;
import net.sf.ehcache.constructs.nonstop.NonstopExecutorServiceFactory;
import net.sf.ehcache.distribution.CacheManagerPeerListener;
import net.sf.ehcache.distribution.CacheManagerPeerProvider;
import net.sf.ehcache.event.CacheEventListener;
import net.sf.ehcache.event.CacheManagerEventListener;
import net.sf.ehcache.event.CacheManagerEventListenerRegistry;
import net.sf.ehcache.event.NonstopCacheEventListener;
import net.sf.ehcache.management.provider.MBeanRegistrationProvider;
import net.sf.ehcache.management.provider.MBeanRegistrationProviderException;
import net.sf.ehcache.management.provider.MBeanRegistrationProviderFactory;
import net.sf.ehcache.management.provider.MBeanRegistrationProviderFactoryImpl;
import net.sf.ehcache.store.DiskStore;
import net.sf.ehcache.store.Store;
import net.sf.ehcache.terracotta.ClusteredInstanceFactory;
import net.sf.ehcache.terracotta.TerracottaClient;
import net.sf.ehcache.terracotta.TerracottaClientRejoinListener;
import net.sf.ehcache.transaction.DelegatingTransactionIDFactory;
import net.sf.ehcache.transaction.ReadCommittedSoftLockFactoryImpl;
import net.sf.ehcache.transaction.SoftLockFactory;
import net.sf.ehcache.transaction.TransactionIDFactory;
import net.sf.ehcache.transaction.manager.TransactionManagerLookup;
import net.sf.ehcache.transaction.xa.processor.XARequestProcessor;
import net.sf.ehcache.util.FailSafeTimer;
import net.sf.ehcache.util.PropertyUtil;
import net.sf.ehcache.util.UpdateChecker;
import net.sf.ehcache.writer.writebehind.WriteBehind;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A container for {@link Ehcache}s that maintain all aspects of their lifecycle.
 * <p/>
 * CacheManager may be either be a singleton if created with factory methods, or multiple instances may exist, in which case resources
 * required by each must be unique.
 * <p/>
 * A CacheManager holds references to Caches and Ehcaches and manages their creation and lifecycle.
 *
 * @author Greg Luck
 * @version $Id$
 */
public class CacheManager {

    /**
     * Default name if not specified in the configuration/
     */
    public static final String DEFAULT_NAME = "__DEFAULT__";

    /**
     * Keeps track of all known CacheManagers. Used to check on conflicts.
     * CacheManagers should remove themselves from this list during shut down.
     */
    public static final List<CacheManager> ALL_CACHE_MANAGERS = new CopyOnWriteArrayList<CacheManager>();

    /**
     * System property to enable creation of a shutdown hook for CacheManager.
     */
    public static final String ENABLE_SHUTDOWN_HOOK_PROPERTY = "net.sf.ehcache.enableShutdownHook";

    private static final Logger LOG = LoggerFactory.getLogger(CacheManager.class);

    /**
     * Update check interval - one week in milliseconds
     */
    private static final long EVERY_WEEK = 7 * 24 * 60 * 60 * 1000;

    /**
     * delay period before doing update check
     */
    private static final long DELAY_UPDATE_CHECK = 1000;

    /**
     * The Singleton Instance.
     */
    private static volatile CacheManager singleton;

    /**
     * The factory to use for creating MBeanRegistrationProvider's
     */
    private static final MBeanRegistrationProviderFactory MBEAN_REGISTRATION_PROVIDER_FACTORY = new MBeanRegistrationProviderFactoryImpl();

    private static final String NO_DEFAULT_CACHE_ERROR_MSG = "Caches cannot be added by name when default cache config is not specified"
            + " in the config. Please add a default cache config in the configuration.";

    /**
     * A name for this CacheManager to distinguish it from others.
     */
    protected volatile String name;

    /**
     * Status of the Cache Manager
     */
    protected volatile Status status;

    /**
     * The map of providers
     */
    protected final Map<String, CacheManagerPeerProvider> cacheManagerPeerProviders = new ConcurrentHashMap<String, CacheManagerPeerProvider>();

    /**
     * The map of listeners
     */
    protected final Map<String, CacheManagerPeerListener> cacheManagerPeerListeners = new ConcurrentHashMap<String, CacheManagerPeerListener>();

    /**
     * The listener registry
     */
    protected final CacheManagerEventListenerRegistry cacheManagerEventListenerRegistry = new CacheManagerEventListenerRegistry();

    /**
     * The shutdown hook thread for CacheManager. This ensures that the CacheManager and Caches are left in a
     * consistent state on a CTRL-C or kill.
     * <p/>
     * This thread must be unregistered as a shutdown hook, when the CacheManager is disposed. Otherwise the CacheManager is not GC-able.
     * <p/>
     * Of course kill -9 or abrupt termination will not run the shutdown hook. In this case, various sanity checks are made at start up.
     */
    protected Thread shutdownHook;

    /**
     * Ehcaches managed by this manager.
     */
    private final ConcurrentMap<String, Ehcache> ehcaches = new ConcurrentHashMap<String, Ehcache>();

    /**
     * Default cache cache.
     */
    private Ehcache defaultCache;

    /**
     * The path for the directory in which disk caches are created.
     */
    private String diskStorePath;

    private MBeanRegistrationProvider mbeanRegistrationProvider;

    private FailSafeTimer cacheManagerTimer;

    private volatile TerracottaClient terracottaClient;

    /**
     * The {@link TerracottaClientConfiguration} used for this {@link CacheManager}
     */
    private TerracottaClientConfiguration terracottaClientConfiguration;

    private Configuration configuration;

    private volatile boolean allowsDynamicCacheConfig = true;

    private volatile TransactionManagerLookup transactionManagerLookup;

    private volatile TransactionController transactionController;

    private final ConcurrentMap<String, SoftLockFactory> softLockFactories = new ConcurrentHashMap<String, SoftLockFactory>();

    private final NonstopExecutorServiceFactory nonstopExecutorServiceFactory = CacheManagerExecutorServiceFactory.getInstance();

    private final CacheRejoinAction cacheRejoinAction = new CacheRejoinAction();

    /**
     * An constructor for CacheManager, which takes a configuration object, rather than one created by parsing
     * an ehcache.xml file. This constructor gives complete control over the creation of the CacheManager.
     * <p/>
     * Care should be taken to ensure that, if multiple CacheManages are created, they do now overwrite each others disk store files, as
     * would happend if two were created which used the same diskStore path.
     * <p/>
     * This method does not act as a singleton. Callers must maintain their own reference to it.
     * <p/>
     * Note that if one of the {@link #create()} methods are called, a new singleton instance will be created, separate from any instances
     * created in this method.
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
     * Note that if one of the {@link #create()} methods are called, a new singleton will be created,
     * separate from any instances created in this method.
     *
     * @param configurationFileName
     *            an xml configuration file available through a file name. The configuration {@link File} is created
     *            using new <code>File(configurationFileName)</code>
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
     * Note that if one of the {@link #create()} methods are called, a new singleton will be created,
     * separate from any instances created in this method.
     * <p/>
     * This method can be used to specify a configuration resource in the classpath other than the default of \"/ehcache.xml\":
     *
     * <pre>
     * URL url = this.getClass().getResource(&quot;/ehcache-2.xml&quot;);
     * </pre>
     *
     * Note that {@link Class#getResource} will look for resources in the same package unless a leading "/" is used, in which case it will
     * look in the root of the classpath.
     * <p/>
     * You can also load a resource using other class loaders. e.g. {@link Thread#getContextClassLoader()}
     *
     * @param configurationURL
     *            an xml configuration available through a URL.
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
     * Note that if one of the {@link #create()} methods are called, a new singleton will be created,
     * separate from any instances created in this method.
     *
     * @param configurationInputStream
     *            an xml configuration file available through an inputstream
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
        // default config will be done
        status = Status.STATUS_UNINITIALISED;
        init(null, null, null, null);
    }

    /**
     * initialises the CacheManager
     */
    protected synchronized void init(Configuration initialConfiguration, String configurationFileName, URL configurationURL,
            InputStream configurationInputStream) {
        Configuration localConfiguration = initialConfiguration;
        if (initialConfiguration == null) {
            localConfiguration = parseConfiguration(configurationFileName, configurationURL, configurationInputStream);
            this.configuration = localConfiguration;
        } else {
            this.configuration = initialConfiguration;
        }

        if (this.configuration.getTerracottaConfiguration() != null) {
            this.configuration.getTerracottaConfiguration().freezeConfig();
        }
        validateConfiguration();

        if (localConfiguration.getName() != null) {
            this.name = localConfiguration.getName();
        }

        this.allowsDynamicCacheConfig = localConfiguration.getDynamicConfig();
        this.terracottaClientConfiguration = localConfiguration.getTerracottaConfiguration();

        terracottaClient = new TerracottaClient(this, cacheRejoinAction, localConfiguration.getTerracottaConfiguration());

        Map<String, CacheConfiguration> cacheConfigs = localConfiguration.getCacheConfigurations();
        if (localConfiguration.getDefaultCacheConfiguration() != null
                && localConfiguration.getDefaultCacheConfiguration().isTerracottaClustered()) {
            terracottaClient.createClusteredInstanceFactory(cacheConfigs);
        } else {
            for (CacheConfiguration config : cacheConfigs.values()) {
                if (config.isTerracottaClustered()) {
                    terracottaClient.createClusteredInstanceFactory(cacheConfigs);
                    break;
                }
            }
        }

        if (terracottaClient.getClusteredInstanceFactory() != null && this.name == null) {
            this.name = CacheManager.DEFAULT_NAME;
        }

        TransactionIDFactory transactionIDFactory = createTransactionIDFactory();
        this.transactionController = new TransactionController(transactionIDFactory, configuration.getDefaultTransactionTimeoutInSeconds());

        ConfigurationHelper configurationHelper = new ConfigurationHelper(this, localConfiguration);
        configure(configurationHelper);
        status = Status.STATUS_ALIVE;

        for (CacheManagerPeerProvider cacheManagerPeerProvider : cacheManagerPeerProviders.values()) {
            cacheManagerPeerProvider.init();
        }

        cacheManagerEventListenerRegistry.init();
        addShutdownHookIfRequired();

        cacheManagerTimer = new FailSafeTimer(getName());
        checkForUpdateIfNeeded(localConfiguration.getUpdateCheck());

        mbeanRegistrationProvider = MBEAN_REGISTRATION_PROVIDER_FACTORY.createMBeanRegistrationProvider(localConfiguration);

        // do this last
        addConfiguredCaches(configurationHelper);

        try {
            mbeanRegistrationProvider.initialize(this, terracottaClient.getClusteredInstanceFactory());
        } catch (MBeanRegistrationProviderException e) {
            LOG.warn("Failed to initialize the MBeanRegistrationProvider - " + mbeanRegistrationProvider.getClass().getName(), e);
        }
    }

    private boolean isTerracottaRejoinEnabled() {
        TerracottaClientConfiguration terracottaConfiguration = configuration.getTerracottaConfiguration();
        return terracottaConfiguration != null && terracottaConfiguration.isRejoin();
    }

    private void validateConfiguration() {
        if (isTerracottaRejoinEnabled()) {
            validateCacheConfigs(configuration.getCacheConfigurations().values());
        }
    }

    private void validateCacheConfigs(Collection<CacheConfiguration> cacheConfigs) {
        boolean invalid = false;
        final StringBuilder error = new StringBuilder();
        final List<String> invalidCaches = new ArrayList<String>();
        for (CacheConfiguration config : cacheConfigs) {
            if (config.isTerracottaClustered()) {
                if (config.getTerracottaConfiguration().getStorageStrategy().equals(StorageStrategy.CLASSIC)) {
                    if (config.getTerracottaConfiguration().isNonstopEnabled()) {
                        invalid = true;
                        error.append("\n").append(
                                "NONSTOP can't be enabled with " + StorageStrategy.CLASSIC.name() + " strategy. Invalid Cache: "
                                        + config.getName());
                    }

                    if (isTerracottaRejoinEnabled()) {
                        invalid = true;
                        error.append("\n").append(
                                "REJOIN can't be enabled with " + StorageStrategy.CLASSIC.name() + " strategy. Invalid Cache: "
                                        + config.getName());
                    }

                    if (config.getTerracottaConsistency().equals(Consistency.EVENTUAL)) {
                        invalid = true;
                        error.append("\n").append(
                                Consistency.EVENTUAL.name() + " consistency can't be enabled with " + StorageStrategy.CLASSIC.name()
                                        + " strategy. Invalid Cache: " + config.getName());
                    }
                }

                if (isTerracottaRejoinEnabled() && !config.getTerracottaConfiguration().isNonstopEnabled()) {
                    invalid = true;
                    error.append("\n").append(
                            "Terracotta clustered caches must be nonstop when rejoin is enabled. Invalid cache: " + config.getName());
                }
            }
        }

        if (invalid) {
            String errorMessage = "Errors:" + error.toString();
            throw new InvalidConfigurationException(errorMessage);
        }
    }

    /**
     * Returns unique cluster-wide id for this cache-manager. Only applicable when running in "cluster" mode, e.g. when this cache-manager
     * contains caches clustered with Terracotta. Otherwise returns blank string.
     *
     * @return Returns unique cluster-wide id for this cache-manager when it contains clustered caches (e.g. Terracotta clustered caches).
     *         Otherwise returns blank string.
     */
    public String getClusterUUID() {
        if (terracottaClient.getClusteredInstanceFactory() != null) {
            return getClientUUID(terracottaClient.getClusteredInstanceFactory());
        } else {
            return "";
        }
    }

    private static String getClientUUID(ClusteredInstanceFactory clusteredInstanceFactory) {
        return clusteredInstanceFactory.getUUID();
    }

    /**
     * Create/access the appropriate terracotta clustered store for the given cache
     *
     * @param cache The cache for which the Store should be created
     * @return a new (or existing) clustered store
     */
    public Store createTerracottaStore(Ehcache cache) {
        return getClusteredInstanceFactory(cache).createStore(cache);
    }

    /**
     * Create/access the appropriate clustered write behind queue for the given cache
     *
     * @param cache The cache for which the write behind queue should be created
     * @return a new (or existing) write behind queue
     */
    public WriteBehind createTerracottaWriteBehind(Ehcache cache) {
        return getClusteredInstanceFactory(cache).createWriteBehind(cache);
    }

    /**
     * Create/access the appropriate clustered cache event replicator for the given cache
     *
     * @param cache The cache for which the clustered event replicator should be created
     * @return a new cache event replicator
     */
    public CacheEventListener createTerracottaEventReplicator(Ehcache cache) {
        CacheEventListener cacheEventListener = null;
        CacheConfiguration cacheConfig = cache.getCacheConfiguration();
        if (cacheConfig.isTerracottaClustered() && cacheConfig.getTerracottaConfiguration().isNonstopEnabled()) {
            NonstopActiveDelegateHolder nonstopActiveDelegateHolder = getNonstopActiveDelegateHolder(cache);
            cacheEventListener = new NonstopCacheEventListener(nonstopActiveDelegateHolder);
        } else {
            cacheEventListener = getClusteredInstanceFactory(cache).createEventReplicator(cache);
        }
        return cacheEventListener;
    }

    private NonstopActiveDelegateHolder getNonstopActiveDelegateHolder(Ehcache cache) {
        if (cache instanceof Cache) {
            return ((Cache) cache).getNonstopActiveDelegateHolder();
        } else {
            throw new CacheException("Cache event replication using Terracotta is not supported for Cache decorators");
        }
    }

    /**
     * Return the clustered instance factory for a cache of this cache manager.
     *
     * @param cache the cache the clustered instance factory has to be returned for
     * @return the clustered instance factory
     */
    protected ClusteredInstanceFactory getClusteredInstanceFactory(Ehcache cache) {
        ClusteredInstanceFactory clusteredInstanceFactory = terracottaClient.getClusteredInstanceFactory();
        if (null == clusteredInstanceFactory) {
            // adding a cache programmatically when there is no clustered store defined in the configuration
            // at the time this cacheManager was created
            Map<String, CacheConfiguration> map = new HashMap<String, CacheConfiguration>(1);
            map.put(cache.getName(), cache.getCacheConfiguration());
            final boolean created = terracottaClient.createClusteredInstanceFactory(map);
            clusteredInstanceFactory = terracottaClient.getClusteredInstanceFactory();

            if (created) {
                try {
                    mbeanRegistrationProvider.reinitialize(clusteredInstanceFactory);
                } catch (MBeanRegistrationProviderException e) {
                    LOG.warn("Failed to initialize the MBeanRegistrationProvider - " + mbeanRegistrationProvider.getClass().getName(), e);
                }
            }
        }
        return clusteredInstanceFactory;
    }

    private void checkForUpdateIfNeeded(boolean updateCheckNeeded) {
        try {
            if (updateCheckNeeded) {
                UpdateChecker updateChecker = new UpdateChecker();
                cacheManagerTimer.scheduleAtFixedRate(updateChecker, DELAY_UPDATE_CHECK, EVERY_WEEK);
            }
        } catch (Throwable t) {
            LOG.debug("Failed to set up update checker", t);
        }
    }

    /**
     * Loads configuration, either from the supplied {@link ConfigurationHelper} or by creating a new Configuration instance
     * from the configuration file referred to by file, inputstream or URL.
     * <p/>
     * Should only be called once.
     *
     * @param configurationFileName
     *            the file name to parse, or null
     * @param configurationURL
     *            the URL to pass, or null
     * @param configurationInputStream
     *            , the InputStream to parse, or null
     * @return the loaded configuration
     * @throws CacheException
     *             if the configuration cannot be parsed
     */
    private synchronized Configuration parseConfiguration(String configurationFileName, URL configurationURL,
            InputStream configurationInputStream) throws CacheException {
        reinitialisationCheck();
        Configuration parsedConfig;
        if (configurationFileName != null) {

            LOG.debug("Configuring CacheManager from {}", configurationFileName);
            parsedConfig = ConfigurationFactory.parseConfiguration(new File(configurationFileName));
        } else if (configurationURL != null) {
            parsedConfig = ConfigurationFactory.parseConfiguration(configurationURL);
        } else if (configurationInputStream != null) {
            parsedConfig = ConfigurationFactory.parseConfiguration(configurationInputStream);
        } else {
            LOG.debug("Configuring ehcache from classpath.");
            parsedConfig = ConfigurationFactory.parseConfiguration();
        }
        return parsedConfig;

    }

    private void configure(ConfigurationHelper configurationHelper) {

        diskStorePath = configurationHelper.getDiskStorePath();
        int cachesRequiringDiskStores = configurationHelper.numberOfCachesThatOverflowToDisk().intValue()
                + configurationHelper.numberOfCachesThatAreDiskPersistent().intValue();

        if (diskStorePath == null && cachesRequiringDiskStores > 0) {
            diskStorePath = DiskStoreConfiguration.getDefaultPath();
            LOG.warn("One or more caches require a DiskStore but there is no diskStore element configured."
                    + " Using the default disk store path of " + DiskStoreConfiguration.getDefaultPath()
                    + ". Please explicitly configure the diskStore element in ehcache.xml.");
        }

        FactoryConfiguration lookupConfiguration = configuration.getTransactionManagerLookupConfiguration();
        try {
            Properties properties = PropertyUtil.parseProperties(lookupConfiguration.getProperties(), lookupConfiguration
                    .getPropertySeparator());
            Class<TransactionManagerLookup> transactionManagerLookupClass = (Class<TransactionManagerLookup>) Class
                    .forName(lookupConfiguration.getFullyQualifiedClassPath());
            this.transactionManagerLookup = transactionManagerLookupClass.newInstance();
            this.transactionManagerLookup.setProperties(properties);
        } catch (Exception e) {
            LOG.error("could not instantiate transaction manager lookup class: {}", lookupConfiguration.getFullyQualifiedClassPath(), e);
        }

        detectAndFixDiskStorePathConflict(configurationHelper);

        cacheManagerEventListenerRegistry.registerListener(configurationHelper.createCacheManagerEventListener());

        cacheManagerPeerListeners.putAll(configurationHelper.createCachePeerListeners());
        for (CacheManagerPeerListener cacheManagerPeerListener : cacheManagerPeerListeners.values()) {
            cacheManagerEventListenerRegistry.registerListener(cacheManagerPeerListener);
        }

        detectAndFixCacheManagerPeerListenerConflict(configurationHelper);

        ALL_CACHE_MANAGERS.add(this);

        cacheManagerPeerProviders.putAll(configurationHelper.createCachePeerProviders());
        defaultCache = configurationHelper.createDefaultCache();
    }

    private void detectAndFixDiskStorePathConflict(ConfigurationHelper configurationHelper) {
        if (diskStorePath == null) {
            LOG.debug("No disk store path defined. Skipping disk store path conflict test.");
            return;
        }

        for (CacheManager cacheManager : ALL_CACHE_MANAGERS) {
            if (diskStorePath.equals(cacheManager.diskStorePath)) {
                String newDiskStorePath = diskStorePath + File.separator + DiskStore.generateUniqueDirectory();
                LOG.warn("Creating a new instance of CacheManager using the diskStorePath \"" + diskStorePath + "\" which is already used"
                        + " by an existing CacheManager.\nThe source of the configuration was "
                        + configurationHelper.getConfigurationBean().getConfigurationSource() + ".\n"
                        + "The diskStore path for this CacheManager will be set to " + newDiskStorePath + ".\nTo avoid this"
                        + " warning consider using the CacheManager factory methods to create a singleton CacheManager "
                        + "or specifying a separate ehcache configuration (ehcache.xml) for each CacheManager instance.");
                diskStorePath = newDiskStorePath;
                break;
            }
        }
    }

    private void detectAndFixCacheManagerPeerListenerConflict(ConfigurationHelper configurationHelper) {
        if (cacheManagerPeerListeners == null) {
            return;
        }
        for (CacheManagerPeerListener cacheManagerPeerListener : cacheManagerPeerListeners.values()) {
            String uniqueResourceIdentifier = cacheManagerPeerListener.getUniqueResourceIdentifier();
            for (CacheManager cacheManager : ALL_CACHE_MANAGERS) {
                for (CacheManagerPeerListener otherCacheManagerPeerListener : cacheManager.cacheManagerPeerListeners.values()) {
                    if (otherCacheManagerPeerListener == null) {
                        continue;
                    }
                    String otherUniqueResourceIdentifier = otherCacheManagerPeerListener.getUniqueResourceIdentifier();
                    if (uniqueResourceIdentifier.equals(otherUniqueResourceIdentifier)) {
                        LOG.warn("Creating a new instance of CacheManager with a CacheManagerPeerListener which "
                                + "has a conflict on a resource that must be unique.\n" + "The resource is " + uniqueResourceIdentifier
                                + ".\n" + "Attempting automatic resolution. The source of the configuration was "
                                + configurationHelper.getConfigurationBean().getConfigurationSource() + ".\n"
                                + "To avoid this warning consider using the CacheManager factory methods to create a "
                                + "singleton CacheManager "
                                + "or specifying a separate ehcache configuration (ehcache.xml) for each CacheManager instance.");
                        cacheManagerPeerListener.attemptResolutionOfUniqueResourceConflict();
                        break;
                    }
                }

            }
        }
    }

    private void addConfiguredCaches(ConfigurationHelper configurationHelper) {
        Set unitialisedCaches = configurationHelper.createCaches();
        for (Iterator iterator = unitialisedCaches.iterator(); iterator.hasNext();) {
            Ehcache unitialisedCache = (Ehcache) iterator.next();
            addCacheNoCheck(unitialisedCache, true);

            // add the cache decorators for the cache, if any
            List<Ehcache> cacheDecorators = configurationHelper.createCacheDecorators(unitialisedCache);
            for (Ehcache decoratedCache : cacheDecorators) {
                addOrReplaceDecoratedCache(unitialisedCache, decoratedCache);
            }
        }
    }

    private void addOrReplaceDecoratedCache(final Ehcache underlyingCache, final Ehcache decoratedCache) {
        if (decoratedCache.getName().equals(underlyingCache.getName())) {
            this.replaceCacheWithDecoratedCache(underlyingCache, decoratedCache);
        } else {
            addDecoratedCache(decoratedCache);
        }
    }

    private void reinitialisationCheck() throws IllegalStateException {
        if (diskStorePath != null || ehcaches.size() != 0 || status.equals(Status.STATUS_SHUTDOWN)) {
            throw new IllegalStateException("Attempt to reinitialise the CacheManager");
        }
    }

    /**
     * A factory method to create a singleton CacheManager with default config, or return it if it exists.
     * <p/>
     * The configuration will be read, {@link Ehcache}s created and required stores initialized. When the {@link CacheManager} is no longer
     * required, call shutdown to free resources.
     *
     * @return the singleton CacheManager
     * @throws CacheException
     *             if the CacheManager cannot be created
     */
    public static CacheManager create() throws CacheException {
        if (singleton != null) {
            return singleton;
        }
        synchronized (CacheManager.class) {
            if (singleton == null) {
                LOG.debug("Creating new CacheManager with default config");
                singleton = new CacheManager();
            } else {
                LOG.debug("Attempting to create an existing singleton. Existing singleton returned.");
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
     * @throws CacheException
     *             if the CacheManager cannot be created
     */
    public static CacheManager getInstance() throws CacheException {
        return CacheManager.create();
    }

    /**
     * A factory method to create a singleton CacheManager with a specified configuration.
     *
     * @param configurationFileName
     *            an xml file compliant with the ehcache.xsd schema
     *            <p/>
     *            The configuration will be read, {@link Ehcache}s created and required stores initialized. When the {@link CacheManager} is
     *            no longer required, call shutdown to free resources.
     */
    public static CacheManager create(String configurationFileName) throws CacheException {
        if (singleton != null) {
            return singleton;
        }
        synchronized (CacheManager.class) {
            if (singleton == null) {
                LOG.debug("Creating new CacheManager with config file: {}", configurationFileName);
                singleton = new CacheManager(configurationFileName);
            }
            return singleton;
        }
    }

    /**
     * A factory method to create a singleton CacheManager from an URL.
     * <p/>
     * This method can be used to specify a configuration resource in the classpath other than the default of \"/ehcache.xml\": This method
     * can be used to specify a configuration resource in the classpath other than the default of \"/ehcache.xml\":
     *
     * <pre>
     * URL url = this.getClass().getResource(&quot;/ehcache-2.xml&quot;);
     * </pre>
     *
     * Note that {@link Class#getResource} will look for resources in the same package unless a leading "/" is used, in which case it will
     * look in the root of the classpath.
     * <p/>
     * You can also load a resource using other class loaders. e.g. {@link Thread#getContextClassLoader()}
     *
     * @param configurationFileURL
     *            an URL to an xml file compliant with the ehcache.xsd schema
     *            <p/>
     *            The configuration will be read, {@link Ehcache}s created and required stores initialized. When the {@link CacheManager} is
     *            no longer required, call shutdown to free resources.
     */
    public static CacheManager create(URL configurationFileURL) throws CacheException {
        if (singleton != null) {
            return singleton;
        }
        synchronized (CacheManager.class) {
            if (singleton == null) {
                LOG.debug("Creating new CacheManager with config URL: {}", configurationFileURL);
                singleton = new CacheManager(configurationFileURL);
            }
            return singleton;
        }
    }

    /**
     * A factory method to create a singleton CacheManager from a java.io.InputStream.
     * <p/>
     * This method makes it possible to use an inputstream for configuration. Note: it is the clients responsibility to close the
     * inputstream.
     * <p/>
     *
     * @param inputStream
     *            InputStream of xml compliant with the ehcache.xsd schema
     *            <p/>
     *            The configuration will be read, {@link Ehcache}s created and required stores initialized. When the {@link CacheManager} is
     *            no longer required, call shutdown to free resources.
     */
    public static CacheManager create(InputStream inputStream) throws CacheException {
        if (singleton != null) {
            return singleton;
        }
        synchronized (CacheManager.class) {
            if (singleton == null) {
                LOG.debug("Creating new CacheManager with InputStream");
                singleton = new CacheManager(inputStream);
            }
            return singleton;
        }
    }

    /**
     * A factory method to create a singleton CacheManager from a net.sf.ehcache.config.Configuration.
     * <p/>
     * This method makes it possible to use an inputstream for configuration. Note: it is the clients responsibility to close the
     * inputstream.
     * <p/>
     *
     * @param config
     */
    public static CacheManager create(Configuration config) throws CacheException {
        if (singleton != null) {
            return singleton;
        }
        synchronized (CacheManager.class) {
            if (singleton == null) {
                LOG.debug("Creating new CacheManager with InputStream");
                singleton = new CacheManager(config);
            }
            return singleton;
        }
    }

    /**
     * Returns a concrete implementation of Cache, it it is available in the CacheManager.
     * Consider using getEhcache(String name) instead, which will return decorated caches that are registered.
     * <p/>
     * If a decorated ehcache is registered in CacheManager, an undecorated Cache with the same name may also exist.
     *
     * Since version ehcache-core-2.1.0, when an {@link Ehcache} decorator is present in the CacheManager, its not necessary that a
     * {@link Cache} instance is also present for the same name. Decorators can have different names other than the name of the cache its
     * decorating.
     *
     * @return a Cache, if an object of that type exists by that name, else null
     * @throws IllegalStateException
     *             if the cache is not {@link Status#STATUS_ALIVE}
     * @see #getEhcache(String)
     */
    public Cache getCache(String name) throws IllegalStateException, ClassCastException {
        checkStatus();
        return ehcaches.get(name) instanceof Cache ? (Cache) ehcaches.get(name) : null;
    }

    /**
     * Gets an Ehcache
     * <p/>
     *
     * @return a Cache, if an object of type Cache exists by that name, else null
     * @throws IllegalStateException
     *             if the cache is not {@link Status#STATUS_ALIVE}
     */
    public Ehcache getEhcache(String name) throws IllegalStateException {
        checkStatus();
        return ehcaches.get(name);
    }

    /**
     * Some caches might be persistent, so we want to add a shutdown hook if that is the
     * case, so that the data and index can be written to disk.
     */
    private void addShutdownHookIfRequired() {

        String shutdownHookProperty = System.getProperty(ENABLE_SHUTDOWN_HOOK_PROPERTY);
        boolean enabled = PropertyUtil.parseBoolean(shutdownHookProperty);
        if (!enabled) {
            return;
        } else {
            LOG.info("The CacheManager shutdown hook is enabled because {} is set to true.", ENABLE_SHUTDOWN_HOOK_PROPERTY);

            Thread localShutdownHook = new Thread() {
                @Override
                public void run() {
                    synchronized (this) {
                        if (status.equals(Status.STATUS_ALIVE)) {
                            // clear shutdown hook reference to prevent
                            // removeShutdownHook to remove it during shutdown
                            shutdownHook = null;
                            LOG.info("VM shutting down with the CacheManager still active. Calling shutdown.");
                            shutdown();
                        }
                    }
                }
            };

            Runtime.getRuntime().addShutdownHook(localShutdownHook);
            shutdownHook = localShutdownHook;
        }
    }

    /**
     * Remove the shutdown hook to prevent leaving orphaned CacheManagers around. This
     * is called by {@link #shutdown()} AFTER the status has been set to shutdown.
     */
    private void removeShutdownHook() {
        if (shutdownHook != null) {
            // remove shutdown hook
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException e) {
                // This will be thrown if the VM is shutting down. In this case
                // we do not need to worry about leaving references to CacheManagers lying
                // around and the call is ok to fail.
                LOG.debug("IllegalStateException due to attempt to remove a shutdown" + "hook while the VM is actually shutting down.", e);
            }
            shutdownHook = null;
        }
    }

    /**
     * Adds a {@link Ehcache} based on the defaultCache with the given name.
     * <p/>
     * Memory and Disk stores will be configured for it and it will be added to the map of caches.
     * <p/>
     * Also notifies the CacheManagerEventListener after the cache was initialised and added.
     * <p/>
     * It will be created with the defaultCache attributes specified in ehcache.xml
     *
     * @param cacheName
     *            the name for the cache
     * @throws ObjectExistsException
     *             if the cache already exists
     * @throws CacheException
     *             if there was an error creating the cache.
     */
    public synchronized void addCache(String cacheName) throws IllegalStateException, ObjectExistsException, CacheException {
        checkStatus();

        // NPE guard
        if (cacheName == null || cacheName.length() == 0) {
            return;
        }

        if (ehcaches.get(cacheName) != null) {
            throw new ObjectExistsException("Cache " + cacheName + " already exists");
        }
        Ehcache clonedDefaultCache = cloneDefaultCache(cacheName);
        if (clonedDefaultCache == null) {
            throw new CacheException(NO_DEFAULT_CACHE_ERROR_MSG);
        }
        addCache(clonedDefaultCache);
        for (Ehcache ehcache : createDefaultCacheDecorators(clonedDefaultCache)) {
            addOrReplaceDecoratedCache(clonedDefaultCache, ehcache);
        }
    }

    /**
     * Adds a {@link Cache} to the CacheManager.
     * <p/>
     * Memory and Disk stores will be configured for it and it will be added to the map of caches. Also notifies the
     * CacheManagerEventListener after the cache was initialised and added.
     *
     * @param cache
     * @throws IllegalStateException
     *             if the cache is not {@link Status#STATUS_UNINITIALISED} before this method is called.
     * @throws ObjectExistsException
     *             if the cache already exists in the CacheManager
     * @throws CacheException
     *             if there was an error adding the cache to the CacheManager
     */
    public void addCache(Cache cache) throws IllegalStateException, ObjectExistsException, CacheException {
        checkStatus();
        if (cache == null) {
            return;
        }
        addCache((Ehcache) cache);
    }

    /**
     * Adds an {@link Ehcache} to the CacheManager.
     * <p/>
     * Memory and Disk stores will be configured for it and it will be added to the map of caches. Also notifies the
     * CacheManagerEventListener after the cache was initialised and added.
     *
     * @param cache
     * @throws IllegalStateException
     *             if the cache is not {@link Status#STATUS_UNINITIALISED} before this method is called.
     * @throws ObjectExistsException
     *             if the cache already exists in the CacheManager
     * @throws CacheException
     *             if there was an error adding the cache to the CacheManager
     */
    public synchronized void addCache(Ehcache cache) throws IllegalStateException, ObjectExistsException, CacheException {
        checkStatus();
        if (cache == null) {
            return;
        }
        addCacheNoCheck(cache, true);
    }

    /**
     * Adds a decorated {@link Ehcache} to the CacheManager. This method neither creates the memory/disk store
     * nor initializes the cache. It only adds the cache reference to the map of caches held by this
     * cacheManager.
     * <p/>
     * It is generally required that a decorated cache, once constructed, is made available to other execution threads. The simplest way of
     * doing this is to either add it to the cacheManager with a different name or substitute the original cache with the decorated one.
     * <p/>
     * This method adds the decorated cache assuming it has a different name. If another cache (decorated or not) with the same name already
     * exists, it will throw {@link ObjectExistsException}. For replacing existing cache with another decorated cache having same name,
     * please use {@link #replaceCacheWithDecoratedCache(Ehcache, Ehcache)}
     * <p/>
     * Note that any overridden Ehcache methods by the decorator will take on new behaviours without casting. Casting is only required for
     * new methods that the decorator introduces. For more information see the well known Gang of Four Decorator pattern.
     *
     * @param decoratedCache
     * @throws ObjectExistsException
     *             if another cache with the same name already exists.
     */
    public synchronized void addDecoratedCache(Ehcache decoratedCache) throws ObjectExistsException {
        internalAddDecoratedCache(decoratedCache, true);
    }

    /**
     * Same as {@link #addDecoratedCache(Ehcache)} but does not throw exception if another cache with same name already exists.
     *
     * @param decoratedCache
     * @throws ObjectExistsException
     */
    public synchronized void addDecoratedCacheIfAbsent(Ehcache decoratedCache) throws ObjectExistsException {
        internalAddDecoratedCache(decoratedCache, false);
    }

    private void internalAddDecoratedCache(final Ehcache decoratedCache, final boolean strict) {
        Ehcache old = ehcaches.putIfAbsent(decoratedCache.getName(), decoratedCache);
        if (strict && old != null) {
            throw new ObjectExistsException("Cache " + decoratedCache.getName() + " already exists in the CacheManager");
        }
    }

    private Ehcache addCacheNoCheck(final Ehcache cache, final boolean strict) throws IllegalStateException, ObjectExistsException,
            CacheException {

        if (isTerracottaRejoinEnabled()) {
            validateCacheConfigs(Collections.singletonList(cache.getCacheConfiguration()));
        }

        if (cache.getStatus() != Status.STATUS_UNINITIALISED) {
            throw new CacheException("Trying to add an already initialized cache." + " If you are adding a decorated cache, "
                    + "use CacheManager.addDecoratedCache" + "(Ehcache decoratedCache) instead.");
        }

        Ehcache ehcache = ehcaches.get(cache.getName());
        if (ehcache != null) {
            if (strict) {
                throw new ObjectExistsException("Cache " + cache.getName() + " already exists");
            } else {
                return ehcache;
            }
        }
        cache.setCacheManager(this);
        if (cache.getCacheConfiguration().getDiskStorePath() == null) {
            cache.setDiskStorePath(diskStorePath);
        }
        cache.setTransactionManagerLookup(transactionManagerLookup);

        Map<String, CacheConfiguration> configMap = configuration.getCacheConfigurations();
        if (!configMap.containsKey(cache.getName())) {
            CacheConfiguration cacheConfig = cache.getCacheConfiguration();
            if (cacheConfig != null) {
                configuration.addCache(cacheConfig);
            }
        }

        if (isTerracottaRejoinEnabled() && cache.getCacheConfiguration().isTerracottaClustered()) {
            NonstopConfiguration nsCfg = cache.getCacheConfiguration().getTerracottaConfiguration().getNonstopConfiguration();
            final long timeoutMillis = nsCfg.getTimeoutMillis() * nsCfg.getBulkOpsTimeoutMultiplyFactor();
            try {
                getNonstopExecutorService().execute(new Callable<Void>() {
                    public Void call() throws Exception {
                        cache.initialise();
                        return null;
                    }
                }, timeoutMillis);
            } catch (TimeoutException e) {
                throw new NonStopCacheException("Unable to add cache [" + cache.getCacheConfiguration().getName() + "] within "
                        + timeoutMillis + " msecs", e);
            } catch (InterruptedException e) {
                throw new CacheException(e);
            }
        } else {
            cache.initialise();
        }

        if (!allowsDynamicCacheConfig) {
            cache.disableDynamicFeatures();
        }

        try {
            cache.bootstrap();
        } catch (CacheException e) {
            LOG.warn("Cache " + cache.getName() + "requested bootstrap but a CacheException occured. " + e.getMessage(), e);
        }
        ehcache = ehcaches.putIfAbsent(cache.getName(), cache);
        if (ehcache != null) {
            throw new AssertionError();
        }

        // Don't notify initial config. The init method of each listener should take care of this.
        if (status.equals(Status.STATUS_ALIVE)) {
            cacheManagerEventListenerRegistry.notifyCacheAdded(cache.getName());
        }

        return cache;
    }

    /**
     * Checks whether a cache of type ehcache exists.
     * <p/>
     *
     * @param cacheName
     *            the cache name to check for
     * @return true if it exists
     * @throws IllegalStateException
     *             if the cache is not {@link Status#STATUS_ALIVE}
     */
    public boolean cacheExists(String cacheName) throws IllegalStateException {
        checkStatus();
        return (ehcaches.get(cacheName) != null);
    }

    /**
     * Removes all caches using {@link #removeCache} for each cache.
     */
    public void removalAll() {
        String[] cacheNames = getCacheNames();
        for (String cacheName : cacheNames) {
            removeCache(cacheName);
        }
    }

    /**
     * Remove a cache from the CacheManager. The cache is disposed of.
     *
     * @param cacheName
     *            the cache name
     * @throws IllegalStateException
     *             if the cache is not {@link Status#STATUS_ALIVE}
     */
    public synchronized void removeCache(String cacheName) throws IllegalStateException {
        checkStatus();

        // NPE guard
        if (cacheName == null || cacheName.length() == 0) {
            return;
        }
        Ehcache cache = ehcaches.remove(cacheName);
        if (cache != null && cache.getStatus().equals(Status.STATUS_ALIVE)) {
            cache.dispose();
            configuration.getCacheConfigurations().remove(cacheName);
            cacheManagerEventListenerRegistry.notifyCacheRemoved(cache.getName());
        }
    }

    /**
     * Shuts down the CacheManager.
     * <p/>
     * If the shutdown occurs on the singleton, then the singleton is removed, so that if a singleton access method is called, a new
     * singleton will be created.
     * <p/>
     * By default there is no shutdown hook (ehcache-1.3-beta2 and higher).
     * <p/>
     * Set the system property net.sf.ehcache.enableShutdownHook=true to turn it on.
     */
    public void shutdown() {
        synchronized (CacheManager.class) {
            if (status.equals(Status.STATUS_SHUTDOWN)) {
                LOG.debug("CacheManager already shutdown");
                return;
            }
            for (CacheManagerPeerProvider cacheManagerPeerProvider : cacheManagerPeerProviders.values()) {
                if (cacheManagerPeerProvider != null) {
                    cacheManagerPeerProvider.dispose();
                }
            }

            // cancel the cacheManager timer and all tasks
            if (cacheManagerTimer != null) {
                cacheManagerTimer.cancel();
                cacheManagerTimer.purge();
            }

            cacheManagerEventListenerRegistry.dispose();

            synchronized (CacheManager.class) {
                ALL_CACHE_MANAGERS.remove(this);

                for (Ehcache cache : ehcaches.values()) {
                    if (cache != null) {
                        cache.dispose();
                    }
                }
                if (defaultCache != null) {
                    defaultCache.dispose();
                }
                status = Status.STATUS_SHUTDOWN;
                XARequestProcessor.shutdown();

                // only delete singleton if the singleton is shutting down.
                if (this == singleton) {
                    singleton = null;
                }
                terracottaClient.shutdown();
                transactionController = null;
                removeShutdownHook();
                nonstopExecutorServiceFactory.shutdown(this);
                getCacheRejoinAction().unregisterAll();
            }
        }
    }

    /**
     * Returns a list of the current cache names.
     *
     * @return an array of {@link String}s
     * @throws IllegalStateException
     *             if the cache is not {@link Status#STATUS_ALIVE}
     */
    public String[] getCacheNames() throws IllegalStateException {
        checkStatus();
        String[] list = new String[ehcaches.size()];
        return ehcaches.keySet().toArray(list);
    }

    /**
     * Checks the state of the CacheManager for legal operation
     */
    protected void checkStatus() {
        if (!(status.equals(Status.STATUS_ALIVE))) {
            if (status.equals(Status.STATUS_UNINITIALISED)) {
                throw new IllegalStateException("The CacheManager has not yet been initialised. It cannot be used yet.");
            } else if (status.equals(Status.STATUS_SHUTDOWN)) {
                throw new IllegalStateException("The CacheManager has been shut down. It can no longer be used.");
            }
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
     * Clears the contents of all caches in the CacheManager, but without
     * removing any caches.
     * <p/>
     * This method is not synchronized. It only guarantees to clear those elements in a cache at the time that the
     * {@link Ehcache#removeAll()} mehod on each cache is called.
     */
    public void clearAll() throws CacheException {
        String[] cacheNames = getCacheNames();

        LOG.debug("Clearing all caches");
        for (String cacheName : cacheNames) {
            Ehcache cache = getEhcache(cacheName);
            cache.removeAll();
        }
    }

    /**
     * Clears the contents of all caches in the CacheManager with a name starting with the prefix,
     * but without removing them.
     * <p/>
     * This method is not synchronized. It only guarantees to clear those elements in a cache at the time that the
     * {@link Ehcache#removeAll()} method on each cache is called.
     *
     * @param prefix
     *            The prefix the cache name should start with
     * @throws CacheException
     * @since 1.7.2
     */
    public void clearAllStartingWith(String prefix) throws CacheException {
        // NPE guard
        if (prefix == null || prefix.length() == 0) {
            return;
        }

        for (Object o : ehcaches.entrySet()) {
            Map.Entry entry = (Map.Entry) o;
            String cacheName = (String) entry.getKey();
            if (cacheName.startsWith(prefix)) {
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Clearing cache named '" + cacheName + "' (matches '" + prefix + "' prefix");
                }
                ((Ehcache) entry.getValue()).removeAll();
            }
        }
    }

    /**
     * Gets the <code>CacheManagerPeerProvider</code>, matching the given scheme
     * For distributed caches, the peer provider finds other cache managers and their caches in the same cluster
     *
     * @param scheme
     *            the replication scheme to use. Schemes shipped with ehcache are RMI, JGROUPS, JMS
     * @return the provider, or null if one does not exist
     */
    public CacheManagerPeerProvider getCacheManagerPeerProvider(String scheme) {
        return cacheManagerPeerProviders.get(scheme);
    }

    /**
     * @return Read-only map of the registered {@link CacheManagerPeerProvider}s keyed by scheme.
     */
    public Map<String, CacheManagerPeerProvider> getCacheManagerPeerProviders() {
        return Collections.unmodifiableMap(this.cacheManagerPeerProviders);
    }

    /**
     * When CacheManage is configured as part of a cluster, a CacheManagerPeerListener will
     * be registered in it. Use this to access the individual cache listeners
     *
     * @param scheme
     *            the replication scheme to use. Schemes shipped with ehcache are RMI, JGROUPS, JMS
     * @return the listener, or null if one does not exist
     */
    public CacheManagerPeerListener getCachePeerListener(String scheme) {
        return cacheManagerPeerListeners.get(scheme);
    }

    /**
     * Returns the composite listener. A notification sent to this listener will notify all registered
     * listeners.
     *
     * @return null if none
     * @see "getCacheManagerEventListenerRegistry"
     */
    public CacheManagerEventListener getCacheManagerEventListener() {
        return cacheManagerEventListenerRegistry;
    }

    /**
     * Same as getCacheManagerEventListenerRegistry().registerListener(cacheManagerEventListener);
     * Left for backward compatiblity
     *
     * @param cacheManagerEventListener
     *            the listener to set.
     * @see "getCacheManagerEventListenerRegistry"
     */
    public void setCacheManagerEventListener(CacheManagerEventListener cacheManagerEventListener) {
        getCacheManagerEventListenerRegistry().registerListener(cacheManagerEventListener);
    }

    /**
     * Gets the CacheManagerEventListenerRegistry. Add and remove listeners here.
     */
    public CacheManagerEventListenerRegistry getCacheManagerEventListenerRegistry() {
        return cacheManagerEventListenerRegistry;
    }

    /**
     * Replaces in the map of Caches managed by this CacheManager an Ehcache with a decorated version of the same
     * Ehcache. CacheManager can operate fully with a decorated Ehcache.
     * <p/>
     * Ehcache Decorators can be used to obtain different behaviour from an Ehcache in a very flexible way. Some examples in ehcache are:
     * <ol>
     * <li>{@link net.sf.ehcache.constructs.blocking.BlockingCache} - A cache that blocks other threads from getting a null element until
     * the first thread has placed a value in it.
     * <li>{@link net.sf.ehcache.constructs.blocking.SelfPopulatingCache} - A BlockingCache that has the additional property of knowing how
     * to load its own entries.
     * </ol>
     * Many other kinds are possible.
     * <p/>
     * It is generally required that a decorated cache, once constructed, is made available to other execution threads. The simplest way of
     * doing this is to substitute the original cache for the decorated one here.
     * <p/>
     * Note that any overwritten Ehcache methods will take on new behaviours without casting. Casting is only required for new methods that
     * the decorator introduces. For more information see the well known Gang of Four Decorator pattern.
     *
     * @param ehcache
     * @param decoratedCache
     *            An implementation of Ehcache that wraps the original cache.
     * @throws CacheException
     *             if the two caches do not equal each other.
     */
    public synchronized void replaceCacheWithDecoratedCache(Ehcache ehcache, Ehcache decoratedCache) throws CacheException {
        if (!ehcache.equals(decoratedCache)) {
            throw new CacheException("Cannot replace " + decoratedCache.getName() + " It does not equal the incumbent cache.");
        }

        String cacheName = ehcache.getName();
        if (!ehcaches.replace(cacheName, ehcache, decoratedCache)) {
            if (cacheExists(cacheName)) {
                throw new CacheException("Cache '" + ehcache.getName() + "' managed with this CacheManager doesn't match!");
            } else {
                throw new CacheException("Cache '" + cacheName + "' isn't associated with this manager (anymore?)");
            }
        }
    }

    /**
     * Gets the name of the CacheManager. This is useful for distinguishing multiple CacheManagers
     *
     * @return the name, or the output of toString() if it is not set.
     * @see #toString() which uses either the name or Object.toString()
     */
    public String getName() {
        if (name != null) {
            return name;
        } else {
            return super.toString();
        }
    }

    /**
     * Indicate whether the CacheManager is named or not.
     *
     * @return True if named
     */
    public boolean isNamed() {
        return name != null;
    }

    /**
     * Sets the name of the CacheManager. This is useful for distinguishing multiple CacheManagers
     * in a monitoring situation.
     *
     * @param name
     *            a name with characters legal in a JMX ObjectName
     */
    public void setName(String name) {
        this.name = name;
        try {
            mbeanRegistrationProvider.reinitialize(terracottaClient.getClusteredInstanceFactory());
        } catch (MBeanRegistrationProviderException e) {
            throw new CacheException("Problem in reinitializing MBeanRegistrationProvider - "
                    + mbeanRegistrationProvider.getClass().getName(), e);
        }
    }

    /**
     * @return either the name of this CacheManager, or if unset, Object.toString()
     */
    @Override
    public String toString() {
        return getName();
    }

    /**
     * Returns the disk store path. This may be null if no caches need a DiskStore and none was configured.
     * The path cannot be changed after creation of the CacheManager. All caches take the disk store path
     * from this value.
     *
     * @return the disk store path.
     */
    public String getDiskStorePath() {
        return diskStorePath;
    }

    /**
     * Returns a {@link FailSafeTimer} associated with this {@link CacheManager}
     *
     * @return The {@link FailSafeTimer} associated with this cache manager
     * @since 1.7
     */
    public FailSafeTimer getTimer() {
        return cacheManagerTimer;
    }

    /**
     * Returns access to information about the cache cluster.
     *
     * @param scheme The clustering scheme to retrieve information about (such as "Terracotta")
     * @return Cluster API (never null, but possibly a simple single node implementation)
     * @throws ClusterSchemeNotAvailableException If the CacheCluster specified by scheme is not available.
     * @see ClusterScheme
     * @since 2.0
     */
    public CacheCluster getCluster(ClusterScheme scheme) throws ClusterSchemeNotAvailableException {
        switch (scheme) {
            case TERRACOTTA:
                if (null == terracottaClient.getClusteredInstanceFactory()) {
                    throw new ClusterSchemeNotAvailableException(ClusterScheme.TERRACOTTA, "Terracotta cluster scheme is not available");
                }
                return terracottaClient.getCacheCluster();
            default:
                return NoopCacheCluster.INSTANCE;
        }
    }

    /**
     * Returns the original configuration text for this {@link CacheManager}
     *
     * @return Returns the original configuration text for this {@link CacheManager}
     */
    public String getOriginalConfigurationText() {
        if (configuration.getConfigurationSource() == null) {
            return "Originally configured programmatically. No original configuration source text.";
        } else {
            Configuration originalConfiguration = configuration.getConfigurationSource().createConfiguration();
            return ConfigurationUtil.generateCacheManagerConfigurationText(originalConfiguration);
        }
    }

    /**
     * Returns the active configuration text for this {@link CacheManager}
     *
     * @return Returns the active configuration text for this {@link CacheManager}
     */
    public String getActiveConfigurationText() {
        return ConfigurationUtil.generateCacheManagerConfigurationText(configuration);
    }

    /**
     * Returns the original configuration text for the input cacheName
     *
     * @param cacheName
     * @return Returns the original configuration text for the input cacheName
     * @throws CacheException if the cache with <code>cacheName</code> does not exist in the original config
     */
    public String getOriginalConfigurationText(String cacheName) throws CacheException {
        if (configuration.getConfigurationSource() == null) {
            return "Originally configured programmatically. No original configuration source text.";
        } else {
            Configuration originalConfiguration = configuration.getConfigurationSource().createConfiguration();
            CacheConfiguration cacheConfiguration = originalConfiguration.getCacheConfigurations().get(cacheName);
            if (cacheConfiguration == null) {
                throw new CacheException("Cache with name '" + cacheName + "' does not exist in the original configuration");
            }
            return ConfigurationUtil.generateCacheConfigurationText(cacheConfiguration);
        }
    }

    /**
     * Returns the active configuration text for the input cacheName
     *
     * @param cacheName
     * @return Returns the active configuration text for the input cacheName
     * @throws CacheException if the cache with <code>cacheName</code> does not exist
     */
    public String getActiveConfigurationText(String cacheName) throws CacheException {
        CacheConfiguration config = configuration.getCacheConfigurations().get(cacheName);
        if (config == null) {
            throw new CacheException("Cache with name '" + cacheName + "' does not exist");
        }
        return ConfigurationUtil.generateCacheConfigurationText(config);
    }

    /**
     * Get the CacheManager configuration
     *
     * @return the configuration
     */
    Configuration getConfiguration() {
        return configuration;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public int hashCode() {
        if (name != null) {
            return name.hashCode();
        } else {
            return super.hashCode();
        }
    }

    /**
     * Only adds the cache to the CacheManager should not one with the same name already be present
     *
     * @param cache The Ehcache to be added
     * @return the instance registered with the CacheManager, the cache instance passed in if it was added; or null if Ehcache is null
     */
    public synchronized Ehcache addCacheIfAbsent(final Ehcache cache) {
        checkStatus();
        return cache == null ? null : addCacheNoCheck(cache, false);
    }

    /**
     * Only creates and adds the cache to the CacheManager should not one with the same name already be present
     *
     * @param cacheName the name of the Cache to be created
     * @return the Ehcache instance created and registered; null if cacheName was null or of length 0
     */
    public synchronized Ehcache addCacheIfAbsent(final String cacheName) {
        checkStatus();

        // NPE guard
        if (cacheName == null || cacheName.length() == 0) {
            return null;
        }

        Ehcache ehcache = ehcaches.get(cacheName);
        if (ehcache == null) {
            Ehcache clonedDefaultCache = cloneDefaultCache(cacheName);
            if (clonedDefaultCache == null) {
                throw new CacheException(NO_DEFAULT_CACHE_ERROR_MSG);
            }
            addCacheIfAbsent(clonedDefaultCache);
            for (Ehcache createdCache : createDefaultCacheDecorators(clonedDefaultCache)) {
                addOrReplaceDecoratedCache(clonedDefaultCache, createdCache);
            }
        }
        return ehcaches.get(cacheName);
    }

    private Ehcache cloneDefaultCache(final String cacheName) {
        if (defaultCache == null) {
            return null;
        }
        Ehcache cache;
        try {
            cache = (Ehcache) defaultCache.clone();
        } catch (CloneNotSupportedException e) {
            throw new CacheException("Failure cloning default cache. Initial cause was " + e.getMessage(), e);
        }
        if (cache != null) {
            cache.setName(cacheName);
        }
        return cache;
    }

    private List<Ehcache> createDefaultCacheDecorators(Ehcache underlyingCache) {
        return ConfigurationHelper.createDefaultCacheDecorators(underlyingCache, configuration.getDefaultCacheConfiguration());
    }

    /**
     * Get the TransactionController
     *
     * @return the TransactionController
     */
    public TransactionController getTransactionController() {
        return transactionController;
    }

    /**
     * Create a TransactionIDFactory
     *
     * @return a TransactionIDFactory
     */
    TransactionIDFactory createTransactionIDFactory() {
        return new DelegatingTransactionIDFactory(terracottaClient);
    }

    /**
     * Create a soft lock factory for a specific cache
     *
     * @param cache the cache to create the soft lock factory for
     * @return a SoftLockFactory
     */
    SoftLockFactory createSoftLockFactory(Ehcache cache) {
        SoftLockFactory softLockFactory;
        if (cache.getCacheConfiguration().isTerracottaClustered()) {
            softLockFactory = getClusteredInstanceFactory(cache).getOrCreateSoftLockFactory(cache.getName());
        } else {
            softLockFactory = softLockFactories.get(cache.getName());
            if (softLockFactory == null) {
                softLockFactory = new ReadCommittedSoftLockFactoryImpl(cache.getName());
                SoftLockFactory old = softLockFactories.putIfAbsent(cache.getName(), softLockFactory);
                if (old != null) {
                    softLockFactory = old;
                }
            }
        }
        return softLockFactory;
    }

    /**
     * Return the {@link NonstopExecutorService} associated with this cacheManager
     * @return the {@link NonstopExecutorService} associated with this cacheManager
     */
    protected NonstopExecutorService getNonstopExecutorService() {
        return nonstopExecutorServiceFactory.getOrCreateNonstopExecutorService(this);
    }

    /**
     * Get the CacheRejoinAction
     * @return the CacheRejoinAction
     */
    CacheRejoinAction getCacheRejoinAction() {
        return cacheRejoinAction;
    }

    /**
     * Class which handles rejoin events and notifies Caches implementations about them.
     */
    class CacheRejoinAction implements TerracottaClientRejoinListener {
        private final Collection<WeakReference<Cache>> caches = new CopyOnWriteArrayList<WeakReference<Cache>>();

        /**
         * {@inheritDoc}
         */
        public void clusterRejoinStarted() {
            // send clusterRejoinStarted event to all TC clustered caches
            Collection<WeakReference<Cache>> toRemove = new ArrayList<WeakReference<Cache>>();
            for (final WeakReference<Cache> cacheRef : caches) {
                Cache cache = cacheRef.get();
                if (cache == null) {
                    toRemove.add(cacheRef);
                    continue;
                }
                if (cache.getCacheConfiguration().isTerracottaClustered()) {
                    cache.clusterRejoinStarted();
                }
            }
            caches.removeAll(toRemove);

            // shutdown the current nonstop executor service
            nonstopExecutorServiceFactory.shutdown(CacheManager.this);
        }

        /**
         * {@inheritDoc}
         */
        public void clusterRejoinComplete() {
            // restart nonstop executor service
            nonstopExecutorServiceFactory.getOrCreateNonstopExecutorService(CacheManager.this);

            // send clusterRejoinComplete event to all TC clustered caches
            Collection<WeakReference<Cache>> toRemove = new ArrayList<WeakReference<Cache>>();
            for (final WeakReference<Cache> cacheRef : caches) {
                Cache cache = cacheRef.get();
                if (cache == null) {
                    toRemove.add(cacheRef);
                    continue;
                }
                if (cache.getCacheConfiguration().isTerracottaClustered()) {
                    cache.clusterRejoinComplete();
                }
            }
            caches.removeAll(toRemove);

            if (mbeanRegistrationProvider.isInitialized()) {
                // re-register mbeans
                try {
                    mbeanRegistrationProvider.reinitialize(terracottaClient.getClusteredInstanceFactory());
                } catch (MBeanRegistrationProviderException e) {
                    throw new CacheException("Problem in reinitializing MBeanRegistrationProvider - "
                            + mbeanRegistrationProvider.getClass().getName(), e);
                }
            }
            // recreate TransactionController with fresh TransactionIDFactory
            transactionController = new TransactionController(createTransactionIDFactory(), configuration.getDefaultTransactionTimeoutInSeconds());
        }

        /**
         * Register a Cache implementation
         *
         * @param cache the cache
         */
        public void register(Cache cache) {
            caches.add(new WeakReference<Cache>(cache));
        }

        /**
         * Unregister a Cache implementation
         *
         * @param cache the cache
         */
        public void unregister(Cache cache) {
            Collection<WeakReference<Cache>> toRemove = new ArrayList<WeakReference<Cache>>();
            for (final WeakReference<Cache> cacheRef : caches) {
                Cache c = cacheRef.get();
                if (c == null) {
                    toRemove.add(cacheRef);
                    continue;
                }

                if (c == cache) {
                    toRemove.add(cacheRef);
                    return;
                }
            }
            caches.removeAll(toRemove);
        }

        /**
         * Unregister all caches
         */
        public void unregisterAll() {
            caches.clear();
        }
    }

}
