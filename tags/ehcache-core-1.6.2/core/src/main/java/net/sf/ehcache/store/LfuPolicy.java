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

package net.sf.ehcache.store;

import net.sf.ehcache.Element;


/**
 * Contains common LFU policy code for use between the LfuMemoryStore and the DiskStore, which also
 * uses an LfuPolicy for evictions.
 *
 * @author Greg Luck
 * @version $Id$
 */
public class LfuPolicy extends AbstractPolicy {


    /**
     * @return the name of the Policy. Inbuilt examples are LRU, LFU and FIFO.
     */
    public String getName() {
        return "LFU";
    }

    /**
     * Compares the desirableness for eviction of two elements
     *
     * Compares hit counts. If both zero, 
     *
     * @param element1 the element to compare against
     * @param element2 the element to compare
     * @return true if the second element is preferable to the first element for ths policy
     */
    public boolean compare(Element element1, Element element2) {
        return element2.getHitCount() < element1.getHitCount();
        
    }

}
