/*
 * Copyright 2012-2020 the original author or authors.
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

package org.springframework.cloud.loadbalancer.core;

import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import reactor.cache.CacheFlux;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cloud.client.ServiceInstance;

/**
 * A {@link ServiceInstanceListSupplier} implementation that tries retrieving
 * {@link ServiceInstance} objects from cache; if none found, retrieves instances using
 * {@link DiscoveryClientServiceInstanceListSupplier}.
 *
 * @author Spencer Gibb
 * @author Olga Maciaszek-Sharma
 * @since 2.2.0
 */
public class CachingServiceInstanceListSupplier extends DelegatingServiceInstanceListSupplier {

	private static final Log log = LogFactory.getLog(CachingServiceInstanceListSupplier.class);

	/**
	 * Name of the service cache instance.
	 * CachingServiceInstanceListSupplierCache
	 */
	public static final String SERVICE_INSTANCE_CACHE_NAME = CachingServiceInstanceListSupplier.class.getSimpleName()
			+ "Cache";

	private final Flux<List<ServiceInstance>> serviceInstances;

	/**
	 *
	 * @param delegate  org.springframework.cloud.loadbalancer.core.DiscoveryClientServiceInstanceListSupplier@58091607
	 * @param cacheManager  org.springframework.cloud.loadbalancer.cache.CaffeineBasedLoadBalancerCacheManager@37445c57
	 */
	@SuppressWarnings("unchecked")
	public CachingServiceInstanceListSupplier(ServiceInstanceListSupplier delegate, CacheManager cacheManager) {
		super(delegate);
		// 使用 CacheFlux.lookup 创建缓存逻辑
		this.serviceInstances = CacheFlux.lookup(key -> {
					// TODO: configurable cache name
					// 通过 CacheManager 获取指定名称的缓存
					Cache cache = cacheManager.getCache(SERVICE_INSTANCE_CACHE_NAME);
					if (cache == null) {
						// 如果未找到缓存，记录错误并返回空的 Mono
						if (log.isErrorEnabled()) {
							log.error("Unable to find cache: " + SERVICE_INSTANCE_CACHE_NAME);
						}
						return Mono.empty();
					}
					// 从缓存中获取服务实例列表
					List<ServiceInstance> list = cache.get(key, List.class);
					if (list == null || list.isEmpty()) {
						// 如果缓存中没有服务实例列表，返回空的 Mono
						return Mono.empty();
					}
					// 将服务实例列表包装为 Flux，以便后续处理
					return Flux.just(list).materialize().collectList();
				}, delegate.getServiceId())
				// 在缓存未命中时，使用 delegate 获取服务实例列表并进行缓存
				.onCacheMissResume(delegate.get().take(1))
				// 配置写入缓存的逻辑
				.andWriteWith((key, signals) -> Flux.fromIterable(signals).dematerialize().doOnNext(instances -> {
					// 获取缓存，并将服务实例列表写入缓存
					Cache cache = cacheManager.getCache(SERVICE_INSTANCE_CACHE_NAME);
					if (cache == null) {
						// 如果未找到缓存，记录错误
						if (log.isErrorEnabled()) {
							log.error("Unable to find cache for writing: " + SERVICE_INSTANCE_CACHE_NAME);
						}
					} else {
						// 将服务实例列表写入缓存
						cache.put(key, instances);
					}
				}).then());
		/**
		 * .andWriteWith：这个方法用于配置写入缓存的逻辑。
		 * (key, signals) ->：这是一个 Lambda 表达式，接受两个参数，key 是缓存的键，signals 是写入缓存的信号流。
		 * Flux.fromIterable(signals)：将信号流转换为 Flux。
		 * .dematerialize()：将信号流还原为原始元素流，这里是服务实例列表。
		 * .doOnNext(instances -> { ... })：在每个服务实例列表元素上执行一些操作。
		 * 获取缓存：通过 cacheManager.getCache(SERVICE_INSTANCE_CACHE_NAME) 获取指定名称的缓存。
		 * 缓存存在性检查：检查缓存是否存在，如果不存在，则记录错误。
		 * 缓存写入：如果缓存存在，则将服务实例列表写入缓存。
		 * .then()：在所有操作完成后返回一个表示操作完成的 Mono。
		 */
	}


	@Override
	public Flux<List<ServiceInstance>> get() {
		return serviceInstances;
	}

}
