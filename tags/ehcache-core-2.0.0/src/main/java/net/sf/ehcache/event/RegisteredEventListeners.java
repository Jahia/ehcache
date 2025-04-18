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

package net.sf.ehcache.event;

import net.sf.ehcache.CacheException;
import net.sf.ehcache.Ehcache;
import net.sf.ehcache.Element;
import net.sf.ehcache.distribution.CacheReplicator;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Registered listeners for registering and unregistering CacheEventListeners and multicasting notifications to registrants.
 * <p/>
 * There is one of these per Cache.
 * <p/>
 * This class also has counters to accumulate the numbers of each type of event for statistics purposes.
 *
 * @author Greg Luck
 * @author Geert Bevin
 * @version $Id$
 */
public class RegisteredEventListeners {

    /**
     * A Set of CacheEventListeners keyed by listener instance.
     * CacheEventListener implementations that will be notified of this cache's events.
     *
     * @see CacheEventListener
     */
    private final Set<ListenerWrapper> cacheEventListeners = new CopyOnWriteArraySet<ListenerWrapper>();
    private final Ehcache cache;

    private AtomicLong elementsRemovedCounter = new AtomicLong(0);
    private AtomicLong elementsPutCounter = new AtomicLong(0);
    private AtomicLong elementsUpdatedCounter = new AtomicLong(0);
    private AtomicLong elementsExpiredCounter = new AtomicLong(0);
    private AtomicLong elementsEvictedCounter = new AtomicLong(0);
    private AtomicLong elementsRemoveAllCounter = new AtomicLong(0);

    /**
     * Constructs a new notification service
     *
     * @param cache
     */
    public RegisteredEventListeners(Ehcache cache) {
        this.cache = cache;
    }

    /**
     * Notifies all registered listeners, in no guaranteed order, that an element was removed
     *
     * @param element
     * @param remoteEvent whether the event came from a remote cache peer
     * @see CacheEventListener#notifyElementRemoved
     */
    public final void notifyElementRemoved(Element element, boolean remoteEvent) throws CacheException {
        elementsRemovedCounter.incrementAndGet();
        if (hasCacheEventListeners()) {
            for (ListenerWrapper listenerWrapper : cacheEventListeners) {
                if (listenerWrapper.getScope().shouldDeliver(remoteEvent)
                        && !isCircularNotification(remoteEvent, listenerWrapper.getListener())) {
                    listenerWrapper.getListener().notifyElementRemoved(cache, element);
                }
            }
        }
    }

    /**
     * Notifies all registered listeners, in no guaranteed order, that an element was put into the cache
     *
     * @param element
     * @param remoteEvent whether the event came from a remote cache peer
     * @see CacheEventListener#notifyElementPut(net.sf.ehcache.Ehcache,net.sf.ehcache.Element)
     */
    public final void notifyElementPut(Element element, boolean remoteEvent) throws CacheException {
        elementsPutCounter.incrementAndGet();
        if (hasCacheEventListeners()) {
            for (ListenerWrapper listenerWrapper : cacheEventListeners) {
                if (listenerWrapper.getScope().shouldDeliver(remoteEvent)
                        && !isCircularNotification(remoteEvent, listenerWrapper.getListener())) {
                    listenerWrapper.getListener().notifyElementPut(cache, element);
                }
            }

        }
    }

    /**
     * Notifies all registered listeners, in no guaranteed order, that an element in the cache was updated
     *
     * @param element
     * @param remoteEvent whether the event came from a remote cache peer
     * @see CacheEventListener#notifyElementPut(net.sf.ehcache.Ehcache,net.sf.ehcache.Element)
     */
    public final void notifyElementUpdated(Element element, boolean remoteEvent) {
        elementsUpdatedCounter.incrementAndGet();
        if (hasCacheEventListeners()) {
            for (ListenerWrapper listenerWrapper : cacheEventListeners) {
                if (listenerWrapper.getScope().shouldDeliver(remoteEvent)
                        && !isCircularNotification(remoteEvent, listenerWrapper.getListener())) {
                    listenerWrapper.getListener().notifyElementUpdated(cache, element);
                }
            }
        }
    }

