/*
 * JBoss, Home of Professional Open Source
 * Copyright 2010, JBoss Inc., and individual contributors as indicated
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.remoting3;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import org.jboss.remoting3.spi.AbstractHandleableCloseable;
import org.jboss.remoting3.spi.ConnectionHandler;
import org.jboss.remoting3.spi.ConnectionHandlerFactory;
import org.jboss.remoting3.spi.ConnectionProviderContext;
import org.jboss.remoting3.spi.SpiUtils;
import org.xnio.FutureResult;
import org.xnio.IoFuture;
import org.xnio.OptionMap;
import org.xnio.TranslatingResult;

class ConnectionImpl extends AbstractHandleableCloseable<Connection> implements Connection {

    private final Attachments attachments = new BasicAttachments();

    private final EndpointImpl endpoint;
    private final ConnectionHandler connectionHandler;
    private final String name;

    ConnectionImpl(final EndpointImpl endpoint, final ConnectionHandlerFactory connectionHandlerFactory, final ConnectionProviderContext connectionProviderContext, final String name) {
        super(endpoint.getExecutor());
        this.endpoint = endpoint;
        this.name = name;
        connectionHandler = connectionHandlerFactory.createInstance(endpoint.new LocalConnectionContext(connectionProviderContext, this));
    }

    protected void closeAction() throws IOException {
        connectionHandler.close();
    }

    public <I, O> IoFuture<? extends Client<I, O>> openClient(final String serviceType, final String groupName, final Class<I> requestClass, final Class<O> replyClass) {
        return openClient(serviceType, groupName, requestClass, replyClass, OptionMap.EMPTY);
    }

    public <I, O> IoFuture<? extends Client<I, O>> openClient(final String serviceType, final String groupName, final Class<I> requestClass, final Class<O> replyClass, final OptionMap optionMap) {
        ClassLoader classLoader;
        final SecurityManager sm = System.getSecurityManager();
        if (sm == null) {
            classLoader = Thread.currentThread().getContextClassLoader();
        } else {
            classLoader = AccessController.doPrivileged(new PrivilegedAction<ClassLoader>() {
                public ClassLoader run() {
                    return Thread.currentThread().getContextClassLoader();
                }
            });
        }
        return openClient(serviceType, groupName, requestClass, replyClass, classLoader, optionMap);
    }

    public <I, O> IoFuture<? extends Client<I, O>> openClient(final String serviceType, final String groupName, final Class<I> requestClass, final Class<O> replyClass, final ClassLoader classLoader, final OptionMap optionMap) {
        final FutureResult<Client<I, O>> futureResult = new FutureResult<Client<I, O>>();
        futureResult.addCancelHandler(connectionHandler.open(serviceType, groupName, new ClientWrapper<I, O>(endpoint, futureResult, requestClass, replyClass, classLoader), classLoader, optionMap));
        return futureResult.getIoFuture();
    }

    public <I, O> ClientConnector<I, O> createClientConnector(final RequestListener<I, O> listener, final Class<I> requestClass, final Class<O> replyClass) throws IOException {
        return createClientConnector(listener, requestClass, replyClass, OptionMap.EMPTY);
    }

    public <I, O> ClientConnector<I, O> createClientConnector(final RequestListener<I, O> listener, final Class<I> requestClass, final Class<O> replyClass, final OptionMap optionMap) throws IOException {
        final ClientContextImpl context = new ClientContextImpl(getExecutor(), this);
        final LocalRequestHandler localRequestHandler = endpoint.createLocalRequestHandler(listener, context, requestClass, replyClass);
        final RequestHandlerConnector connector = connectionHandler.createConnector(localRequestHandler);
        context.addCloseHandler(SpiUtils.closingCloseHandler(localRequestHandler));
        return new ClientConnectorImpl<I, O>(connector, endpoint, requestClass, replyClass, context);
    }

    public Attachments getAttachments() {
        return attachments;
    }

    public String toString() {
        return "Connection to " + name;
    }

    private static class ClientWrapper<I, O> extends TranslatingResult<RemoteRequestHandler, Client<I, O>> {

        private final Class<I> requestClass;
        private final Class<O> replyClass;
        private final EndpointImpl endpoint;
        private final ClassLoader classLoader;

        public ClientWrapper(final EndpointImpl endpoint, final FutureResult<Client<I, O>> futureResult, final Class<I> requestClass, final Class<O> replyClass, final ClassLoader classLoader) {
            super(futureResult);
            this.requestClass = requestClass;
            this.replyClass = replyClass;
            this.endpoint = endpoint;
            this.classLoader = classLoader;
        }

        protected Client<I, O> translate(final RemoteRequestHandler input) throws IOException {
            return endpoint.createClient(input, requestClass, replyClass, classLoader);
        }
    }
}