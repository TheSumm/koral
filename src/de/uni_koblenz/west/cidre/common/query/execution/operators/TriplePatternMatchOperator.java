package de.uni_koblenz.west.cidre.common.query.execution.operators;

import java.io.File;
import java.util.Iterator;

import de.uni_koblenz.west.cidre.common.query.Mapping;
import de.uni_koblenz.west.cidre.common.query.TriplePattern;
import de.uni_koblenz.west.cidre.common.query.execution.QueryOperatorBase;
import de.uni_koblenz.west.cidre.slave.triple_store.TripleStoreAccessor;

public class TriplePatternMatchOperator extends QueryOperatorBase {

	private final TriplePattern pattern;

	private final TripleStoreAccessor tripleStore;

	private Iterator<Mapping> iterator;

	public TriplePatternMatchOperator(long id, long coordinatorId,
			long estimatedWorkLoad, int numberOfSlaves, int cacheSize,
			File cacheDirectory, TriplePattern pattern,
			TripleStoreAccessor tripleStore) {
		super(id, coordinatorId, estimatedWorkLoad, numberOfSlaves, cacheSize,
				cacheDirectory);
		this.pattern = pattern;
		this.tripleStore = tripleStore;
	}

	public TriplePatternMatchOperator(short slaveId, int queryId, short taskId,
			long coordinatorId, long estimatedWorkLoad, int numberOfSlaves,
			int cacheSize, File cacheDirectory, TriplePattern pattern,
			TripleStoreAccessor tripleStore) {
		super(slaveId, queryId, taskId, coordinatorId, estimatedWorkLoad,
				numberOfSlaves, cacheSize, cacheDirectory);
		this.pattern = pattern;
		this.tripleStore = tripleStore;
	}

	@Override
	public long[] getResultVariables() {
		return pattern.getVariables();
	}

	@Override
	public long getFirstJoinVar() {
		long[] vars = pattern.getVariables();
		long min = Long.MAX_VALUE;
		for (long var : vars) {
			if (var < min) {
				min = var;
			}
		}
		return min;
	}

	@Override
	public long getCurrentTaskLoad() {
		if (iterator == null) {
			return 0;
		}
		// TODO Auto-generated method stub
		return 0;
	}

	@Override
	protected void closeInternal() {
	}

	@Override
	protected void executeOperationStep() {
		if (getEstimatedTaskLoad() == 0) {
			return;
		}
		if (iterator == null) {
			iterator = tripleStore.lookup(recycleCache, pattern).iterator();
			// TODO Auto-generated method stub
		}

	}

	@Override
	protected void executeFinalStep() {
	}

	@Override
	protected boolean isFinishedInternal() {
		return getEstimatedTaskLoad() == 0
				|| (iterator != null && !iterator.hasNext());
	}

}