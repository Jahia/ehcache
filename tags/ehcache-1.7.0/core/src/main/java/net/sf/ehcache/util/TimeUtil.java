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

package net.sf.ehcache.util;

/**
 * Utilities for converting times
 * @author Greg Luck
 */
public class TimeUtil {

    /**
     * Constant that contains the amount of milliseconds in a second
     */
    static final long ONE_SECOND = 1000L;

    /**
     * Converts milliseconds to seconds
     * @param timeInMillis
     * @return The equivalent time in seconds
     */
    public static int toSecs(long timeInMillis) {
        // Rounding the result to the ceiling, otherwise a
        // System.currentTimeInMillis that happens right before a new Element
        // instantiation will be seen as 'later' than the actual creation time
        return (int)Math.ceil((double)timeInMillis / ONE_SECOND);
    }

    /**
     * Converts seconds to milliseconds, with a precision of 1 second
     * @param timeInSecs the time in seconds
     * @return The equivalent time in milliseconds
     */
    public static long toMillis(int timeInSecs) {
        return timeInSecs * ONE_SECOND;
    }
}
