/* Copyright (C) 2010 SpringSource
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
package org.grails.datastore.mapping.model.types;

import org.grails.datastore.mapping.config.Property;
import org.grails.datastore.mapping.model.MappingContext;
import org.grails.datastore.mapping.model.PersistentEntity;

import java.beans.PropertyDescriptor;

/**
 * Models a many-to-many association between one class and another
 *
 * @author Graeme Rocher
 * @since 1.0
 */
public abstract class ManyToMany<T extends Property> extends ToMany<T> {

    String inversePropertyName;

    public ManyToMany(PersistentEntity owner, MappingContext context, PropertyDescriptor descriptor) {
        super(owner, context, descriptor);
    }

    public ManyToMany(PersistentEntity owner, MappingContext context, String name, @SuppressWarnings("rawtypes") Class type) {
        super(owner, context, name, type);
    }

    public String getInversePropertyName() {
        return inversePropertyName;
    }

    public void setInversePropertyName(String inversePropertyName) {
        this.inversePropertyName = inversePropertyName;
    }
}
