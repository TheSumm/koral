package de.uni_koblenz.west.cidre.common.networManager;

import java.util.logging.Logger;

import javax.xml.ws.WebServiceException;

import org.zeromq.ZContext;
import org.zeromq.ZMQ;
import org.zeromq.ZMQ.Socket;

import com.sun.xml.internal.ws.Closeable;

import de.uni_koblenz.west.cidre.common.config.impl.Configuration;

public class NetworkManager implements Closeable {

	private final Logger logger;

	private final ZContext context;

	private final Socket receiver;

	private final Socket[] senders;

	public NetworkManager(Configuration conf, Logger logger,
			String[] currentServer) {
		this.logger = logger;
		context = NetworkContextFactory.getNetworkContext();

		receiver = context.createSocket(ZMQ.PULL);
		receiver.bind("tcp://" + currentServer[0] + ":" + currentServer[1]);

		if (logger != null) {
			logger.info("listening on tcp://" + currentServer[0] + ":"
					+ currentServer[1]);
		}

		senders = new Socket[conf.getNumberOfSlaves() + 1];
		String[] master = conf.getMaster();
		senders[0] = context.createSocket(ZMQ.PUSH);
		senders[0].bind("tcp://" + master[0] + ":" + master[1]);
		for (int i = 1; i < senders.length; i++) {
			String[] slave = conf.getSlave(i - 1);
			senders[i] = context.createSocket(ZMQ.PUSH);
			senders[i].connect("tcp://" + slave[0] + ":" + slave[1]);
		}
	}

	@Override
	public void close() throws WebServiceException {
		for (Socket soc : senders) {
			soc.close();
		}
		receiver.close();
		NetworkContextFactory.destroyNetworkContext(context);
		if (logger != null) {
			logger.info("connection closed");
		}
	}

}
