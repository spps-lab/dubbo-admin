/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dubbo.admin.config;

import org.apache.dubbo.admin.common.exception.ConfigurationException;
import org.apache.dubbo.admin.common.util.Constants;
import org.apache.dubbo.admin.registry.config.GovernanceConfiguration;
import org.apache.dubbo.admin.registry.mapping.AdminMappingListener;
import org.apache.dubbo.admin.registry.mapping.ServiceMapping;
import org.apache.dubbo.admin.registry.mapping.impl.NoOpServiceMapping;
import org.apache.dubbo.admin.registry.metadata.MetaDataCollector;
import org.apache.dubbo.admin.service.impl.InstanceRegistryCache;

import org.apache.commons.lang3.StringUtils;
import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.beans.factory.ScopeBeanFactory;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.metadata.MappingListener;
import org.apache.dubbo.metadata.report.MetadataReport;
import org.apache.dubbo.metadata.report.MetadataReportInstance;
import org.apache.dubbo.registry.Registry;
import org.apache.dubbo.registry.RegistryFactory;
import org.apache.dubbo.registry.RegistryService;
import org.apache.dubbo.registry.client.ServiceDiscovery;
import org.apache.dubbo.registry.client.ServiceDiscoveryFactory;
import org.apache.dubbo.rpc.model.ApplicationModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import java.util.Arrays;
import java.util.Optional;

import static org.apache.dubbo.common.constants.RegistryConstants.ENABLE_EMPTY_PROTECTION_KEY;
import static org.apache.dubbo.registry.client.ServiceDiscoveryFactory.getExtension;

@Configuration
public class ConfigCenter {


    //centers in dubbo 2.7
    @Value("${admin.config-center:}")
    private String configCenter;

    @Value("${admin.registry.address:}")
    private String registryAddress;

    @Value("${admin.metadata-report.address:}")
    private String metadataAddress;

    @Value("${admin.metadata-report.cluster:false}")
    private boolean cluster;

    @Value("${admin.registry.group:}")
    private String registryGroup;

    @Value("${admin.config-center.group:}")
    private String configCenterGroup;

    @Value("${admin.metadata-report.group:}")
    private String metadataGroup;

    @Value("${admin.registry.namespace:}")
    private String registryNameSpace;

    @Value("${admin.config-center.namespace:}")
    private String configCenterGroupNameSpace;

    @Value("${admin.metadata-report.namespace:}")
    private String metadataGroupNameSpace;

    @Value("${admin.config-center.username:}")
    private String username;
    @Value("${admin.config-center.password:}")
    private String password;

    private static final Logger logger = LoggerFactory.getLogger(ConfigCenter.class);

    private URL configCenterUrl;
    private URL registryUrl;
    private URL metadataUrl;

    /*
     * generate dynamic configuration client
     */
    @Bean("governanceConfiguration")
    GovernanceConfiguration getDynamicConfiguration() {
        GovernanceConfiguration dynamicConfiguration = null;

        if (StringUtils.isNotEmpty(configCenter)) {
            configCenterUrl = formUrl(configCenter, configCenterGroup, configCenterGroupNameSpace, username, password);
            logger.info("Admin using config center: " + configCenterUrl);
            dynamicConfiguration = ExtensionLoader.getExtensionLoader(GovernanceConfiguration.class).getDefaultExtension();
            dynamicConfiguration.setUrl(configCenterUrl);
            dynamicConfiguration.init();
            String config = dynamicConfiguration.getConfig(Constants.DUBBO_PROPERTY);

            if (StringUtils.isNotEmpty(config)) {
                Arrays.stream(config.split("\n")).forEach(s -> {
                    if (s.startsWith(Constants.REGISTRY_ADDRESS)) {
                        String registryAddress = removerConfigKey(s);
                        registryUrl = formUrl(registryAddress, registryGroup, registryNameSpace, username, password);
                    } else if (s.startsWith(Constants.METADATA_ADDRESS)) {
                        metadataUrl = formUrl(removerConfigKey(s), metadataGroup, metadataGroupNameSpace, username, password);
                    }
                    logger.info("Registry address found from config center: " + registryUrl);
                    logger.info("Metadata address found from config center: " + registryUrl);
                });
            }
        }
        if (dynamicConfiguration == null) {
            if (StringUtils.isNotEmpty(registryAddress)) {
                registryUrl = formUrl(registryAddress, registryGroup, registryNameSpace, username, password);
                dynamicConfiguration = ExtensionLoader.getExtensionLoader(GovernanceConfiguration.class).getDefaultExtension();
                dynamicConfiguration.setUrl(registryUrl);
                dynamicConfiguration.init();
                logger.warn("you are using dubbo.registry.address, which is not recommend, please refer to: https://github.com/apache/dubbo-admin/wiki/Dubbo-Admin-configuration");
            } else {
                throw new ConfigurationException("Either config center or registry address is needed, please refer to https://github.com/apache/dubbo-admin/wiki/Dubbo-Admin-configuration");
                //throw exception
            }
        }
        return dynamicConfiguration;
    }

