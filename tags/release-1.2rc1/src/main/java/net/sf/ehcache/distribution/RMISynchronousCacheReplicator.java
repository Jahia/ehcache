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
import net.sf.ehcache.Element;
import net.sf.ehcache.Status;

import java.io.Serializable;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Listens to {@link net.sf.ehcache.CacheManager} and {@link net.sf.ehcache.Cache} events and propagates those to
 * {@link CachePeer} peers of the Cache.
 *
 * @author Greg Luck
 * @version $Id$
 */
public class RMISynchronousCacheReplicator implements CacheReplicator {

    private static final Log LOG = LogFactory.getLog(RMISynchronousCacheReplicator.class.getName());


    /**
     * The status of the replicator. Only replicates when <code>STATUS_ALIVE</code>
     */
    protected Status status;

    /**
     * Whether to replicate puts.
     */
    protected boolean replicatePuts;

    /**
     * Whether to replicate updates.
     */
    protected boolean replicateUpdates;

    /**
     * Whether an update (a put) should be by copy or by invalidation, (a remove).
     * <p/>
     * By copy is best when the entry is expensive to produce. By invalidation is best when
     * we are really trying to force other caches to sync back to a canonical source like a database.
     * An example of a latter usage would be a read/write cache being used in Hibernate.
     * <p/>
     * This setting only has effect if <code>#replicateUpdates</code> is true.
     */
    protected final boolean replicateUpdatesViaCopy;

    /**
     * Whether to replicate removes
     */
    protected boolean replicateRemovals;

    /**
     * Constructor for internal and subclass use
     *
     * @param replicatePuts
     * @param replicateUpdates
     * @param replicateUpdatesViaCopy
     * @param replicateRemovals
     */
    protected RMISynchronousCacheReplicator(
            boolean replicatePuts,
            boolean replicateUpdates,
            boolean replicateUpdatesViaCopy,
            boolean replicateRemovals) {
        this.replicatePuts = replicatePuts;
        this.replicateUpdates = replicateUpdates;
        this.replicateUpdatesViaCopy = replicateUpdatesViaCopy;
        this.replicateRemovals = replicateRemovals;
        status = Status.STATUS_ALIVE;
    }

