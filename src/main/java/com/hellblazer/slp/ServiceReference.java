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

import java.io.Serializable;
import java.util.Collections;
import java.util.Map;

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
public class ServiceReference implements Serializable {
    private static final long     serialVersionUID = 1L;
    protected ServiceURL          url;
    protected Map<String, Object> properties;

    /**
     * @param url
     * @param properties
     */
    public ServiceReference(ServiceURL url, Map<String, Object> properties) {
        super();
        this.url = url;
        this.properties = properties;
    }

    public Map<String, Object> getProperties() {
        return Collections.unmodifiableMap(properties);
    }

    public ServiceURL getUrl() {
        return url;
    }

    @Override
    public String toString() {
        return "ServiceReference [url=" + url + ", properties=" + properties
               + "]";
    }
}