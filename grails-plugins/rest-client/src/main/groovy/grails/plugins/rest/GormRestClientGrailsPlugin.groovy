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

package grails.plugins.restClient

import grails.core.GrailsClass
import org.grails.core.artefact.DomainClassArtefactHandler
import org.grails.datastore.gorm.rest.client.plugin.support.*

import grails.plugins.Plugin
import groovy.transform.CompileStatic
import org.grails.datastore.gorm.rest.client.plugin.support.RestClientMethodsConfigurer
import org.grails.datastore.mapping.rest.client.RestClientDatastore
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.transaction.PlatformTransactionManager

/**
 * @author Graeme Rocher
 */
class GormRestClientGrailsPlugin extends Plugin{
    // the plugin version
    def version = "1.0.0"
    // the version or versions of Grails the plugin is designed for
    def grailsVersion = "2.5 > *"
    // resources that are excluded from plugin packaging
    def pluginExcludes = [
        "grails-app/views/error.gsp"
    ]

    def title = "GORM Rest Client Plugin" // Headline display name of the plugin
    def author = "Graeme Rocher"
    def authorEmail = "graeme.rocher@gmail.com"
    def description = '''\
A GORM implementation that can back onto a REST web service
'''

    // URL to the plugin's documentation
    def documentation = "http://grails.github.io/grails-data-mapping/rest-client/"

    // Extra (optional) plugin metadata

    // License: one of 'APACHE', 'GPL2', 'GPL3'
    def license = "APACHE"

    // Details of company behind the plugin (if there is one)
    def organization = [ name: "Pivotal", url: "http://www.gopivotal.com/" ]

    // Any additional developers beyond the author specified above.
    def developers = [ [ name: "Graeme Rocher", email: "graeme.rocher@gmail.com" ]]

    // Location of the plugin's issue tracker.
    def issueManagement = [ system: "JIRA", url: "http://jira.grails.org/browse/GRAILS" ]

    // Online location of the plugin's browseable source code.
    def scm = [ url: "https://github.com/grails/grails-data-mapping" ]

    @Override
    @CompileStatic
    Closure doWithSpring() {
        //return new RestClientSpringConfigurer().getConfiguration()
        def initializer = new RestClientSpringInitializer(config, grailsApplication.getArtefacts(DomainClassArtefactHandler.TYPE).collect() { GrailsClass cls -> cls.clazz })
        initializer.registerApplicationIfNotPresent = false
        initializer.setSecondaryDatastore( false )
        return initializer.getBeanDefinitions((BeanDefinitionRegistry)applicationContext)
    }


    @Override
    @CompileStatic
    void doWithDynamicMethods() {
        def ctx = applicationContext
        def datastore = ctx.getBean(RestClientDatastore)
        def transactionManager = ctx.getBean("restclientTransactionManager",PlatformTransactionManager)
        def methodsConfigurer = new RestClientMethodsConfigurer(datastore, transactionManager)
        methodsConfigurer.hasExistingDatastore = manager?.hasGrailsPlugin("hibernate") || manager?.hasGrailsPlugin("hibernate4")
        methodsConfigurer.failOnError = config.getProperty('grails.gorm.failOnError', Boolean, false)
        methodsConfigurer.configure()
    }
}
