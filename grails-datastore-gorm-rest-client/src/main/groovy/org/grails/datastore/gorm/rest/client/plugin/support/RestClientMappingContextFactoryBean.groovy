/* Copyright (C) 2013 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.grails.datastore.gorm.rest.client.plugin.support

import groovy.transform.Canonical
import org.grails.datastore.gorm.bean.factory.AbstractMappingContextFactoryBean
import org.grails.datastore.mapping.model.MappingContext
import org.grails.datastore.mapping.rest.client.config.RestClientMappingContext

/**
 * Spring Factory bean for constructing the RestClientMappingContext
 *
 * @author Graeme Rocher
 * @since 1.0
 */
class RestClientMappingContextFactoryBean extends AbstractMappingContextFactoryBean {
    DefaultMappingHolder defaultMapping

    @Override
    protected MappingContext createMappingContext() {
        def defaultMapping = defaultMapping?.defaultMapping
        def context = defaultMapping != null ?  new RestClientMappingContext(defaultMapping) : new RestClientMappingContext()
        return context
    }
}
@Canonical
class DefaultMappingHolder {
    Closure defaultMapping
}
