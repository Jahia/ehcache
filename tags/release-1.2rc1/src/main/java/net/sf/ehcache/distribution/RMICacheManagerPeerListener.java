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

package net.sf.ehcache.distribution;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheException;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.event.CacheEventListener;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.rmi.Naming;
import java.rmi.Remote;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.rmi.server.ExportException;
import java.rmi.server.UnicastRemoteObject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * A cache server which exposes available cache operations remotely through RMI.
 * <p/>
 * It acts as a Decorator to a Cache. It holds an instance of cache, which is a local cache it talks to.
 * <p/>
 * This class could specify a security manager with code like:
 * <pre>
 * if (System.getSecurityManager() == null) {
 *     System.setSecurityManager(new RMISecurityManager());
 * }
 * </pre>
 * Doing so would require the addition of <code>grant</code> statements in the <code>java.policy</code> file.
 * <p/>
 * Per the JDK documentation: "If no security manager is specified no class loading, by RMI clients or servers, is allowed,
 * aside from what can be found in the local CLASSPATH." The classpath of each instance of this class should have
 * all required classes to enable distribution, so no remote classloading is required or desirable. Accordingly,
 * no security manager is set and there are no special JVM configuration requirements.
 * <p/>
 *
 * @author Greg Luck
 * @version $Id$
 */
public class RMICacheManagerPeerListener implements CacheManagerPeerListener {

    private static final Log LOG = LogFactory.getLog(RMICacheManagerPeerListener.class.getName());
    private static final int MINIMUM_SENSIBLE_TIMEOUT = 200;

    private Registry registry;

    private String hostName;
    private Integer port;
    private CacheManager cacheManager;
    private Integer socketTimeoutMillis;

    private List cachePeers = new ArrayList();

    /**
     * Constructor with full arguements
     *
     * @param hostName            may be null, in which case the hostName will be looked up. Machines with multiple
     *                            interfaces should specify this if they do not want it to be the default NIC.
     * @param port                a port in the range 1025 - 65536
     * @param cacheManager        the CacheManager this listener belongs to
     * @param socketTimeoutMillis TCP/IP Socket timeout when waiting on response
     */
    public RMICacheManagerPeerListener(String hostName, Integer port, CacheManager cacheManager,
                                       Integer socketTimeoutMillis) throws UnknownHostException {
        if (hostName != null && hostName.length() != 0) {
            this.hostName = hostName;
        } else {
            this.hostName = calculateHostAddress();
        }
        if (port == null) {
            throw new IllegalArgumentException("port must be specified in the range 1025 - 65536");
        } else {
            this.port = port;
        }
        this.cacheManager = cacheManager;
        if (socketTimeoutMillis == null || socketTimeoutMillis.intValue() < MINIMUM_SENSIBLE_TIMEOUT) {
            throw new IllegalArgumentException("socketTimoutMillis must be a reasonable value greater than 200ms");
        }
        this.socketTimeoutMillis = socketTimeoutMillis;

    }


    private String calculateHostAddress() throws UnknownHostException {
        return InetAddress.getLocalHost().getHostAddress();
    }


    /**
     * {@inheritDoc}
     */
    public void init() throws CacheException {
        RMICachePeer rmiCachePeer = null;
        try {
            startRegistry();
            populateListOfRemoteCachePeers();
            for (int i = 0; i < cachePeers.size(); i++) {
                rmiCachePeer = (RMICachePeer) cachePeers.get(i);
                Naming.rebind(rmiCachePeer.getUrl(), rmiCachePeer);
            }
            LOG.debug("Server bound in registry");
        } catch (Exception e) {
            throw new CacheException("Problem starting listener for RMICachePeer "
                    + rmiCachePeer.getUrl() + ". Initial cause was " + e.getMessage(), e);
        }
    }

    /**
     * Returns a list of bound objects.
     * <p/>
     * This should match the list of cachePeers i.e. they should always be bound
     * @return a list of String representations of <code>RMICachePeer</code> objects
     */
    String[] listBoundRMICachePeers() throws CacheException {
        try {
            return registry.list();
        } catch (RemoteException e) {
            throw new CacheException("Unable to list cache peers " + e.getMessage());
        }
    }

    /**
     * Returns a reference to the remote object.
     * @param name the name of the cache e.g. <code>sampleCache1</code>
     */
    Remote lookupPeer(String name) throws CacheException {
        try {
            return (Remote) registry.lookup(name);
        } catch (Exception e) {
            throw new CacheException("Unable to lookup peer for replicated cache " + name + " "
                    + e.getMessage());
        }
    }

    /**
     * Should be called on init because this is one of the last things that should happen on CacheManager
     * startup
     */
    private void populateListOfRemoteCachePeers() throws RemoteException {
        String[] names = cacheManager.getCacheNames();
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            Cache cache = cacheManager.getCache(name);
            if (isDistributed(cache)) {
                RMICachePeer peer = new RMICachePeer(cache, hostName, port, socketTimeoutMillis);
                cachePeers.add(peer);
            }
        }

    }

    /**
     * Determine if the given cache is distributed.
     *
     * @param cache the cache to check
     * @return true if a <code>CacheReplicator</code> is found in the listeners
     */
    private boolean isDistributed(Cache cache) {
        Set listeners = cache.getCacheEventNotificationService().getCacheEventListeners();
        for (Iterator iterator = listeners.iterator(); iterator.hasNext();) {
            CacheEventListener cacheEventListener = (CacheEventListener) iterator.next();
            if (cacheEventListener instanceof CacheReplicator) {
                return true;
            }
        }
        return false;
    }

    /**
     * Start the rmiregistry
     * <p/>
     * The alternative is to use the <code>rmiregistry</code> binary, in which case:
     * <ol/>
     * <li>rmiregistry running
     * <li>-Djava.rmi.server.codebase="file:///Users/gluck/work/ehcache/build/classes/ file:///Users/gluck/work/ehcache/lib/commons-logging-1.0.4.jar"
     * </ol>
     * There appears to be no way to stop an rmiregistry. We check to see if one if already "there"
     * before we create a new one.
     *
     * @throws RemoteException
     */
    private void startRegistry() throws RemoteException {
        try {
            registry = LocateRegistry.getRegistry(port.intValue());
            try {
                registry.list();
            } catch (RemoteException e) {
                //may not be created. Let's create it.
                registry = LocateRegistry.createRegistry(port.intValue());
            }
        } catch (ExportException exception) {
            LOG.fatal("Exception starting RMI registry. Error was " + exception.getMessage(), exception);
        }
    }

    /**
     * Stop the listener. It
     * <ul>
     * <li>unexports Remote objects
     * <li>unbinds the objects from the registry
     * </ul>
     */
    public void dispose() throws CacheException {
        try {
            for (int i = 0; i < cachePeers.size(); i++) {
                RMICachePeer rmiCachePeer = (RMICachePeer) cachePeers.get(i);
                UnicastRemoteObject.unexportObject(rmiCachePeer, false);
                Naming.unbind(rmiCachePeer.getUrl());
            }
            LOG.debug("Server unbound in registry");
        } catch (Exception e) {
            throw new CacheException("Problem unbinding remote cache peers. Initial cause was " + e.getMessage(), e);
        }
    }

    /**
     * All of the caches which are listenting for remote changes.
     *
     * @return a list of <code>RMICachePeer</code> objects
     */
    public List getBoundCachePeers() {
        return cachePeers;
    }

    /**
     * Gets a list of cache peers
     */
    List getCachePeers() {
        return cachePeers;
    }
}
