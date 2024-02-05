/*
 * Copyright 2013-2020 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.netflix.eureka.server;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.servlet.Filter;
import javax.ws.rs.Path;
import javax.ws.rs.core.Application;
import javax.ws.rs.ext.Provider;

import com.netflix.appinfo.ApplicationInfoManager;
import com.netflix.discovery.EurekaClient;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.converters.EurekaJacksonCodec;
import com.netflix.discovery.converters.wrappers.CodecWrapper;
import com.netflix.discovery.converters.wrappers.CodecWrappers;
import com.netflix.eureka.DefaultEurekaServerContext;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.EurekaServerContext;
import com.netflix.eureka.cluster.PeerEurekaNode;
import com.netflix.eureka.cluster.PeerEurekaNodes;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import com.netflix.eureka.resources.DefaultServerCodecs;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.transport.JerseyReplicationClient;
import com.sun.jersey.api.core.DefaultResourceConfig;
import com.sun.jersey.spi.container.servlet.ServletContainer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.cloud.client.actuator.HasFeatures;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.netflix.eureka.EurekaConstants;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.util.ClassUtils;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * @author Gunnar Hillert
 * @author Biju Kunjummen
 * @author Fahim Farook
 */
@Configuration(proxyBeanMethods = false)
//导入 EurekaServerInitializerConfiguration 对Eureak进行初始化
@Import(EurekaServerInitializerConfiguration.class)
//条件：EurekaServerMarkerConfiguration.Marker是通过@EnableEureakServer激活，这个条件是通过@EnableEureakServer开启
@ConditionalOnBean(EurekaServerMarkerConfiguration.Marker.class)
//Eureka仪表盘界面配置，以及实例注册的配置
@EnableConfigurationProperties({ EurekaDashboardProperties.class,
		InstanceRegistryProperties.class })
//加载server.properties 服务端配置文件
@PropertySource("classpath:/eureka/server.properties")
public class EurekaServerAutoConfiguration implements WebMvcConfigurer {

	/**
	 * List of packages containing Jersey resources required by the Eureka server.
	 */
	private static final String[] EUREKA_PACKAGES = new String[] {
			"com.netflix.discovery", "com.netflix.eureka" };

	@Autowired
	private ApplicationInfoManager applicationInfoManager;

	//eureka server服务端配置对象加载 eureka.server 开头的配置项
	//里面记录了了EurekaServer所需要的配置
	@Autowired
	private EurekaServerConfig eurekaServerConfig;
	//eureka client 客户端配置对象 加载eureka.client开头的配置项
	@Autowired
	private EurekaClientConfig eurekaClientConfig;
	//Eureka客户端
	@Autowired
	private EurekaClient eurekaClient;
	//实例注册表属性配置，加载eureka.instance.registry开头的配置
	@Autowired
	private InstanceRegistryProperties instanceRegistryProperties;

	/**
	 * A {@link CloudJacksonJson} instance.
	 */
	public static final CloudJacksonJson JACKSON_JSON = new CloudJacksonJson();

	@Bean
	public HasFeatures eurekaServerFeature() {
		return HasFeatures.namedFeature("Eureka Server",
				EurekaServerAutoConfiguration.class);
	}
	//定义Eureka Server dashboard仪表盘监控界面的端点，仪表盘端点controller，默认的路径是“/”
	@Bean
	@ConditionalOnProperty(prefix = "eureka.dashboard", name = "enabled",
			matchIfMissing = true)
	public EurekaController eurekaController() {
		return new EurekaController(this.applicationInfoManager);
	}

	static {
		CodecWrappers.registerWrapper(JACKSON_JSON);
		EurekaJacksonCodec.setInstance(JACKSON_JSON.getCodec());
	}

	@Bean
	public ServerCodecs serverCodecs() {
		return new CloudServerCodecs(this.eurekaServerConfig);
	}

	private static CodecWrapper getFullJson(EurekaServerConfig serverConfig) {
		CodecWrapper codec = CodecWrappers.getCodec(serverConfig.getJsonCodecName());
		return codec == null ? CodecWrappers.getCodec(JACKSON_JSON.codecName()) : codec;
	}

	private static CodecWrapper getFullXml(EurekaServerConfig serverConfig) {
		CodecWrapper codec = CodecWrappers.getCodec(serverConfig.getXmlCodecName());
		return codec == null ? CodecWrappers.getCodec(CodecWrappers.XStreamXml.class)
				: codec;
	}