    /**
     * Called immediately after an element has been put into the cache. The {@link net.sf.ehcache.Cache#put(net.sf.ehcache.Element)} method
     * will block until this method returns.
     * <p/>
     * Implementers may wish to have access to the Element's fields, including value, so the element is provided.
     * Implementers should be careful not to modify the element. The effect of any modifications is undefined.
     *
     * @param cache   the cache emitting the notification
     * @param element the element which was just put into the cache.
     */
    public void notifyElementPut(final Cache cache, final Element element) throws CacheException {
        if (notAlive()) {
            return;
        }

        if (!replicatePuts) {
            return;
        }

        if (!element.isSerializable()) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Object with key " + element.getKey() + " is not Serializable and cannot be replicated");
            }
            return;
        }


        replicatePutNotification(cache, element);
    }

    /**
     * Does the actual RMI remote call
     *
     * @param element
     * @throws RemoteCacheException if anything goes wrong with the remote call
     */
    protected static void replicatePutNotification(Cache cache, Element element) throws RemoteCacheException {
        List cachePeers = listRemoteCachePeers(cache);
        for (int i = 0; i < cachePeers.size(); i++) {
            CachePeer cachePeer = (CachePeer) cachePeers.get(i);
            try {
                cachePeer.put(element);
            } catch (Throwable t) {
                throw new RemoteCacheException("Error doing put to remote peer. Message was: " + t.getMessage());
            }
        }
    }


    /**
     * Called immediately after an element has been put into the cache and the element already
     * existed in the cache. This is thus an update.
     * <p/>
     * The {@link net.sf.ehcache.Cache#put(net.sf.ehcache.Element)} method
     * will block until this method returns.
     * <p/>
     * Implementers may wish to have access to the Element's fields, including value, so the element is provided.
     * Implementers should be careful not to modify the element. The effect of any modifications is undefined.
     *
     * @param cache   the cache emitting the notification
     * @param element the element which was just put into the cache.
     */
    public void notifyElementUpdated(final Cache cache, final Element element) throws CacheException {
        if (notAlive()) {
            return;
        }
        if (!replicateUpdates) {
            return;
        }

        if (replicateUpdatesViaCopy) {
            if (!element.isSerializable()) {
                if (LOG.isWarnEnabled()) {
                    LOG.warn("Object with key " + element.getKey() + " is not Serializable and cannot be updated via copy");
                }
                return;
            }

            replicatePutNotification(cache, element);
        } else {
            if (!element.isKeySerializable()) {
                if (LOG.isWarnEnabled()) {
                    LOG.warn("Key " + element.getKey() + " is not Serializable and cannot be replicated.");
                }
                return;
            }

            replicateRemovalNotification(cache, (Serializable) element.getKey());
        }
    }

    /**
     * Called immediately after an element has been removed. The remove method will block until
     * this method returns.
     * <p/>
     * Ehcache does not check for
     * <p/>
     * As the {@link net.sf.ehcache.Element} has been removed, only what was the key of the element is known.
     * <p/>
     *
     * @param cache   the cache emitting the notification
     * @param element just deleted
     */
    public void notifyElementRemoved(final Cache cache, final Element element) throws CacheException {
        if (notAlive()) {
            return;
        }

        if (!replicateRemovals) {
            return;
        }

        if (!element.isKeySerializable()) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Key " + element.getKey() + " is not Serializable and cannot be replicated.");
            }
            return;
        }

        replicateRemovalNotification(cache, (Serializable) element.getKey());
    }

    /**
     * Does the actual RMI remote call
     *
     * @param key
     * @throws RemoteCacheException if anything goes wrong with the remote call
     */
    protected static void replicateRemovalNotification(Cache cache, Serializable key) throws RemoteCacheException {
        List cachePeers = listRemoteCachePeers(cache);
        for (int i = 0; i < cachePeers.size(); i++) {
            CachePeer cachePeer = (CachePeer) cachePeers.get(i);
            try {
                cachePeer.remove(key);
            } catch (Throwable e) {
                throw new RemoteCacheException("Error doing remove to remote peer. Message was: " + e.getMessage());
            }
        }
    }

    /**
     * Package protected List of cache peers
     * @param cache
     * @return
     */
    static List listRemoteCachePeers(Cache cache) {
        CacheManagerPeerProvider provider = cache.getCacheManager().getCachePeerProvider();
        return provider.listRemoteCachePeers(cache);
    }

    /**
     * {@inheritDoc}
     * <p/>
     * This implementation does not propagate expiries. It does not need to do anything because the element will
     * expire in the remote cache at the same time. If the remote peer is not configured the same way they should
     * not be in an cache cluster.
     */
    public final void notifyElementExpired(final Cache cache, final Element element) {
        /*do not propagate expiries. The element should expire in the remote cache at the same time, thus
          preseerving coherency.
          */
    }

    /**
     * @return whether update is through copy or invalidate
     */
    public final boolean isReplicateUpdatesViaCopy() {
        return replicateUpdatesViaCopy;
    }

    /**
     * Asserts that the replicator is active.
     *
     * @return true if the status is not STATUS_ALIVE
     */
    public final boolean notAlive() {
        return !status.equals(Status.STATUS_ALIVE);
    }

    /**
     * Checks that the replicator is is <code>STATUS_ALIVE</code>.
     */
    public final boolean alive() {
        return (status.equals(Status.STATUS_ALIVE));
    }

    /**
     * Give the replicator a chance to cleanup and free resources when no longer needed
     */
    public void dispose() {
        status = Status.STATUS_SHUTDOWN;
    }
}
