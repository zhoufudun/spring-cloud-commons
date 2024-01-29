/*
 * Copyright 2012-2019 the original author or authors.
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

package org.springframework.cloud.context.named;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.MapPropertySource;

/**
 * Creates a set of child contexts that allows a set of Specifications to define the beans
 * in each child context.
 * <p>
 * Ported from spring-cloud-netflix FeignClientFactory and SpringClientFactory
 * <p>
 * Spring Cloud 中它为了实现不同的微服务具有不同的配置，例如不同的FeignClient会使用不同的ApplicationContext，从各自的上下文中获取不同配置进行实例化。在什么场景下我们会需要这种机制呢？ 例如，认证服务是会高频访问的服务，它的客户端超时时间应该要设置的比较小；而报表服务因为涉及到大量的数据查询和统计，它的超时时间就应该设置的比较大
 * <p>
 * 著作权归作者所有。商业转载请联系作者获得授权，非商业转载请注明出处。
 *
 * @param <C> specification
 * @author Spencer Gibb
 * @author Dave Syer
 */
// TODO: add javadoc
public abstract class NamedContextFactory<C extends NamedContextFactory.Specification>
		implements DisposableBean, ApplicationContextAware {

	private final String propertySourceName; // ribbon

	private final String propertyName; // ribbon.client.name

	private Map<String, AnnotationConfigApplicationContext> contexts = new ConcurrentHashMap<>();
	/**
	 * 对于使用了Ribbon和Nacos情况下, 项目启动后，在第一次请求进来之前：
	 * {
	 * default.org.springframework.cloud.netflix.ribbon.RibbonAutoConfiguration=RibbonClientSpecification{name='default.org.springframework.cloud.netflix.ribbon.RibbonAutoConfiguration', configuration=[]},
	 * default.com.alibaba.cloud.nacos.ribbon.RibbonNacosAutoConfiguration=RibbonClientSpecification{name='default.com.alibaba.cloud.nacos.ribbon.RibbonNacosAutoConfiguration', configuration=[class com.alibaba.cloud.nacos.ribbon.NacosRibbonClientConfiguration]}
	 * }
	 */
	private Map<String, C> configurations = new ConcurrentHashMap<>();

	/**
	 * org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext@1b5bc39d, started on Mon Jan 29 15:18:23 CST 2024, parent: org.springframework.context.annotation.AnnotationConfigApplicationContext@81d9a72
	 */
	private ApplicationContext parent;

	/**
	 * class org.springframework.cloud.netflix.ribbon.RibbonClientConfiguration
	 */
	private Class<?> defaultConfigType;

	/**
	 * 使用SpringClientFactory时
	 *
	 * @param defaultConfigType
	 * @param propertySourceName
	 * @param propertyName
	 */
	public NamedContextFactory(Class<?> defaultConfigType, String propertySourceName,
							   String propertyName) {
		// class org.springframework.cloud.netflix.ribbon.RibbonClientConfiguration
		this.defaultConfigType = defaultConfigType;
		// ribbon
		this.propertySourceName = propertySourceName;
		// ribbon.client.name
		this.propertyName = propertyName;
	}

	@Override
	public void setApplicationContext(ApplicationContext parent) throws BeansException {
		this.parent = parent;
	}

	public void setConfigurations(List<C> configurations) {
		for (C client : configurations) {
			this.configurations.put(client.getName(), client);
		}
	}

	public Set<String> getContextNames() {
		return new HashSet<>(this.contexts.keySet());
	}

	@Override
	public void destroy() {
		Collection<AnnotationConfigApplicationContext> values = this.contexts.values();
		for (AnnotationConfigApplicationContext context : values) {
			// This can fail, but it never throws an exception (you see stack traces
			// logged as WARN).
			context.close();
		}
		this.contexts.clear();
	}

	protected AnnotationConfigApplicationContext getContext(String name) {
		if (!this.contexts.containsKey(name)) {
			synchronized (this.contexts) {
				if (!this.contexts.containsKey(name)) {
					this.contexts.put(name, createContext(name));
				}
			}
		}
		return this.contexts.get(name);
	}

	/**
	 * 每一个FeignClient创建一个对应的上下文
	 *
	 * @param name nacos-user-service
	 * @return 注解类型的上下文
	 */
	protected AnnotationConfigApplicationContext createContext(String name) {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		if (this.configurations.containsKey(name)) {
			for (Class<?> configuration : this.configurations.get(name).getConfiguration()) {
				context.register(configuration);
			}
		}
		for (Map.Entry<String, C> entry : this.configurations.entrySet()) {
			if (entry.getKey().startsWith("default.")) {
				for (Class<?> configuration : entry.getValue().getConfiguration()) {
					context.register(configuration);
				}
			}
		}
		/**
		 *  注册一些默认的配置类到上下文中，其中包括 PropertyPlaceholderAutoConfiguration
		 */
		context.register(PropertyPlaceholderAutoConfiguration.class, this.defaultConfigType);
		/**
		 * 向上下文的环境中添加属性源，这里创建了一个 MapPropertySource 用于存放属性值
		 */
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(this.propertySourceName, Collections.<String, Object>singletonMap(this.propertyName, name)));
		if (this.parent != null) {
			// 如果存在父上下文，则将其设置为当前上下文的父上下文。这是为了让上下文能够共享环境和Bean。
			// Uses Environment from parent as well as beans
			context.setParent(this.parent);
			// jdk11 issue
			// https://github.com/spring-cloud/spring-cloud-netflix/issues/3101
			context.setClassLoader(this.parent.getClassLoader());
		}
		// 设置上下文的显示名称，通过调用 generateDisplayName 方法生成
		context.setDisplayName(generateDisplayName(name));
		// 刷新上下文，使其准备好使用
		context.refresh();
		return context;
	}

	protected String generateDisplayName(String name) {
		return this.getClass().getSimpleName() + "-" + name; // SpringClientFactory-nacos-user-service
	}

	public <T> T getInstance(String name, Class<T> type) {
		AnnotationConfigApplicationContext context = getContext(name);
		if (BeanFactoryUtils.beanNamesForTypeIncludingAncestors(context,
				type).length > 0) {
			return context.getBean(type);
		}
		return null;
	}

	public <T> ObjectProvider<T> getLazyProvider(String name, Class<T> type) {
		return new ClientFactoryObjectProvider<>(this, name, type);
	}

	public <T> ObjectProvider<T> getProvider(String name, Class<T> type) {
		AnnotationConfigApplicationContext context = getContext(name);
		return context.getBeanProvider(type);
	}

	public <T> T getInstance(String name, Class<?> clazz, Class<?>... generics) {
		ResolvableType type = ResolvableType.forClassWithGenerics(clazz, generics);
		return getInstance(name, type);
	}

	@SuppressWarnings("unchecked")
	public <T> T getInstance(String name, ResolvableType type) {
		AnnotationConfigApplicationContext context = getContext(name);
		String[] beanNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(context,
				type);
		if (beanNames.length > 0) {
			for (String beanName : beanNames) {
				if (context.isTypeMatch(beanName, type)) {
					return (T) context.getBean(beanName);
				}
			}
		}
		return null;
	}

	public <T> Map<String, T> getInstances(String name, Class<T> type) {
		AnnotationConfigApplicationContext context = getContext(name);
		if (BeanFactoryUtils.beanNamesForTypeIncludingAncestors(context,
				type).length > 0) {
			return BeanFactoryUtils.beansOfTypeIncludingAncestors(context, type);
		}
		return null;
	}

	/**
	 * Specification with name and configuration.
	 */
	public interface Specification {

		String getName();

		Class<?>[] getConfiguration();

	}

}
