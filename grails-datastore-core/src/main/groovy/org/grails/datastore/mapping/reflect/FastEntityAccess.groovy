/*
 * Copyright 2015 original authors
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
package org.grails.datastore.mapping.reflect

import groovy.transform.CompileStatic
import org.grails.datastore.mapping.engine.EntityAccess
import org.grails.datastore.mapping.model.PersistentEntity
import org.springframework.cglib.reflect.FastMethod
import org.springframework.core.convert.ConversionService

/**
 * An implementation of {@link EntityAccess} that uses cglib and @CompileStatic for optimized performance
 * @author Graeme Rocher
 * @since 4.1
 */
@CompileStatic
class FastEntityAccess implements EntityAccess {

    final PersistentEntity persistentEntity
    final Object entity
    final String identifierName
    final Map<String, FastMethod> fastGetters
    final Map<String, FastMethod> fastSetters
    final ConversionService conversionService
    final FastClassData fastClassData

    FastEntityAccess(Object entity, FastClassData fastClassData, ConversionService conversionService) {
        this.entity = entity
        this.fastClassData = fastClassData
        this.persistentEntity = fastClassData.entity
        this.identifierName = persistentEntity.identity?.name
        this.fastGetters = fastClassData.fastGetters
        this.fastSetters = fastClassData.fastSetters
        this.conversionService = conversionService
    }

    @Override
    void setProperty(String property, Object newValue) {
        def converted = conversionService.convert(newValue, fastGetters[property].returnType)
        fastSetters.get(property).invoke(entity, converted)
    }

    @Override
    Object getPropertyValue(String name) {
        return fastGetters.get(name).invoke(entity)
    }

    @Override
    Object getProperty(String property) {
        return fastGetters.get(property).invoke(entity)
    }

    @Override
    Class getPropertyType(String name) {
        return fastClassData.getPropertyType(name)
    }

    @Override
    Object getIdentifier() {
        return fastClassData.getIdentifier(entity)
    }

    @Override
    void setIdentifier(Object id) {
        if(identifierName != null) {
            def idType = fastClassData.idReader?.returnType
            fastSetters.get(identifierName).invoke(entity, conversionService.convert(id, idType))
        }
    }

    @Override
    void setIdentifierNoConversion(Object id) {
        if(identifierName != null) {
            fastSetters.get(identifierName).invoke(entity, id)
        }
    }

    @Override
    void refresh() {
        // no-op
    }

    @Override
    void setPropertyNoConversion(String name, Object value) {
        fastSetters.get(name).invoke(entity, value)
    }
}