	@Bean
	@ConditionalOnMissingBean
	public ReplicationClientAdditionalFilters replicationClientAdditionalFilters() {
		return new ReplicationClientAdditionalFilters(Collections.emptySet());
	}
	// 应用对象注册接口,应用对象注册表接口，提供了eureka集群间的服务同步功能及相关操作
	@Bean
	public PeerAwareInstanceRegistry peerAwareInstanceRegistry(
			ServerCodecs serverCodecs) {
		//客户端获取注册列表，强制初始化EurekaClient
		this.eurekaClient.getApplications(); // force initialization
		//创建实例注册器InstanceRegistry,继承了PeerAwareInstanceRegistry，PeerAwareInstanceRegistryimpl的实现
		return new InstanceRegistry(this.eurekaServerConfig, this.eurekaClientConfig,
				serverCodecs, this.eurekaClient,
				//期望最大每分钟续租次数
				this.instanceRegistryProperties.getExpectedNumberOfClientsSendingRenews(),
				this.instanceRegistryProperties.getDefaultOpenForTrafficCount());
	}
	//集群节点PeerEurekaNode的管理类
	@Bean
	@ConditionalOnMissingBean
	public PeerEurekaNodes peerEurekaNodes(PeerAwareInstanceRegistry registry,
			ServerCodecs serverCodecs,
			ReplicationClientAdditionalFilters replicationClientAdditionalFilters) {
		return new RefreshablePeerEurekaNodes(registry, this.eurekaServerConfig,
				this.eurekaClientConfig, serverCodecs, this.applicationInfoManager,
				replicationClientAdditionalFilters);
	}
	//EurekaServer的上下文对象
	@Bean
	@ConditionalOnMissingBean
	public EurekaServerContext eurekaServerContext(ServerCodecs serverCodecs,
			PeerAwareInstanceRegistry registry, PeerEurekaNodes peerEurekaNodes) {
		return new DefaultEurekaServerContext(this.eurekaServerConfig, serverCodecs,
				registry, peerEurekaNodes, this.applicationInfoManager);
	}
	//EurekaServerBootstrap  :EurekaServer的启动引导
	@Bean
	public EurekaServerBootstrap eurekaServerBootstrap(PeerAwareInstanceRegistry registry,
			EurekaServerContext serverContext) {
		return new EurekaServerBootstrap(this.applicationInfoManager,
				this.eurekaClientConfig, this.eurekaServerConfig, registry,
				serverContext);
	}

	/**
	 * Register the Jersey filter.
	 * @param eurekaJerseyApp an {@link Application} for the filter to be registered
	 * @return a jersey {@link FilterRegistrationBean}
	 */
	//注册 Jersey filter ,通过ServletContainer处理/eureka/*请求
	@Bean
	public FilterRegistrationBean<?> jerseyFilterRegistration(
			javax.ws.rs.core.Application eurekaJerseyApp) {
		FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<Filter>();
		//使用ServletContainer处理
		bean.setFilter(new ServletContainer(eurekaJerseyApp));
		bean.setOrder(Ordered.LOWEST_PRECEDENCE);
		//filter的拦截url   /eureka/*
		bean.setUrlPatterns(
				Collections.singletonList(EurekaConstants.DEFAULT_PREFIX + "/*"));

		return bean;
	}

	/**
	 * Construct a Jersey {@link javax.ws.rs.core.Application} with all the resources
	 * required by the Eureka server.
	 * @param environment an {@link Environment} instance to retrieve classpath resources
	 * @param resourceLoader a {@link ResourceLoader} instance to get classloader from
	 * @return created {@link Application} object
	 */
	//创建用Eureka服务器所需的所有资源,构建Jersey {@link javax.ws.rs.core.Application}
	@Bean
	public javax.ws.rs.core.Application jerseyApplication(Environment environment,
			ResourceLoader resourceLoader) {

		ClassPathScanningCandidateComponentProvider provider = new ClassPathScanningCandidateComponentProvider(
				false, environment);

		// Filter to include only classes that have a particular annotation.
		//
		provider.addIncludeFilter(new AnnotationTypeFilter(Path.class));
		provider.addIncludeFilter(new AnnotationTypeFilter(Provider.class));

		// Find classes in Eureka packages (or subpackages)
		//
		Set<Class<?>> classes = new HashSet<>();
		for (String basePackage : EUREKA_PACKAGES) {
			Set<BeanDefinition> beans = provider.findCandidateComponents(basePackage);
			for (BeanDefinition bd : beans) {
				Class<?> cls = ClassUtils.resolveClassName(bd.getBeanClassName(),
						resourceLoader.getClassLoader());
				classes.add(cls);
			}
		}

		// Construct the Jersey ResourceConfig
		Map<String, Object> propsAndFeatures = new HashMap<>();
		propsAndFeatures.put(
				// Skip static content used by the webapp
				ServletContainer.PROPERTY_WEB_PAGE_CONTENT_REGEX,
				EurekaConstants.DEFAULT_PREFIX + "/(fonts|images|css|js)/.*");

		DefaultResourceConfig rc = new DefaultResourceConfig(classes);
		rc.setPropertiesAndFeatures(propsAndFeatures);

		return rc;
	}

