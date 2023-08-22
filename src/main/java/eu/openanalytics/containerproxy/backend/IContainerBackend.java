/**
 * ContainerProxy
 *
 * Copyright (C) 2016-2023 Open Analytics
 *
 * ===========================================================================
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License as published by
 * The Apache Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Apache License for more details.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/>
 */
package eu.openanalytics.containerproxy.backend;

import java.io.OutputStream;
import java.util.function.BiConsumer;

import java.util.List;
import java.util.Map;

import eu.openanalytics.containerproxy.ProxyFailedToStartException;
import eu.openanalytics.containerproxy.ContainerProxyException;
import eu.openanalytics.containerproxy.model.runtime.Proxy;
import eu.openanalytics.containerproxy.model.runtime.ProxyStartupLog;
import eu.openanalytics.containerproxy.model.runtime.ProxyStatus;
import eu.openanalytics.containerproxy.model.spec.ProxySpec;
import eu.openanalytics.containerproxy.model.runtime.ExistingContainerInfo;
import eu.openanalytics.containerproxy.model.runtime.Container;
import org.springframework.security.core.Authentication;

public interface IContainerBackend {

	/**
	 * Initialize this container backend.
	 * This method is called lazily, when the backend is needed for the first time.
	 * 
	 * @throws ContainerProxyException If anything goes wrong during initialization of the backend.
	 */
	public void initialize() throws ContainerProxyException;
	
	/**
	 * Start the given proxy, which may take some time depending on the type of backend.
	 * The proxy will be in the {@link ProxyStatus#New} state before entering this method.
	 * When this method returns, the proxy should be in the {@link ProxyStatus#Up} state.
	 *
	 * @param user the user starting the proxy
	 * @param proxy the proxy to start
	 * @param spec the spec of the proxy
	 * @param proxyStartupLogBuilder the startupLog of this proxy
	 * @throws ContainerProxyException If the startup fails for any reason.
	 */
	public Proxy startProxy(Authentication user, Proxy proxy, ProxySpec spec, ProxyStartupLog.ProxyStartupLogBuilder proxyStartupLogBuilder) throws ProxyFailedToStartException;

	/**
	 * Stop the given proxy. Any resources used by the proxy should be released.
	 * 
	 * @param proxy The proxy to stop.
	 * @throws ContainerProxyException If an error occurs while stopping the proxy.
	 */
	public void stopProxy(Proxy proxy) throws ContainerProxyException;

	default public void pauseProxy(Proxy proxy) {
		throw new IllegalStateException("PauseProxy not supported by backend");
	}

	default public Proxy resumeProxy(Authentication user, Proxy proxy, ProxySpec proxySpec) throws ProxyFailedToStartException {
		throw new IllegalStateException("ResumeProxy not supported by backend");
	}

	/**
	 * Get a function that will forward the standard output and standard error of
	 * the given proxy's containers to two output streams.
	 * 
	 * The function will be executed in a separate thread, and is assumed to block
	 * until the container stops.
	 * 
	 * @param proxy The proxy whose container output should be attached to the output streams.
	 * @return A function that will attach the output, or null if this backend does
	 * not support output attaching.
	 */
	public BiConsumer<OutputStream, OutputStream> getOutputAttacher(Proxy proxy);

	/**
	 * Scans for running/existing apps that need to be recovered.
     *
	 * @return a list of existing containers.
	 */
	public List<ExistingContainerInfo> scanExistingContainers() throws Exception;

	/**
	 * Setups the port mapping for an existing proxy in exact the same way as if the proxy was newly created.
	 *
	 * @param proxy        The proxy to create the port mapping for.
	 * @param container    The specific container to create the port mapping for.
	 * @param portBindings The portbindings of this container, as generated by the scanExistingContainers method.
	 */
	public Container setupPortMappingExistingProxy(Proxy proxy, Container container, Map<Integer, Integer> portBindings, String serviceName) throws Exception;

	// TODO move implementation to abstract class
	default public Boolean supportsPause() {
		return false;
	}

    default public Proxy addRuntimeValuesBeforeSpel(Authentication user, ProxySpec spec, Proxy proxy) throws ContainerProxyException {
		return proxy;
	}

}
