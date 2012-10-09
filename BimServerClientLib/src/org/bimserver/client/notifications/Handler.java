package org.bimserver.client.notifications;

/******************************************************************************
 * Copyright (C) 2009-2012  BIMserver.org
 * 
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *****************************************************************************/

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.Map;

import org.bimserver.interfaces.objects.SToken;
import org.bimserver.models.log.AccessMethod;
import org.bimserver.shared.ServiceFactory;
import org.bimserver.shared.exceptions.UserException;
import org.bimserver.shared.interfaces.NotificationInterface;
import org.bimserver.shared.interfaces.PublicInterface;
import org.bimserver.shared.interfaces.ServiceInterface;
import org.bimserver.shared.meta.SService;
import org.bimserver.shared.pb.ProtocolBuffersMetaData;
import org.bimserver.shared.pb.ProtocolBuffersMetaData.MethodDescriptorContainer;
import org.bimserver.shared.pb.ReflectiveRpcChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.DynamicMessage;
import com.google.protobuf.DynamicMessage.Builder;
import com.google.protobuf.Message;

public class Handler extends Thread {
	private static final Logger LOGGER = LoggerFactory.getLogger(Handler.class);
	private final Socket socket;
	private final ProtocolBuffersMetaData protocolBuffersMetaData;
	private final SocketNotificationsClient socketNotificationsClient;
	private ServiceFactory serviceFactory;
	private boolean running;
	private final Map<String, SService> services;

	public Handler(SocketNotificationsClient socketNotificationsClient, Socket socket, final NotificationInterface notificationInterface, ProtocolBuffersMetaData protocolBuffersMetaData, Map<String, SService> services) {
		this.socketNotificationsClient = socketNotificationsClient;
		this.socket = socket;
		this.protocolBuffersMetaData = protocolBuffersMetaData;
		this.services = services;
		serviceFactory = new ServiceFactory() {
			@Override
			public <T extends PublicInterface> T newService(Class<T> interfaceClass, AccessMethod accessMethod, String remoteAddress) {
				return (T) notificationInterface;
			}

			@Override
			public String getName() {
				return null;
			}

			@Override
			public <T extends PublicInterface> T getService(Class<T> serviceInterface, SToken token) throws UserException {
				return null;
			}
		};
	}

	@Override
	public void run() {
		running = true;
		ReflectiveRpcChannel reflectiveRpcChannel = new ReflectiveRpcChannel(serviceFactory.newService(ServiceInterface.class, AccessMethod.PROTOCOL_BUFFERS, socket.getRemoteSocketAddress().toString()), protocolBuffersMetaData, services);
		try {
			while (running) {
				DataInputStream dis = new DataInputStream(socket.getInputStream());
				String serviceName = dis.readUTF();
				String methodName = dis.readUTF();
				MethodDescriptorContainer methodDescriptorContainer = protocolBuffersMetaData.getMethod(serviceName, methodName);
				
				Builder newBuilder = DynamicMessage.newBuilder(methodDescriptorContainer.getInputDescriptor());
				newBuilder.mergeDelimitedFrom(dis);
				DynamicMessage request = newBuilder.build();
				Message response = reflectiveRpcChannel.callBlockingMethod(methodDescriptorContainer, request);
				response.writeDelimitedTo(socket.getOutputStream());
			}
		} catch (IOException e) {
			LOGGER.error("", e);
		}
		socketNotificationsClient.notifyDisconnect();
	}

	public void close() {
		running = false;
		interrupt();
		try {
			socket.close();
		} catch (IOException e) {
			LOGGER.error("", e);
		}
	}
}