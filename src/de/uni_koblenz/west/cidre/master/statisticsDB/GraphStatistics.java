package de.uni_koblenz.west.cidre.master.statisticsDB;

import java.io.Closeable;
import java.util.logging.Logger;

import de.uni_koblenz.west.cidre.common.config.impl.Configuration;

public class GraphStatistics implements Closeable {

	private final Logger logger;

	public GraphStatistics(Configuration conf, Logger logger) {
		this.logger = logger;
		// TODO Auto-generated constructor stub
	}

	public void clear() {
		// TODO Auto-generated method stub

	}

	@Override
	public void close() {
		// TODO Auto-generated method stub

	}

	public void count(long subject, long property, long object, int chunk) {
		// TODO Auto-generated method stub
		// TODO count number of triples in chunk persistently
	}

}
