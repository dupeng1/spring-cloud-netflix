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

package org.springframework.cloud.netflix.eureka.serviceregistry;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.cloud.client.discovery.event.InstanceRegisteredEvent;
import org.springframework.cloud.client.serviceregistry.AutoServiceRegistration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.SmartApplicationListener;
import org.springframework.core.Ordered;

/**
 * @author Dave Syer
 * @author Spencer Gibb
 * @author Jon Schneider
 * @author Jakub Narloch
 * @author Raiyan Raiyan
 */
public class EurekaAutoServiceRegistration implements AutoServiceRegistration,
		SmartLifecycle, Ordered, SmartApplicationListener {

	private static final Log log = LogFactory.getLog(EurekaAutoServiceRegistration.class);

	private AtomicBoolean running = new AtomicBoolean(false);

	private int order = 0;

	private AtomicInteger port = new AtomicInteger(0);

	private ApplicationContext context;

	private EurekaServiceRegistry serviceRegistry;

	private EurekaRegistration registration;

	public EurekaAutoServiceRegistration(ApplicationContext context,
			EurekaServiceRegistry serviceRegistry, EurekaRegistration registration) {
		this.context = context;
		this.serviceRegistry = serviceRegistry;
		this.registration = registration;
	}

	//Eureka初始化和注册
	@Override
	public void start() {
		// only set the port if the nonSecurePort or securePort is 0 and this.port != 0
		if (this.port.get() != 0) {
			if (this.registration.getNonSecurePort() == 0) {
				this.registration.setNonSecurePort(this.port.get());
			}

			if (this.registration.getSecurePort() == 0 && this.registration.isSecure()) {
				this.registration.setSecurePort(this.port.get());
			}
		}

		// only initialize if nonSecurePort is greater than 0 and it isn't already running
		// because of containerPortInitializer below
		//检测port
		//如果没有启动过 并且 非安全接口大于0
		if (!this.running.get() && this.registration.getNonSecurePort() > 0) {
			//调用serviceRegistry 将自己的registration 注册上去
			this.serviceRegistry.register(this.registration);
			// 发布一个事件
			this.context.publishEvent(new InstanceRegisteredEvent<>(this,
					this.registration.getInstanceConfig()));
			// 设置running状态为true下一次当进入start（）方法之后this.running.get()取为true就不会再进行一次注册了
			this.running.set(true);
		}
	}

	@Override
	public void stop() {
		//取消注册
		this.serviceRegistry.deregister(this.registration);
		//设置running状态为 flase
		this.running.set(false);
	}

	@Override
	public boolean isRunning() {
		return this.running.get();
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
		stop();
		callback.run();
	}

	@Override
	public int getOrder() {
		return this.order;
	}

	@Override
	public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
		return WebServerInitializedEvent.class.isAssignableFrom(eventType)
				|| ContextClosedEvent.class.isAssignableFrom(eventType);
	}

	@Override
	public void onApplicationEvent(ApplicationEvent event) {
		if (event instanceof WebServerInitializedEvent) {
			onApplicationEvent((WebServerInitializedEvent) event);
		}
		else if (event instanceof ContextClosedEvent) {
			onApplicationEvent((ContextClosedEvent) event);
		}
	}

	public void onApplicationEvent(WebServerInitializedEvent event) {
		// TODO: take SSL into account
		String contextName = event.getApplicationContext().getServerNamespace();
		if (contextName == null || !contextName.equals("management")) {
			int localPort = event.getWebServer().getPort();
			if (this.port.get() == 0) {
				log.info("Updating port to " + localPort);
				this.port.compareAndSet(0, localPort);
				start();
			}
		}
	}

	public void onApplicationEvent(ContextClosedEvent event) {
		if (event.getApplicationContext() == context) {
			stop();
		}
	}

}
