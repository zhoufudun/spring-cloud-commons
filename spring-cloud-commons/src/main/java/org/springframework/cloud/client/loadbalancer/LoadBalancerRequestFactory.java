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

import java.util.List;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

/**
 * Creates {@link LoadBalancerRequest}s for {@link LoadBalancerInterceptor} and
 * {@link RetryLoadBalancerInterceptor}. Applies {@link LoadBalancerRequestTransformer}s
 * to the intercepted {@link HttpRequest}.
 *
 * @author William Tran
 */
public class LoadBalancerRequestFactory {

	private LoadBalancerClient loadBalancer;

	private List<LoadBalancerRequestTransformer> transformers;

	public LoadBalancerRequestFactory(LoadBalancerClient loadBalancer,
									  List<LoadBalancerRequestTransformer> transformers) {
		this.loadBalancer = loadBalancer;
		this.transformers = transformers;
	}

	public LoadBalancerRequestFactory(LoadBalancerClient loadBalancer) {
		this.loadBalancer = loadBalancer;
	}

	public LoadBalancerRequest<ClientHttpResponse> createRequest(
			final HttpRequest request, // org.springframework.http.client.InterceptingClientHttpRequest@31154921
			final byte[] body, // http请求body的字节数组
			final ClientHttpRequestExecution execution) { // org.springframework.http.client.InterceptingClientHttpRequest$InterceptingRequestExecution@1b300a41
		return new LoadBalancerRequest<ClientHttpResponse>() {
			/**
			 *
			 * @param instance:
			 * RibbonServer{serviceId='nacos-user-service', server=10.2.40.18:8207, secure=false, metadata={preserved.register.source=SPRING_CLOUD}}
			 */
			@Override
			public ClientHttpResponse apply(ServiceInstance instance) throws Exception {
				HttpRequest serviceRequest = new ServiceRequestWrapper(request, instance, LoadBalancerRequestFactory.this.loadBalancer);
				if (LoadBalancerRequestFactory.this.transformers != null) {
					for (LoadBalancerRequestTransformer transformer : LoadBalancerRequestFactory.this.transformers) {
						serviceRequest = transformer.transformRequest(serviceRequest, instance);
					}
				}
				// 执行执行链中的下一个拦截器
				return execution.execute(serviceRequest, body);
			}
		};
	}

}
