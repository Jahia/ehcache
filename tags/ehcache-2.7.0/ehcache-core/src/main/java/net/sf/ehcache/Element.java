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


package net.sf.ehcache;


import net.sf.ehcache.config.CacheConfiguration;
import net.sf.ehcache.pool.sizeof.annotations.IgnoreSizeOf;
import net.sf.ehcache.util.TimeUtil;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A Cache Element, consisting of a key, value and attributes.
 * <p/>
 * From ehcache-1.2, Elements can have keys and values that are Serializable or Objects. To preserve backward
 * compatibility, special accessor methods for Object keys and values are provided: {@link #getObjectKey()} and
 * {@link #getObjectValue()}. If placing Objects in ehcache, developers must use the new getObject... methods to
 * avoid CacheExceptions. The get... methods are reserved for Serializable keys and values.
 *
 * @author Greg Luck
 * @version $Id$
 */
public class Element implements Serializable, Cloneable {

    /**
     * serial version
     * Updated for version 1.2, 1.2.1 and 1.7
     */
    private static final long serialVersionUID = 1098572221246444544L;

    private static final Logger LOG = LoggerFactory.getLogger(Element.class.getName());

    private static final AtomicLongFieldUpdater<Element> HIT_COUNT_UPDATER = AtomicLongFieldUpdater.newUpdater(Element.class, "hitCount");

    private static final boolean ELEMENT_VERSION_AUTO = Boolean.getBoolean("net.sf.ehcache.element.version.auto");

    private static final long NOT_SET_ID = 0;

    static {
        if (ELEMENT_VERSION_AUTO) {
            LOG.warn("Note that net.sf.ehcache.element.version.auto is set and user provided version will not be honored");
        }
    }

    /**
     * the cache key.
     */
    @IgnoreSizeOf
    private final Object key;

    /**
     * the value.
     */
    private final Object value;

    /**
     * version of the element. System.currentTimeMillis() is used to compute version for updated elements. That
     * way, the actual version of the updated element does not need to be checked.
     */
    private volatile long version;

    /**
     * The number of times the element was hit.
     */
    private volatile long hitCount;

    /**
     * The amount of time for the element to live, in seconds. 0 indicates unlimited.
     */
    private volatile int timeToLive = Integer.MIN_VALUE;

    /**
     * The amount of time for the element to idle, in seconds. 0 indicates unlimited.
     */
    private volatile int timeToIdle = Integer.MIN_VALUE;

    /**
     * The creation time.
     */
    private transient long creationTime;

    /**
     * The last access time.
     */
    private transient long lastAccessTime;

    /**
     * If there is an Element in the Cache and it is replaced with a new Element for the same key,
     * then both the version number and lastUpdateTime should be updated to reflect that. The creation time
     * will be the creation time of the new Element, not the original one, so that TTL concepts still work.
     */
    private volatile long lastUpdateTime;

    private volatile boolean cacheDefaultLifespan = true;

    private volatile long id = NOT_SET_ID;

    /**
     * A full constructor.
     * <p/>
     * Creation time is set to the current time. Last Access Time is not set.
     *
     * @since .4
     */
    public Element(final Serializable key, final Serializable value, final long version) {
        this((Object) key, (Object) value, version);

    }

    /**
     * A full constructor.
     * <p/>
     * Creation time is set to the current time. Last Access Time and Previous To Last Access Time
     * are not set.
     *
     * @since 1.2
     */
    public Element(final Object key, final Object value, final long version) {
        this.key = key;
        this.value = value;
        this.version = version;
        HIT_COUNT_UPDATER.set(this, 0);
        this.creationTime = System.currentTimeMillis();
    }

    /**
     * Constructor.
     *
     * @deprecated The {@code nextToLastAccessTime} field is unused since
     *             version 1.7, setting it will have no effect. Use
     *             #Element(Object, Object, long, long, long, long, long)
     *             instead
     * @since 1.3
     * @see #Element(Object, Object, long, long, long, long, long)
     */
    @Deprecated
    public Element(final Object key, final Object value, final long version,
                   final long creationTime, final long lastAccessTime, final long nextToLastAccessTime,
                   final long lastUpdateTime, final long hitCount) {
        this(key, value, version, creationTime, lastAccessTime, lastUpdateTime, hitCount);
    }

    /**
     * Constructor.
     *
     * @since 1.7
     */
    public Element(final Object key, final Object value, final long version,
                   final long creationTime, final long lastAccessTime,
                   final long lastUpdateTime, final long hitCount) {
        this.key = key;
        this.value = value;
        this.version = version;
        this.lastUpdateTime = lastUpdateTime;
        HIT_COUNT_UPDATER.set(this, hitCount);
        this.creationTime = creationTime;
        this.lastAccessTime = lastAccessTime;
    }

    /**
     * Constructor used by ElementData. Needs to be public since ElementData might be in another classloader
     *
     * @since 1.7
     */
    public Element(final Object key, final Object value, final long version, final long creationTime,
            final long lastAccessTime, final long hitCount, final boolean cacheDefaultLifespan,
            final int timeToLive, final int timeToIdle, final long lastUpdateTime) {
        this.key = key;
        this.value = value;
        this.version = version;
        HIT_COUNT_UPDATER.set(this, hitCount);
        this.cacheDefaultLifespan = cacheDefaultLifespan;
        this.timeToLive = timeToLive;
        this.timeToIdle = timeToIdle;
        this.lastUpdateTime = lastUpdateTime;
        this.creationTime = creationTime;
        this.lastAccessTime = lastAccessTime;
    }

    /**
     * Constructor used by ehcache-server
     *
     * @param key               any non null value
     * @param value             any value, including nulls
     * @param eternal           specify as non-null to override cache configuration
     * @param timeToIdleSeconds specify as non-null to override cache configuration
     * @param timeToLiveSeconds specify as non-null to override cache configuration
     */
    public Element(final Object key, final Object value,
                   final Boolean eternal, final Integer timeToIdleSeconds, final Integer timeToLiveSeconds) {
        this.key = key;
        this.value = value;
        if (eternal != null) {
            setEternal(eternal.booleanValue());
        }
        if (timeToIdleSeconds != null) {
            setTimeToIdle(timeToIdleSeconds.intValue());
        }
        if (timeToLiveSeconds != null) {
            setTimeToLive(timeToLiveSeconds.intValue());
        }
        this.creationTime = System.currentTimeMillis();
    }

    /**
     * Constructor.
     *
     * @param key
     * @param value
     */
    public Element(final Serializable key, final Serializable value) {
        this((Object) key, (Object) value, 1L);
    }

    /**
     * Constructor.
     *
     * @param key
     * @param value
     * @since 1.2
     */
    public Element(final Object key, final Object value) {
        this(key, value, 1L);
    }

    /**
     * Gets the key attribute of the Element object.
     *
     * @return The key value.
     * @throws CacheException if the key is not {@code Serializable}.
     * @deprecated Please use {@link #getObjectKey()} instead.
     */
    @Deprecated
    public final Serializable getKey() throws CacheException {
        try {
            return (Serializable) getObjectKey();
        } catch (ClassCastException e) {
            throw new CacheException("The key " + getObjectKey() + " is not Serializable. Consider using Element.getObjectKey()");
        }
    }

    /**
     * Gets the key attribute of the Element object.
     * <p/>
     * This method is provided for those wishing to use ehcache as a memory only cache
     * and enables retrieval of non-Serializable values from elements.
     *
     * @return The key as an Object. i.e no restriction is placed on it
     * @see #getKey()
     */
    public final Object getObjectKey() {
        return key;
    }

    /**
     * Gets the value attribute of the Element object.
     *
     * @return The value which must be {@code Serializable}. If not use {@link #getObjectValue}.
     * @throws CacheException if the value is not {@code Serializable}.
     * @deprecated Please use {@link #getObjectValue()} instead.
     */
    @Deprecated
    public final Serializable getValue() throws CacheException {
        try {
            return (Serializable) getObjectValue();
        } catch (ClassCastException e) {
            throw new CacheException("The value " + getObjectValue() + " for key " + getObjectKey() +
                    " is not Serializable. Consider using Element.getObjectValue()");
        }
    }

    /**
     * Gets the value attribute of the Element object as an Object.
     * <p/>
     * This method is provided for those wishing to use ehcache as a memory only cache
     * and enables retrieval of non-Serializable values from elements.
     *
     * @return The value as an Object.  i.e no restriction is placed on it
     * @see #getValue()
     * @since 1.2
     */
    public final Object getObjectValue() {
        return value;
    }

    /**
     * Equals comparison with another element, based on the key.
     */
    @Override
    public final boolean equals(final Object object) {
        if (object == null || !(object instanceof Element)) {
            return false;
        }

        Element element = (Element) object;
        if (key == null || element.getObjectKey() == null) {
            return false;
        }

        return key.equals(element.getObjectKey());
    }

    /**
     * Sets time to Live
     *
     * @param timeToLiveSeconds the number of seconds to live
     */
    public void setTimeToLive(final int timeToLiveSeconds) {
        if (timeToLiveSeconds < 0) {
            throw new IllegalArgumentException("timeToLive can't be negative");
        }
        this.cacheDefaultLifespan = false;
        this.timeToLive = timeToLiveSeconds;
    }

    /**
     * Sets time to idle
     *
     * @param timeToIdleSeconds the number of seconds to idle
     */
    public void setTimeToIdle(final int timeToIdleSeconds) {
        if (timeToIdleSeconds < 0) {
            throw new IllegalArgumentException("timeToIdle can't be negative");
        }
        this.cacheDefaultLifespan = false;
        this.timeToIdle = timeToIdleSeconds;
    }

    /**
     * Gets the hashcode, based on the key.
     */
    @Override
    public final int hashCode() {
        return key.hashCode();
    }

    /**
     * Sets the version attribute of the ElementAttributes object.
     *
     * @param version The new version value
     */
    public final void setVersion(final long version) {
        this.version = version;
    }

    /**
     * Sets the element identifier (this field is used internally by ehcache). Setting this field in application code will not be preserved
     *
     * @param id The new id value
     */
    void setId(final long id) {
        if (id == NOT_SET_ID) {
            throw new IllegalArgumentException("Id cannot be set to " + id);
        }
        this.id = id;
    }

    /**
     * Gets the element identifier (this field is used internally by ehcache)
     *
     * @return id the id
     */
    long getId() {
        final long v = id;
        if (v == NOT_SET_ID) {
            throw new IllegalStateException("Id not set");
        }
        return v;
    }

    /**
     * Determines if an Id has been set on this element
     *
     * @return true if this element has an Id
     */
    boolean hasId() {
        return id != NOT_SET_ID;
    }

    /**
     * Sets the creationTime attribute of the ElementAttributes object.
     * <p>
     * Note that in a Terracotta clustered environment, resetting the creation
     * time will not have any effect.
     *
     * @deprecated Resetting the creation time is not recommended as of version
     *             1.7
     */
    @Deprecated
    public final void setCreateTime() {
        creationTime = System.currentTimeMillis();
    }

    /**
     * Gets the creationTime of the Element
     *
     * @return The creationTime value
     */
    public final long getCreationTime() {
        return creationTime;
    }

    /**
     * Calculates the latest of creation and update time
     * @return if never updated, creation time is returned, otherwise updated time
     */
    public final long getLatestOfCreationAndUpdateTime() {
        if (0 == lastUpdateTime) {
            return creationTime;
        } else {
            return lastUpdateTime;
        }
    }

    /**
     * Gets the version attribute of the ElementAttributes object.
     *
     * @return The version value
     */
    public final long getVersion() {
        return version;
    }

    /**
     * Gets the last access time.
     * Access means a get. So a newly created {@link Element}
     * will have a last access time equal to its create time.
     */
    public final long getLastAccessTime() {
        return lastAccessTime;
    }

    /**
     * Gets the next to last access time.
     *
     * @deprecated The {@code nextToLastAccessTime} field is unused since
     *             version 1.7, retrieving it will return the {@code
     *             lastAccessTime}. Use #getLastAccessTime() instead.
     * @see #getLastAccessTime()
     */
    @Deprecated
    public final long getNextToLastAccessTime() {
        return getLastAccessTime();
    }

    /**
     * Gets the hit count on this element.
     */
    public final long getHitCount() {
        return hitCount;
    }

    /**
     * Resets the hit count to 0 and the last access time to now. Used when an Element is put into a cache.
     */
    public final void resetAccessStatistics() {
        lastAccessTime = System.currentTimeMillis();
        HIT_COUNT_UPDATER.set(this, 0);
    }

    /**
     * Sets the last access time to now and increase the hit count.
     */
    public final void updateAccessStatistics() {
        lastAccessTime = System.currentTimeMillis();
        HIT_COUNT_UPDATER.incrementAndGet(this);
    }

    /**
     * Sets the last access time to now without updating the hit count.
     */
    public final void updateUpdateStatistics() {
        lastUpdateTime = System.currentTimeMillis();
        if (ELEMENT_VERSION_AUTO) {
          version = lastUpdateTime;
        }
    }


    /**
     * Returns a {@link String} representation of the {@link Element}.
     */
    @Override
    public final String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("[ key = ").append(key)
                .append(", value=").append(value)
                .append(", version=").append(version)
                .append(", hitCount=").append(hitCount)
                .append(", CreationTime = ").append(this.getCreationTime())
                .append(", LastAccessTime = ").append(this.getLastAccessTime())
                .append(" ]");

        return sb.toString();
    }

    /**
     * Clones an Element. A completely new object is created, with no common references with the
     * existing one.
     * <p/>
     * This method will not work unless the Object is Serializable
     * <p/>
     * Warning: This can be very slow on large object graphs. If you use this method
     * you should write a performance test to verify suitability.
     *
     * @return a new {@link Element}, with exactly the same field values as the one it was cloned from.
     * @throws CloneNotSupportedException
     */
    @Override
    public final Object clone() throws CloneNotSupportedException {
        //Not used. Just to get code inspectors to shut up
        super.clone();

        try {
            return new Element(deepCopy(key), deepCopy(value), version, creationTime, lastAccessTime, 0L, hitCount);
        } catch (IOException e) {
            LOG.error("Error cloning Element with key " + key
                    + " during serialization and deserialization of value");
            throw new CloneNotSupportedException();
        } catch (ClassNotFoundException e) {
            LOG.error("Error cloning Element with key " + key
                    + " during serialization and deserialization of value");
            throw new CloneNotSupportedException();
        }
    }

    private static Object deepCopy(final Object oldValue) throws IOException, ClassNotFoundException {
        Serializable newValue = null;
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ObjectOutputStream oos = null;
        ObjectInputStream ois = null;
        try {
            oos = new ObjectOutputStream(bout);
            oos.writeObject(oldValue);
            ByteArrayInputStream bin = new ByteArrayInputStream(bout.toByteArray());
            ois = new ObjectInputStream(bin);
            newValue = (Serializable) ois.readObject();
        } finally {
            try {
                if (oos != null) {
                    oos.close();
                }
                if (ois != null) {
                    ois.close();
                }
            } catch (Exception e) {
                LOG.error("Error closing Stream");
            }
        }
        return newValue;
    }

    /**
     * The size of this object in serialized form. This is not the same
     * thing as the memory size, which is JVM dependent. Relative values should be meaningful,
     * however.
     * <p/>
     * Warning: This method can be <b>very slow</b> for values which contain large object graphs.
     * <p/>
     * If the key or value of the Element is not Serializable, an error will be logged and 0 will be returned.
     *
     * @return The serialized size in bytes
     */
    public final long getSerializedSize() {

        if (!isSerializable()) {
            return 0;
        }
        long size = 0;
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ObjectOutputStream oos = null;
        try {
            oos = new ObjectOutputStream(bout);
            oos.writeObject(this);
            size = bout.size();
            return size;
        } catch (IOException e) {
            LOG.debug("Error measuring element size for element with key " + key + ". Cause was: " + e.getMessage());
        } finally {
            try {
                if (oos != null) {
                    oos.close();
                }
            } catch (Exception e) {
                LOG.error("Error closing ObjectOutputStream");
            }
        }

        return size;
    }

    /**
     * Whether the element may be Serialized.
     * <p/>
     * While Element implements Serializable, it is possible to create non Serializable elements
     * for use in MemoryStores. This method checks that an instance of Element really is Serializable
     * and will not throw a NonSerializableException if Serialized.
     * <p/>
     * This method was tweaked in 1.6 as it has been shown that Serializable classes can be serializaed as can
     * null, regardless of what class it is a null of. ObjectOutputStream.write(null) works and ObjectInputStream.read()
     * will read null back.
     *
     * @return true if the element is Serializable
     * @since 1.2
     */
    public final boolean isSerializable() {
        return isKeySerializable()
            && (value instanceof Serializable || value == null);
    }

    /**
     * Whether the element's key may be Serialized.
     * <p/>
     * While Element implements Serializable, it is possible to create non Serializable elements and/or
     * non Serializable keys for use in MemoryStores.
     * <p/>
     * This method checks that an instance of an Element's key really is Serializable
     * and will not throw a NonSerializableException if Serialized.
     *
     * @return true if the element's key is Serializable
     * @since 1.2
     */
    public final boolean isKeySerializable() {
        return key instanceof Serializable || key == null;
    }

    /**
     * If there is an Element in the Cache and it is replaced with a new Element for the same key,
     * then both the version number and lastUpdateTime should be updated to reflect that. The creation time
     * will be the creation time of the new Element, not the original one, so that TTL concepts still work.
     *
     * @return the time when the last update occured. If this is the original Element, the time will be null
     */
    public long getLastUpdateTime() {
        return lastUpdateTime;
    }

    /**
     * An element is expired if the expiration time as given by {@link #getExpirationTime()} is in the past.
     *
     * @return true if the Element is expired, otherwise false. If no lifespan has been set for the Element it is
     *         considered not able to expire.
     * @see #getExpirationTime()
     */
    public boolean isExpired() {
        if (!isLifespanSet() || isEternal()) {
            return false;
        }

        long now = System.currentTimeMillis();
        long expirationTime = getExpirationTime();

        return now > expirationTime;
    }

    /**
     * An element is expired if the expiration time as given by {@link #getExpirationTime()} is in the past.
     * <p>
     * This method in addition propogates the default TTI/TTL values of the supplied cache into this element.
     *
     * @param config config to take default parameters from
     * @return true if the Element is expired, otherwise false. If no lifespan has been set for the Element it is
     *         considered not able to expire.
     * @see #getExpirationTime()
     */
    public boolean isExpired(CacheConfiguration config) {
        if (cacheDefaultLifespan) {
            if (config.isEternal()) {
                timeToIdle = 0;
                timeToLive = 0;
            } else {
                timeToIdle = TimeUtil.convertTimeToInt(config.getTimeToIdleSeconds());
                timeToLive = TimeUtil.convertTimeToInt(config.getTimeToLiveSeconds());
            }
        }
        return isExpired();
    }

    /**
     * Returns the expiration time based on time to live. If this element also has a time to idle setting, the expiry
     * time will vary depending on whether the element is accessed.
     *
     * @return the time to expiration
     */
    public long getExpirationTime() {
        if (!isLifespanSet() || isEternal()) {
            return Long.MAX_VALUE;
        }

        long expirationTime = 0;
        long ttlExpiry = creationTime + TimeUtil.toMillis(getTimeToLive());

        long mostRecentTime = Math.max(creationTime, lastAccessTime);
        long ttiExpiry = mostRecentTime + TimeUtil.toMillis(getTimeToIdle());

        if (getTimeToLive() != 0 && (getTimeToIdle() == 0 || lastAccessTime == 0)) {
            expirationTime = ttlExpiry;
        } else if (getTimeToLive() == 0) {
            expirationTime = ttiExpiry;
        } else {
            expirationTime = Math.min(ttlExpiry, ttiExpiry);
        }
        return expirationTime;
    }

    /**
     * @return true if the element is eternal
     */
    public boolean isEternal() {
        return (0 == timeToIdle) && (0 == timeToLive);
    }

    /**
     * Sets whether the element is eternal.
     *
     * @param eternal
     */
    public void setEternal(final boolean eternal) {
        if (eternal) {
            this.cacheDefaultLifespan = false;
            this.timeToIdle = 0;
            this.timeToLive = 0;
        } else if (isEternal()) {
            this.cacheDefaultLifespan = false;
            this.timeToIdle = Integer.MIN_VALUE;
            this.timeToLive = Integer.MIN_VALUE;
        }
    }

    /**
     * Whether any combination of eternal, TTL or TTI has been set.
     *
     * @return true if set.
     */
    public boolean isLifespanSet() {
        return this.timeToIdle != Integer.MIN_VALUE || this.timeToLive != Integer.MIN_VALUE;
    }

    /**
     * @return the time to live, in seconds
     */
    public int getTimeToLive() {
        if (Integer.MIN_VALUE == timeToLive) {
            return 0;
        } else {
            return timeToLive;
        }
    }

    /**
     * @return the time to idle, in seconds
     */
    public int getTimeToIdle() {
        if (Integer.MIN_VALUE == timeToIdle) {
            return 0;
        } else {
            return timeToIdle;
        }
    }

    /**
     * @return <code>false</code> if this Element has a custom lifespan
     */
    public boolean usesCacheDefaultLifespan() {
        return cacheDefaultLifespan;
    }

    /**
     * Set the default parameters of this element - those from its enclosing cache.
     * @param tti TTI in seconds
     * @param ttl TTL in seconds
     * @param eternal <code>true</code> if the element is eternal.
     */
    protected void setLifespanDefaults(int tti, int ttl, boolean eternal) {
        if (eternal) {
            this.timeToIdle = 0;
            this.timeToLive = 0;
        } else if (isEternal()) {
            this.timeToIdle = Integer.MIN_VALUE;
            this.timeToLive = Integer.MIN_VALUE;
        } else {
            timeToIdle = tti;
            timeToLive = ttl;
        }
    }

    /**
     * Custom serialization write logic
     */
    private void writeObject(ObjectOutputStream out) throws IOException {
        out.defaultWriteObject();
        out.writeInt(TimeUtil.toSecs(creationTime));
        out.writeInt(TimeUtil.toSecs(lastAccessTime));
    }

    /**
     * Custom serialization read logic
     */
    private void readObject(ObjectInputStream in) throws IOException, ClassNotFoundException {
        in.defaultReadObject();
        creationTime = TimeUtil.toMillis(in.readInt());
        lastAccessTime = TimeUtil.toMillis(in.readInt());
    }
}
