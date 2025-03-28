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


package net.sf.ehcache.distribution.jgroups;

import java.io.Serializable;

/**
 * Serializable type used for Jgroups type replication
 *
 * @author Pierre Monestie (pmonestie__REMOVE__THIS__@gmail.com)
 * @author <a href="mailto:gluck@gregluck.com">Greg Luck</a>
 * @version $Id$
 */
public class JGroupSerializable implements Serializable {

    private int event;

    private Serializable key;

    private Serializable value;

    private String cacheName;

    /**
     * @param event the type of replication event
     * @param key   the key
     * @param value can be null if REMOVE or REMOVE_ALL
     */
    public JGroupSerializable(int event, Serializable key, Serializable value, String cacheName) {
        super();
        this.event = event;
        this.key = key;
        this.value = value;
        this.cacheName = cacheName;
    }

    /**
     * Gets the event
     *
     * @return the event
     */
    public int getEvent() {
        return event;
    }

    /**
     * Get the Serializable key for the event
     *
     * @return the key
     */
    public Serializable getKey() {
        return key;
    }

    /**
     * Gets the value, null if REMOVE or REMOVE_ALL
     *
     * @return the value
     */
    public Serializable getValue() {
        return value;
    }

    /**
     * Gets the cache name
     *
     * @return the cache name
     */
    public String getCacheName() {
        return cacheName;
    }

}