    /**
     * Notifies all registered listeners, in no guaranteed order, that an element has expired
     *
     * @param element     the Element to perform the notification on
     * @param remoteEvent whether the event came from a remote cache peer
     * @see CacheEventListener#notifyElementExpired
     */
    public final void notifyElementExpiry(Element element, boolean remoteEvent) {
        elementsExpiredCounter.incrementAndGet();
        if (hasCacheEventListeners()) {
            for (ListenerWrapper listenerWrapper : cacheEventListeners) {
                if (listenerWrapper.getScope().shouldDeliver(remoteEvent)
                        && !isCircularNotification(remoteEvent, listenerWrapper.getListener())) {
                    listenerWrapper.getListener().notifyElementExpired(cache, element);
                }
            }
        }
    }

    /**
     * Returns whether or not at least one cache event listeners has been registered.
     *
     * @return true if a one or more listeners have registered, otherwise false
     */
    public final boolean hasCacheEventListeners() {
        return cacheEventListeners.size() > 0;
    }

    /**
     * Notifies all registered listeners, in no guaranteed order, that an element has been
     * evicted from the cache
     *
     * @param element     the Element to perform the notification on
     * @param remoteEvent whether the event came from a remote cache peer
     * @see CacheEventListener#notifyElementEvicted
     */
    public void notifyElementEvicted(Element element, boolean remoteEvent) {
        elementsEvictedCounter.incrementAndGet();
        if (hasCacheEventListeners()) {
            for (ListenerWrapper listenerWrapper : cacheEventListeners) {
                if (listenerWrapper.getScope().shouldDeliver(remoteEvent)
                        && !isCircularNotification(remoteEvent, listenerWrapper.getListener())) {
                    listenerWrapper.getListener().notifyElementEvicted(cache, element);
                }
            }
        }
    }

    /**
     * Notifies all registered listeners, in no guaranteed order, that removeAll
     * has been called and all elements cleared
     *
     * @param remoteEvent whether the event came from a remote cache peer
     * @see CacheEventListener#notifyElementEvicted
     */
    public void notifyRemoveAll(boolean remoteEvent) {
        elementsRemoveAllCounter.incrementAndGet();
        if (hasCacheEventListeners()) {
            for (ListenerWrapper listenerWrapper : cacheEventListeners) {
                if (listenerWrapper.getScope().shouldDeliver(remoteEvent)
                        && !isCircularNotification(remoteEvent, listenerWrapper.getListener())) {
                    listenerWrapper.getListener().notifyRemoveAll(cache);
                }
            }
        }
    }

    /**
     * CacheReplicators should not be notified of events received remotely, as this would cause
     * a circular notification
     *
     * @param remoteEvent
     * @param cacheEventListener
     * @return true is notifiying the listener would cause a circular notification
     */
    private static boolean isCircularNotification(boolean remoteEvent, CacheEventListener cacheEventListener) {
        return remoteEvent && cacheEventListener instanceof CacheReplicator;
    }


    /**
     * Adds a listener to the notification service. No guarantee is made that listeners will be
     * notified in the order they were added.
     *
     * @param cacheEventListener
     * @return true if the listener is being added and was not already added
     */
    public final boolean registerListener(CacheEventListener cacheEventListener) {
        return registerListener(cacheEventListener, NotificationScope.ALL);
    }

    /**
     * Adds a listener to the notification service. No guarantee is made that listeners will be
     * notified in the order they were added.
     * <p/>
     * If a cache is configured in a cluster, listeners in each node will get triggered by an event
     * depending on the value of the <pre>listenFor</pre> parameter.
     *
     * @param cacheEventListener The listener to add
     * @param scope              The notification scope
     * @return true if the listener is being added and was not already added
     * @since 2.0
     */
    public final boolean registerListener(CacheEventListener cacheEventListener, NotificationScope scope) {
        if (cacheEventListener == null) {
            return false;
        }
        return cacheEventListeners.add(new ListenerWrapper(cacheEventListener, scope));
    }

    /**
     * Removes a listener from the notification service.
     *
     * @param cacheEventListener
     * @return true if the listener was present
     */
    public final boolean unregisterListener(CacheEventListener cacheEventListener) {
        Iterator<ListenerWrapper> it = cacheEventListeners.iterator();
        while (it.hasNext()) {
            ListenerWrapper listenerWrapper = it.next();
            if (listenerWrapper.getListener().equals(cacheEventListener)) {
                it.remove();
                return true;
            }
        }
        return false;
    }


