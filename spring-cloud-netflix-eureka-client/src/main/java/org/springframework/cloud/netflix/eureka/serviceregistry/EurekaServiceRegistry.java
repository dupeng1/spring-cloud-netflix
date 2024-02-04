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

import java.util.HashMap;

import com.netflix.appinfo.InstanceInfo;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.cloud.client.serviceregistry.ServiceRegistry;

import static com.netflix.appinfo.InstanceInfo.InstanceStatus.UNKNOWN;

/**
 * @author Spencer Gibb
 */

/**
 * Eureka注册过程是以EurekaServiceRegistry类为主体，EurekaRegistration只是作为参数
 */
public class EurekaServiceRegistry implements ServiceRegistry<EurekaRegistration> {

	private static final Log log = LogFactory.getLog(EurekaServiceRegistry.class);

	//注册，注册方法中并没有真正调用Eureka的方法去执行注册，而是仅仅设置了一个状态以及设置健康检查处理器
	@Override
	public void register(EurekaRegistration reg) {
		// 初始化信息
		maybeInitializeClient(reg);

		if (log.isInfoEnabled()) {
			log.info("Registering application "
					+ reg.getApplicationInfoManager().getInfo().getAppName()
					+ " with eureka with status "
					+ reg.getInstanceConfig().getInitialStatus());
		}
		//设置当前实例状态，一旦这个实例的状态发生变化，只要状态不是DOWN，那么就会被监听器监听并且执行服务注册
		reg.getApplicationInfoManager()
				.setInstanceStatus(reg.getInstanceConfig().getInitialStatus());
		//注册健康检测机制
		reg.getHealthCheckHandler().ifAvailable(healthCheckHandler -> reg
				.getEurekaClient().registerHealthCheck(healthCheckHandler));
	}

	private void maybeInitializeClient(EurekaRegistration reg) {
		// force initialization of possibly scoped proxies
		reg.getApplicationInfoManager().getInfo();
		reg.getEurekaClient().getApplications();
	}

	//取消注册
	@Override
	public void deregister(EurekaRegistration reg) {
		if (reg.getApplicationInfoManager().getInfo() != null) {

			if (log.isInfoEnabled()) {
				log.info("Unregistering application "
						+ reg.getApplicationInfoManager().getInfo().getAppName()
						+ " with eureka with status DOWN");
			}
			//将状态设置为DOWN
			reg.getApplicationInfoManager()
					.setInstanceStatus(InstanceInfo.InstanceStatus.DOWN);

			// shutdown of eureka client should happen with EurekaRegistration.close()
			// auto registration will create a bean which will be properly disposed
			// manual registrations will need to call close()
		}
	}

	//设置状态
	@Override
	public void setStatus(EurekaRegistration registration, String status) {
		InstanceInfo info = registration.getApplicationInfoManager().getInfo();

		// TODO: howto deal with delete properly?
		if ("CANCEL_OVERRIDE".equalsIgnoreCase(status)) {
			registration.getEurekaClient().cancelOverrideStatus(info);
			return;
		}

		// TODO: howto deal with status types across discovery systems?
		InstanceInfo.InstanceStatus newStatus = InstanceInfo.InstanceStatus
				.toEnum(status);
		registration.getEurekaClient().setStatus(newStatus, info);
	}

	//获取状态
	@Override
	public Object getStatus(EurekaRegistration registration) {
		String appname = registration.getApplicationInfoManager().getInfo().getAppName();
		String instanceId = registration.getApplicationInfoManager().getInfo().getId();
		InstanceInfo info = registration.getEurekaClient().getInstanceInfo(appname,
				instanceId);

		HashMap<String, Object> status = new HashMap<>();
		if (info != null) {
			status.put("status", info.getStatus().toString());
			status.put("overriddenStatus", info.getOverriddenStatus().toString());
		}
		else {
			status.put("status", UNKNOWN.toString());
		}

		return status;
	}

	//关闭服务
	public void close() {
	}

}