	@Bean
	@ConditionalOnBean(name = "httpTraceFilter")
	public FilterRegistrationBean<?> traceFilterRegistration(
			@Qualifier("httpTraceFilter") Filter filter) {
		FilterRegistrationBean<Filter> bean = new FilterRegistrationBean<Filter>();
		bean.setFilter(filter);
		bean.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
		return bean;
	}

	@Configuration(proxyBeanMethods = false)
	protected static class EurekaServerConfigBeanConfiguration {
		//Eureka服务端配置对象，eureka服务器运行所需的配置信息，加载eureka.server开头配置
		@Bean
		@ConditionalOnMissingBean
		public EurekaServerConfig eurekaServerConfig(EurekaClientConfig clientConfig) {
			EurekaServerConfigBean server = new EurekaServerConfigBean();
			//如果开启了eureka.client.registerWithEureka=true 注册到Eureka
			if (clientConfig.shouldRegisterWithEureka()) {
				// Set a sensible default if we are supposed to replicate
				//当eureka服务器启动时尝试去获取集群里其他服务器上的注册信息的次数，默认为5
				server.setRegistrySyncRetries(5);
			}
			return server;
		}

	}

	/**
	 * {@link PeerEurekaNodes} which updates peers when /refresh is invoked. Peers are
	 * updated only if <code>eureka.client.use-dns-for-fetching-service-urls</code> is
	 * <code>false</code> and one of following properties have changed.
	 * <p>
	 * </p>
	 * <ul>
	 * <li><code>eureka.client.availability-zones</code></li>
	 * <li><code>eureka.client.region</code></li>
	 * <li><code>eureka.client.service-url.&lt;zone&gt;</code></li>
	 * </ul>
	 */
	//可刷新的集群节点，通过监听EnvironmentChangeEvent环境改变事件，如果节点的配置改变如：
	//eureka.client.region，eureka.client.service-url配置改变就刷新集群中的节点信息
	static class RefreshablePeerEurekaNodes extends PeerEurekaNodes
			implements ApplicationListener<EnvironmentChangeEvent> {

		private ReplicationClientAdditionalFilters replicationClientAdditionalFilters;

		RefreshablePeerEurekaNodes(final PeerAwareInstanceRegistry registry,
				final EurekaServerConfig serverConfig,
				final EurekaClientConfig clientConfig, final ServerCodecs serverCodecs,
				final ApplicationInfoManager applicationInfoManager,
				final ReplicationClientAdditionalFilters replicationClientAdditionalFilters) {
			super(registry, serverConfig, clientConfig, serverCodecs,
					applicationInfoManager);
			this.replicationClientAdditionalFilters = replicationClientAdditionalFilters;
		}

		@Override
		protected PeerEurekaNode createPeerEurekaNode(String peerEurekaNodeUrl) {
			JerseyReplicationClient replicationClient = JerseyReplicationClient
					.createReplicationClient(serverConfig, serverCodecs,
							peerEurekaNodeUrl);

			this.replicationClientAdditionalFilters.getFilters()
					.forEach(replicationClient::addReplicationClientFilter);

			String targetHost = hostFromUrl(peerEurekaNodeUrl);
			if (targetHost == null) {
				targetHost = "host";
			}
			return new PeerEurekaNode(registry, targetHost, peerEurekaNodeUrl,
					replicationClient, serverConfig);
		}
		//监听事件
		@Override
		public void onApplicationEvent(final EnvironmentChangeEvent event) {
			//如果配置发生改变
			if (shouldUpdate(event.getKeys())) {
				//更新集群中的节点，通过删除旧的不可用的PeerEurekaNode，创建新的副本
				updatePeerEurekaNodes(resolvePeerUrls());
			}
		}

		/*
		 * Check whether specific properties have changed.
		 */
		//检查是否需要更新节点，检查节点的配置是否有改变
		protected boolean shouldUpdate(final Set<String> changedKeys) {
			assert changedKeys != null;

			// if eureka.client.use-dns-for-fetching-service-urls is true, then
			// service-url will not be fetched from environment.
			if (this.clientConfig.shouldUseDnsForFetchingServiceUrls()) {
				return false;
			}

			if (changedKeys.contains("eureka.client.region")) {
				return true;
			}

			for (final String key : changedKeys) {
				// property keys are not expected to be null.
				if (key.startsWith("eureka.client.service-url.")
						|| key.startsWith("eureka.client.availability-zones.")) {
					return true;
				}
			}
			return false;
		}

	}

	class CloudServerCodecs extends DefaultServerCodecs {

		CloudServerCodecs(EurekaServerConfig serverConfig) {
			super(getFullJson(serverConfig),
					CodecWrappers.getCodec(CodecWrappers.JacksonJsonMini.class),
					getFullXml(serverConfig),
					CodecWrappers.getCodec(CodecWrappers.JacksonXmlMini.class));
		}

	}

}
