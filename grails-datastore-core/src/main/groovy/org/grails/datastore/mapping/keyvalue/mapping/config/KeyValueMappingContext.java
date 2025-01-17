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
package org.grails.datastore.mapping.keyvalue.mapping.config;

import org.grails.datastore.mapping.model.AbstractMappingContext;
import org.grails.datastore.mapping.model.MappingConfigurationStrategy;
import org.grails.datastore.mapping.model.MappingFactory;
import org.grails.datastore.mapping.model.PersistentEntity;
import org.grails.datastore.mapping.model.config.GormMappingConfigurationStrategy;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;

/**
 * A MappingContext used to map objects to a Key/Value store
 *
 * @author Graeme Rocher
 * @since 1.0
 */
public class KeyValueMappingContext extends AbstractMappingContext {
    protected MappingFactory<Family, KeyValue> mappingFactory;
    protected MappingConfigurationStrategy syntaxStrategy;
    private String keyspace;
    public static final String GROOVY_OBJECT_CLASS = "groovy.lang.GroovyObject";

    @Override
    public void setCanInitializeEntities(boolean canInitializeEntities) {
        super.setCanInitializeEntities(canInitializeEntities);
        syntaxStrategy.setCanExpandMappingContext(false);
    }

    /**
     * Constructs a context using the given keyspace
     *
     * @param keyspace The keyspace, this is typically the application name
     */
    public KeyValueMappingContext(String keyspace) {
        Assert.notNull(keyspace, "Argument [keyspace] cannot be null");
        this.keyspace = keyspace;
        initializeDefaultMappingFactory(keyspace);

        syntaxStrategy = new GormMappingConfigurationStrategy(mappingFactory);
    }

    public String getKeyspace() {
        return keyspace;
    }

    protected void initializeDefaultMappingFactory(String keyspace) {
        mappingFactory = new GormKeyValueMappingFactory(keyspace);
    }

    public void setMappingFactory(MappingFactory<Family, KeyValue> mappingFactory) {
        this.mappingFactory = mappingFactory;
    }

    public void setSyntaxStrategy(MappingConfigurationStrategy syntaxStrategy) {
        this.syntaxStrategy = syntaxStrategy;
    }

    public MappingConfigurationStrategy getMappingSyntaxStrategy() {
        return syntaxStrategy;
    }

    @Override
    public MappingFactory<Family, KeyValue> getMappingFactory() {
        return mappingFactory;
    }

    @Override
    protected PersistentEntity createPersistentEntity(@SuppressWarnings("rawtypes") Class javaClass) {
        return new KeyValuePersistentEntity(javaClass, this);
    }
}
