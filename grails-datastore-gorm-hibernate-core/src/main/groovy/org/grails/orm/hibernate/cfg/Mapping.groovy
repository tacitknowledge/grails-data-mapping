/*
 * Copyright 2003-2007 the original author or authors.
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
package org.grails.orm.hibernate.cfg

import groovy.transform.CompileStatic
import org.codehaus.groovy.grails.commons.GrailsDomainClassProperty
import org.grails.datastore.mapping.config.Entity

/**
 * Models the mapping from GORM classes to the db.
 *
 * @author Graeme Rocher
 * @since 1.0
 */
//@CompileStatic
class Mapping extends Entity {

    public static final String ALL_DATA_SOURCES = "ALL";
    public static final String DEFAULT_DATA_SOURCE = "DEFAULT"

    /**
     * Custom hibernate user types
     */
    Map userTypes = [:]

    /**
     * Return a type name of the known custom user types
     */
    String getTypeName(Class theClass) {
        def type = userTypes[theClass]
        if (type == null) {
            return null
        }

        return type instanceof Class ? type.name : type.toString()
    }

    /**
     * The table
     */
    Table table = new Table()

    /**
     * The table name
     */
    String getTableName() { table?.name }

    /**
     * Set the table name
     */
    void setTableName(String name) { table?.name = name }

    /**
     * Whether the class is versioned for optimistic locking
     */
    boolean versioned = true

    /**
     * Sets whether to use table-per-hierarchy or table-per-subclass mapping
     */
    boolean tablePerHierarchy = true

    /**
     * Sets whether to use table-per-concrete-class or table-per-subclass mapping
     */
    boolean tablePerConcreteClass = false

    /**
     * Sets whether automatic timestamping should occur for columns like last_updated and date_created
     */
    boolean autoTimestamp = true

    /**
     * Sets whether packaged domain classes should be auto-imported in HQL queries
     */
    boolean autoImport = true

    Map columns = [:]

    /**
     * The identity definition
     */
    def identity = new Identity()

    /**
     * Caching config
     */
    CacheConfig cache

    /**
     * Used to hold the names and directions of the default property to sort by
     */
    SortConfig sort = new SortConfig()

    /**
     * Value used to discriminate entities in table-per-hierarchy inheritance mapping
     */
    String discriminator

    /**
     * Used to hold the attributes for Discriminator, such as formula, type and insertable
     */
    Map discriminatorMap = [:]

    /**
     * Used to configure the disriminator column properties
     */
    ColumnConfig discriminatorColumn

    /**
     * Obtains a PropertyConfig object for the given name
     */
    PropertyConfig getPropertyConfig(String name) { columns[name] }

    /**
     * The name of the column used for the version number
     */
    String versionColumn

    /**
     * The batch size to use for lazy loading
     */
    Integer batchSize

    /**
     * Whether to use dynamically created update queries, at the cost of some performance
     */
    boolean dynamicUpdate = false

    /**
     * Whether to use dynamically created insert queries, at the cost of some performance
     */
    boolean dynamicInsert = false

    /**
     * Get the datasource names that this domain class works with.
     * @return the datasource names
     */
    List<String> datasources = [ DEFAULT_DATA_SOURCE ]

    /**
     * DDL comment.
     */
    String comment

    boolean isTablePerConcreteClass() {
        return tablePerConcreteClass
    }

    void setTablePerConcreteClass(boolean tablePerConcreteClass) {
        this.tablePerHierarchy = !tablePerConcreteClass
        this.tablePerConcreteClass = tablePerConcreteClass
    }
}
