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

import javax.servlet.ServletContext;

import com.netflix.eureka.EurekaServerConfig;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.netflix.eureka.server.event.EurekaRegistryAvailableEvent;
import org.springframework.cloud.netflix.eureka.server.event.EurekaServerStartedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.context.ServletContextAware;

/**
 * 通过starter初始化和启动eureka,并抛出两个事件：EurekaRegistryAvailableEvent服务注册事件，EurekaServerStartedEvent服务启动事件，
 * EurekaServer初始化核心的代码在eurekaServerBootstrap.contextInitialized中
 * @author Dave Syer
 */
@Configuration(proxyBeanMethods = false)
public class EurekaServerInitializerConfiguration
		implements ServletContextAware, SmartLifecycle, Ordered {

	private static final Log log = LogFactory
			.getLog(EurekaServerInitializerConfiguration.class);
	//EurekaServer 配置
	@Autowired
	private EurekaServerConfig eurekaServerConfig;
	//Servlet上下文
	private ServletContext servletContext;
	//应用上下文对象
	@Autowired
	private ApplicationContext applicationContext;
	//启动引导
	@Autowired
	private EurekaServerBootstrap eurekaServerBootstrap;

	private boolean running;

	private int order = 1;
	//初始化Servlet上下文
	@Override
	public void setServletContext(ServletContext servletContext) {
		this.servletContext = servletContext;
	}
	//开始方法，复写于 SmartLifecycle 在Spring启动的时候，该方法会被地调用，
	@Override
	public void start() {
		new Thread(() -> {
			try {
				// TODO: is this class even needed now?
				//初始化EurekaServer上下文，启动EurekaServer
				eurekaServerBootstrap.contextInitialized(
						EurekaServerInitializerConfiguration.this.servletContext);
				log.info("Started Eureka Server");
				//发布一个EurekaRegistryAvailableEvent注册事件
				publish(new EurekaRegistryAvailableEvent(getEurekaServerConfig()));
				//改变running状态true
				EurekaServerInitializerConfiguration.this.running = true;
				//发布EurekaServer启动事件EurekaServerStartedEvent
				publish(new EurekaServerStartedEvent(getEurekaServerConfig()));
			}
			catch (Exception ex) {
				// Help!
				log.error("Could not initialize Eureka servlet context", ex);
			}
		}).start();
	}

	private EurekaServerConfig getEurekaServerConfig() {
		return this.eurekaServerConfig;
	}

	private void publish(ApplicationEvent event) {
		this.applicationContext.publishEvent(event);
	}
	//生命周期，停止，销毁eurekaServer
	@Override
	public void stop() {
		this.running = false;
		eurekaServerBootstrap.contextDestroyed(this.servletContext);
	}

	@Override
	public boolean isRunning() {
		return this.running;
	}

	@Override
	public int getPhase() {
		return 0;
	}

	@Override
	public boolean isAutoStartup() {
		return true;
	}

	@Override
	public void stop(Runnable callback) {
		callback.run();
	}

	@Override
	public int getOrder() {
		return this.order;
	}

}
