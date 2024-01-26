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

package org.springframework.cloud.client.loadbalancer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestTemplate;

/**
 * Auto-configuration for Ribbon (client-side load balancing).
 * <p>
 * <p>
 * 在 @LoadBalanced 同包下，有一个 LoadBalancerAutoConfiguration 自动化配置类，从注释也可以看出，这是客户端负载均衡 Ribbon 的自动化配置类。
 * 从这个自动化配置类可以得到如下信息：
 * <p>
 * 首先要有 RestTemplate 的依赖和定义了 LoadBalancerClient 对象的前提下才会触发这个自动化配置类，这也对应了前面，RestTemplate 要用 LoadBalancerClient  来配置。
 * 接着可以看到这个类注入了带有 @LoadBalanced 注解的 RestTemplate 对象，就是要对这部分对象增加负载均衡的能力。
 * 从 SmartInitializingSingleton 的构造中可以看到，就是在 bean 初始化完成后，用 RestTemplateCustomizer 定制化 RestTemplate。
 * 再往下可以看到，RestTemplateCustomizer 其实就是向 RestTemplate 中添加了 LoadBalancerInterceptor 这个拦截器。
 * 而 LoadBalancerInterceptor 的构建又需要 LoadBalancerClient 和 LoadBalancerRequestFactory，LoadBalancerRequestFactory
 * 则通过 LoadBalancerClient 和 LoadBalancerRequestTransformer 构造完成
 * <p>
 * 链接：https://juejin.cn/post/6931145846234808333
 *
 * @author Spencer Gibb
 * @author Dave Syer
 * @author Will Tran
 * @author Gang Li
 */
@Configuration
@ConditionalOnClass(RestTemplate.class) // 有 RestTemplate 的依赖
@ConditionalOnBean(LoadBalancerClient.class) // 定义了 LoadBalancerClient 的 bean 对象
public class LoadBalancerAutoConfiguration {

	@LoadBalanced   // 注入 @LoadBalanced 标记的 RestTemplate 对象
	@Autowired(required = false)
	private List<RestTemplate> restTemplates = Collections.emptyList();

	@Autowired(required = false)
	private List<LoadBalancerRequestTransformer> transformers = Collections.emptyList();

	@Bean
	public SmartInitializingSingleton loadBalancedRestTemplateInitializerDeprecated(
			final ObjectProvider<List<RestTemplateCustomizer>> restTemplateCustomizers) {
		return new SmartInitializingSingleton() {
			@Override
			public void afterSingletonsInstantiated() {
				restTemplateCustomizers.ifAvailable(customizers -> {
					for (RestTemplate restTemplate : LoadBalancerAutoConfiguration.this.restTemplates) {
						for (RestTemplateCustomizer customizer : customizers) {
							customizer.customize(restTemplate); // 利用 RestTemplateCustomizer 定制化 restTemplate
						}
					}
				});
			}
		};
	}

	@Bean
	@ConditionalOnMissingBean
	public LoadBalancerRequestFactory loadBalancerRequestFactory(
			LoadBalancerClient loadBalancerClient) {
		return new LoadBalancerRequestFactory(loadBalancerClient, this.transformers);
	}

	@Configuration
	@ConditionalOnMissingClass("org.springframework.retry.support.RetryTemplate")
	static class LoadBalancerInterceptorConfig {

		// 创建 LoadBalancerInterceptor 需要 LoadBalancerClient 和 LoadBalancerRequestFactory
		@Bean
		public LoadBalancerInterceptor ribbonInterceptor(LoadBalancerClient loadBalancerClient, LoadBalancerRequestFactory requestFactory) {
			return new LoadBalancerInterceptor(loadBalancerClient, requestFactory);
		}

		@Bean
		@ConditionalOnMissingBean // 自定义RestTemplate的行为
		public RestTemplateCustomizer restTemplateCustomizer(final LoadBalancerInterceptor loadBalancerInterceptor) {
			return new RestTemplateCustomizer() {
				@Override
				public void customize(RestTemplate restTemplate) {
					List<ClientHttpRequestInterceptor> list = new ArrayList<>(
							restTemplate.getInterceptors());
					list.add(loadBalancerInterceptor);
					restTemplate.setInterceptors(list);
				}
			};
		}

	}

	/**
	 * Auto configuration for retry mechanism.
	 */
	@Configuration
	@ConditionalOnClass(RetryTemplate.class)
	public static class RetryAutoConfiguration {

		@Bean
		@ConditionalOnMissingBean
		public LoadBalancedRetryFactory loadBalancedRetryFactory() {
			return new LoadBalancedRetryFactory() {
			};
		}

	}

	/**
	 * Auto configuration for retry intercepting mechanism.
	 */
	@Configuration
	@ConditionalOnClass(RetryTemplate.class)
	public static class RetryInterceptorAutoConfiguration {

		@Bean
		@ConditionalOnMissingBean
		public RetryLoadBalancerInterceptor ribbonInterceptor(
				LoadBalancerClient loadBalancerClient,
				LoadBalancerRetryProperties properties,
				LoadBalancerRequestFactory requestFactory,
				LoadBalancedRetryFactory loadBalancedRetryFactory) {
			return new RetryLoadBalancerInterceptor(loadBalancerClient, properties,
					requestFactory, loadBalancedRetryFactory);
		}

		@Bean
		@ConditionalOnMissingBean
		public RestTemplateCustomizer restTemplateCustomizer(
				final RetryLoadBalancerInterceptor loadBalancerInterceptor) {
			return restTemplate -> {
				List<ClientHttpRequestInterceptor> list = new ArrayList<>(
						restTemplate.getInterceptors());
				list.add(loadBalancerInterceptor);
				restTemplate.setInterceptors(list);
			};
		}

	}

}
