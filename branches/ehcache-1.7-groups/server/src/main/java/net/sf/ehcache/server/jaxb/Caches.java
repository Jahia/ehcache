/**
 *  Copyright 2003-2008 Luck Consulting Pty Ltd
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

package net.sf.ehcache.server.jaxb;

import javax.xml.bind.annotation.XmlRootElement;
import java.util.List;

/**
 * A collection of Cache representations available from a CacheManager
 * @author Greg Luck
 * @version $Id$
 */
@XmlRootElement
public class Caches {

    private List<Cache> caches;

    /**
     * Empty Constructor
     */
    public Caches() {
    }

    /**
     * Constructor using a list of cache representations
     * @param caches a list of caches that belong in this CacheManager
     */
    public Caches(List<Cache> caches) {
        setCaches(caches);
    }

    /**
     * Gets the list of caches
     * @return a list of Cache representations
     */
    public List<Cache> getCaches() {
        return caches;
    }

    /**
     * Sets the list of caches
     * @param caches a list of cache representations
     */
    public void setCaches(List<Cache> caches) {
        this.caches = caches;
    }


}
