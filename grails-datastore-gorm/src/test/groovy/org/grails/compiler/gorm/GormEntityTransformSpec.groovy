package org.grails.compiler.gorm

import grails.gorm.annotation.Entity
import org.grails.datastore.gorm.GormEnhancer
import org.grails.datastore.gorm.GormEntity
import org.grails.datastore.gorm.GormStaticApi
import org.grails.datastore.gorm.GormValidateable
import org.grails.datastore.mapping.core.Datastore
import org.grails.datastore.mapping.core.Session
import org.grails.datastore.mapping.dirty.checking.DirtyCheckable
import org.grails.datastore.mapping.keyvalue.mapping.config.GormKeyValueMappingFactory
import org.grails.datastore.mapping.keyvalue.mapping.config.KeyValuePersistentEntity
import org.grails.datastore.mapping.model.MappingContext
import org.grails.datastore.mapping.model.config.GormMappingConfigurationStrategy
import org.grails.datastore.mapping.proxy.JavassistProxyFactory
import org.grails.datastore.mapping.reflect.FastClassData
import spock.lang.Specification

/*
 * Copyright 2014 original authors
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

/**
 * @author graemerocher
 */
class GormEntityTransformSpec extends Specification{

    void "Test GORM entity transformation implements"() {
        expect:
        GormEntity.isAssignableFrom(Book)
        GormValidateable.isAssignableFrom(Book)
        DirtyCheckable.isAssignableFrom(Book)
        Book.getAnnotation(Entity)
        new Author().respondsTo('addToBooks', Book)
        new Book().hasProperty('authorId')
    }


}

@Entity
class Book {
    String title

    static belongsTo = [author:Author]
}

@Entity
class Author {
    String name
    static hasMany = [books:Book]
}