    /**
     * Gets a copy of the set of the listeners registered to this class
     *
     * @return a set of type <code>CacheEventListener</code>
     */
    public final Set<CacheEventListener> getCacheEventListeners() {
        Set<CacheEventListener> listenerSet = new HashSet<CacheEventListener>();
        for (ListenerWrapper listenerWrapper : cacheEventListeners) {
            listenerSet.add(listenerWrapper.getListener());
        }
        return listenerSet;
    }

    /**
     * Tell listeners to dispose themselves.
     * Because this method is only ever called from a synchronized cache method, it does not itself need to be
     * synchronized.
     */
    public final void dispose() {
        for (ListenerWrapper listenerWrapper : cacheEventListeners) {
            listenerWrapper.getListener().dispose();
        }
        cacheEventListeners.clear();
    }

    /**
     * Returns a string representation of the object. In general, the
     * <code>toString</code> method returns a string that
     * "textually represents" this object. The result should
     * be a concise but informative representation that is easy for a
     * person to read.
     *
     * @return a string representation of the object.
     */
    @Override
    public final String toString() {
        StringBuilder sb = new StringBuilder(" cacheEventListeners: ");
        for (ListenerWrapper listenerWrapper : cacheEventListeners) {
            sb.append(listenerWrapper.getListener().getClass().getName()).append(" ");
        }
        return sb.toString();
    }

    /**
     * Clears all event counters
     */
    public void clearCounters() {
        elementsRemovedCounter.set(0);
        elementsPutCounter.set(0);
        elementsUpdatedCounter.set(0);
        elementsExpiredCounter.set(0);
        elementsEvictedCounter.set(0);
        elementsRemoveAllCounter.set(0);
    }

    /**
     * Gets the number of events, irrespective of whether there are any registered listeners.
     *
     * @return the number of events since cache creation or last clearing of counters
     */
    public long getElementsRemovedCounter() {
        return elementsRemovedCounter.get();
    }

    /**
     * Gets the number of events, irrespective of whether there are any registered listeners.
     *
     * @return the number of events since cache creation or last clearing of counters
     */
    public long getElementsPutCounter() {
        return elementsPutCounter.get();
    }

    /**
     * Gets the number of events, irrespective of whether there are any registered listeners.
     *
     * @return the number of events since cache creation or last clearing of counters
     */
    public long getElementsUpdatedCounter() {
        return elementsUpdatedCounter.get();
    }

    /**
     * Gets the number of events, irrespective of whether there are any registered listeners.
     *
     * @return the number of events since cache creation or last clearing of counters
     */
    public long getElementsExpiredCounter() {
        return elementsExpiredCounter.get();
    }

    /**
     * Gets the number of events, irrespective of whether there are any registered listeners.
     *
     * @return the number of events since cache creation or last clearing of counters
     */
    public long getElementsEvictedCounter() {
        return elementsEvictedCounter.get();
    }

    /**
     * Gets the number of events, irrespective of whether there are any registered listeners.
     *
     * @return the number of events since cache creation or last clearing of counters
     */
    public long getElementsRemoveAllCounter() {
        return elementsRemoveAllCounter.get();
    }

    /**
     * Combine a Listener and its NotificationScope.  Equality and hashcode are based purely on the listener.
     * This implies that the same listener cannot be added to the set of registered listeners more than
     * once with different notification scopes.
     *
     * @author Alex Miller
     */
    private static final class ListenerWrapper {
        private final CacheEventListener listener;
        private final NotificationScope scope;

        private ListenerWrapper(CacheEventListener listener, NotificationScope scope) {
            this.listener = listener;
            this.scope = scope;
        }

        private CacheEventListener getListener() {
            return this.listener;
        }

        private NotificationScope getScope() {
            return this.scope;
        }

        /**
         * Hash code based on listener
         *
         * @see java.lang.Object#hashCode()
         */
        @Override
        public int hashCode() {
            return listener.hashCode();
        }

        /**
         * Equals based on listener (NOT based on scope) - can't have same listener with two different scopes
         *
         * @see java.lang.Object#equals(java.lang.Object)
         */
        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            ListenerWrapper other = (ListenerWrapper) obj;
            if (listener == null) {
                if (other.listener != null) {
                    return false;
                }
            } else if (!listener.equals(other.listener)) {
                return false;
            }
            return true;
        }

        @Override
        public String toString() {
            return listener.toString();
        }
    }

}
