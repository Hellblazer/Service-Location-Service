/** (C) Copyright 2010 Hal Hildebrand, All Rights Reserved
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 *     
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, 
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
 * See the License for the specific language governing permissions and 
 * limitations under the License.
 */
package com.hellblazer.slp;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * The ServiceReference represents a registered service within a
 * <link>ServiceScope</link>.
 * 
 * A service is represented by a <link>ServiceURL</link> and a <link>Map</link>
 * of the attributes of the service.
 * 
 * @author <a href="mailto:hal.hildebrand@gmail.com">Hal Hildebrand</a>
 * 
 */
abstract public class ServiceReference implements Comparable<ServiceReference> {

    protected Map<String, String> properties;
    protected final UUID          registration;
    protected ServiceURL          url;

    /**
     * @param url
     * @param properties
     */
    public ServiceReference(ServiceURL url, Map<String, String> properties,
                            UUID registration) {
        this.url = url;
        this.properties = properties;
        this.registration = registration;
    }

    /* (non-Javadoc)
     * @see java.lang.Comparable#compareTo(java.lang.Object)
     */
    @Override
    public int compareTo(ServiceReference o) {
        return url.compareTo(o.url);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj != null && obj instanceof ServiceReference) {
            return registration.equals(((ServiceReference) obj).registration);
        }
        return false;
    }

    public Map<String, String> getProperties() {
        return Collections.unmodifiableMap(properties);
    }

    public UUID getRegistration() {
        return registration;
    }

    public ServiceURL getUrl() {
        return url;
    }

    @Override
    public int hashCode() {
        return registration.hashCode();
    }

    @Override
    public String toString() {
        return "ServiceReference [url=" + url + ", registration="
               + registration + "]";
    }
}