    /*
     * generate registry client
     */
    @Bean("dubboRegistry")
    @DependsOn("governanceConfiguration")
    Registry getRegistry() {
        Registry registry = null;
        if (registryUrl == null) {
            if (StringUtils.isBlank(registryAddress)) {
                throw new ConfigurationException("Either config center or registry address is needed, please refer to https://github.com/apache/dubbo-admin/wiki/Dubbo-Admin-configuration");
            }
            registryUrl = formUrl(registryAddress, registryGroup, registryNameSpace, username, password);
        }

        logger.info("Admin using registry address: " + registryUrl);

        RegistryFactory registryFactory = ApplicationModel.defaultModel().getExtensionLoader(RegistryFactory.class).getAdaptiveExtension();
        registry = registryFactory.getRegistry(registryUrl.addParameter(ENABLE_EMPTY_PROTECTION_KEY, String.valueOf(false)));
        return registry;
    }

    /*
     * generate metadata client
     */
    @Bean("metaDataCollector")
    @DependsOn("governanceConfiguration")
    MetaDataCollector getMetadataCollector() {
        ApplicationModel applicationModel = ApplicationModel.defaultModel();
        ScopeBeanFactory beanFactory = applicationModel.getBeanFactory();
        MetadataReportInstance metadataReportInstance = beanFactory.registerBean(MetadataReportInstance.class);

        Optional<MetadataReport> metadataReport = metadataReportInstance.getMetadataReports(true)
                .values().stream().findAny();

        if (metadataReport.isPresent()) {
            return metadataReport.get()::getServiceDefinition;
        } else {
            //NoOpMetadataCollector
            return key -> null;
        }
    }


    @Bean(destroyMethod = "destroy")
    @DependsOn("dubboRegistry")
    ServiceDiscovery getServiceDiscoveryRegistry() throws Exception {
        URL registryURL = registryUrl.setPath(RegistryService.class.getName())
                .addParameter(ENABLE_EMPTY_PROTECTION_KEY, String.valueOf(false));
        ServiceDiscoveryFactory factory = getExtension(registryURL);
        return factory.getServiceDiscovery(registryURL);
    }

    @Bean
    @DependsOn("metaDataCollector")
    ServiceMapping getServiceMapping(ServiceDiscovery serviceDiscovery, InstanceRegistryCache instanceRegistryCache) {
        ServiceMapping serviceMapping = new NoOpServiceMapping();
        if (metadataUrl == null) {
            return serviceMapping;
        }
        MappingListener mappingListener = new AdminMappingListener(serviceDiscovery, instanceRegistryCache);
        serviceMapping = ExtensionLoader.getExtensionLoader(ServiceMapping.class).getExtension(metadataUrl.getProtocol());
        serviceMapping.addMappingListener(mappingListener);
        serviceMapping.init(metadataUrl);
        return serviceMapping;
    }

    public static String removerConfigKey(String properties) {
        String[] split = properties.split("=");
        String[] address = new String[split.length - 1];
        System.arraycopy(split, 1, address, 0, split.length - 1);
        return String.join("=", address).trim();
    }

    private URL formUrl(String config, String group, String nameSpace, String username, String password) {
        URL url = URL.valueOf(config);
        if (StringUtils.isEmpty(url.getParameter(Constants.GROUP_KEY)) && StringUtils.isNotEmpty(group)) {
            url = url.addParameter(Constants.GROUP_KEY, group);
        }
        if (StringUtils.isEmpty(url.getParameter(Constants.NAMESPACE_KEY)) && StringUtils.isNotEmpty(nameSpace)) {
            url = url.addParameter(Constants.NAMESPACE_KEY, nameSpace);
        }
        if (StringUtils.isNotEmpty(username)) {
            url = url.setUsername(username);
        }
        if (StringUtils.isNotEmpty(password)) {
            url = url.setPassword(password);
        }
        return url;
    }
}
