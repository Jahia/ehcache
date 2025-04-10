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

import java.io.Serializable;
import java.util.LinkedList;
import java.util.List;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheException;
import net.sf.ehcache.Element;
import net.sf.ehcache.Status;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * Listens to {@link net.sf.ehcache.CacheManager} and {@link net.sf.ehcache.Cache} events and propagates those to
 * {@link CachePeer} peers of the Cache asynchronously.
 * <p/>
 * Updates are guaranteed to be replicated in the order in which they are received.
 *
 * @author Greg Luck
 * @version $Id$
 */
public class RMIAsynchronousCacheReplicator extends RMISynchronousCacheReplicator {

    /**
     * The amount of time the replication thread sleeps after it detects the replicationQueue is empty
     * before checking again.
     */
    protected static final int REPLICATION_THREAD_INTERVAL = 200;

    private static final Log LOG = LogFactory.getLog(RMIAsynchronousCacheReplicator.class.getName());

    /**
     * A thread which handles replication, so that replication can take place asynchronously and not hold up the cache
     */
    protected final Thread replicationThread = new ReplicationThread();

    /**
     * A queue of updates.
     */
    protected final List replicationQueue = new LinkedList();

    /**
     * Constructor for internal and subclass use
     *
     * @param replicatePuts
     * @param replicateUpdates
     * @param replicateUpdatesViaCopy
     * @param replicateRemovals
     */
    protected RMIAsynchronousCacheReplicator(
            boolean replicatePuts,
            boolean replicateUpdates,
            boolean replicateUpdatesViaCopy,
            boolean replicateRemovals) {
        super(replicatePuts,
                replicateUpdates,
                replicateUpdatesViaCopy,
                replicateRemovals);
        status = Status.STATUS_ALIVE;
        replicationThread.start();
    }

    /**
     * Main method for the replicationQueue thread.
     * <p/>
     * Note that the replicationQueue thread locks the cache for the entire time it is writing elements to the disk.
     */
    private void replicationThreadMain() {
        while (true) {
            // Wait for elements in the replicationQueue
            while (alive() && replicationQueue.size() == 0) {
                try {
                    Thread.sleep(REPLICATION_THREAD_INTERVAL);
                } catch (InterruptedException e) {
                    LOG.info("Spool Thread interrupted.");
                    return;
                }
            }
            if (notAlive()) {
                return;
            }
            if (replicationQueue.size() != 0) {
                flushReplicationQueue();
            }
        }
    }


    /**
     * {@inheritDoc}
     * <p/>
     * This implementation queues the put notification for in-order replication to peers.
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
        
        synchronized (replicationQueue) {
            replicationQueue.add(new EventMessage(EventMessage.PUT, cache, element));
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
            replicationQueue.add(new EventMessage(EventMessage.PUT, cache, element));
        } else {
            if (!element.isKeySerializable()) {
                if (LOG.isWarnEnabled()) {
                    LOG.warn("Key " + element.getKey() + " is not Serializable and cannot be replicated.");
                }
                return;
            }
            replicationQueue.add(new EventMessage(EventMessage.REMOVE, cache, (Serializable) element.getKey()));
        }
    }

    /**
     * Called immediately after an element has been removed. The remove method will block until
     * this method returns.
     * <p/>
     * This implementation queues the removal notification for in order replication to peers.
     *
     * @param cache   the cache emitting the notification
     * @param element just deleted
     */
    public void notifyElementRemoved(final Cache cache, final Element element) throws CacheException {
        if (!replicateRemovals) {
            return;
        }

        if (!element.isKeySerializable()) {
            if (LOG.isWarnEnabled()) {
                LOG.warn("Key " + element.getKey() + " is not Serializable and cannot be replicated.");
            }
            return;
        }
        synchronized (replicationQueue) {
            replicationQueue.add(new EventMessage(EventMessage.REMOVE, cache, (Serializable) element.getKey()));
        }
    }

    /**
     * Makes a copy of the queue so as not to hold up the enqueue operations.
     */
    private void flushReplicationQueue() {
        Object[] replicationQueueCopy;
        synchronized (replicationQueue) {
            replicationQueueCopy = replicationQueue.toArray();
            replicationQueue.clear();
        }
        for (int i = 0; i < replicationQueueCopy.length; i++) {
            final EventMessage eventMessage = (EventMessage) replicationQueueCopy[i];
            try {
                if (eventMessage.event == EventMessage.PUT) {
                    replicatePutNotification(eventMessage.cache, eventMessage.element);
                } else {
                    replicateRemovalNotification(eventMessage.cache, eventMessage.key);
                }
            } catch (CacheException e) {
                LOG.warn(e.getMessage());
            }
        }
    }


    /**
     * A background daemon thread that writes objects to the file.
     */
    private class ReplicationThread extends Thread {
        public ReplicationThread() {
            super("Replication Thread");
            setDaemon(true);
            setPriority(2);
        }

        /**
         * Main thread method.
         */
        public void run() {
            replicationThreadMain();
        }
    }

    /**
     * An Event Message, which enables the element to enqueued along with
     * what is to be done with it.
     */
    private class EventMessage {

        private static final int PUT = 0;
        private static final int REMOVE = 1;

        private int event;
        private Cache cache;
        private Element element;
        private Serializable key;

        public EventMessage(int event, Cache cache, Element element) {
            this.cache = cache;
            this.event = event;
            this.element = element;
        }

        public EventMessage(int event, Cache cache, Serializable key) {
            this.cache = cache;
            this.event = event;
            this.key = key;
        }

    }

    /**
     * Give the replicator a chance to cleanup and free resources when no longer needed
     */
    public void dispose() {
        status = Status.STATUS_SHUTDOWN;
        synchronized (replicationQueue) {
            replicationQueue.clear();
        }

    }
}
