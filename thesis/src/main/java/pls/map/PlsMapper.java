package pls.map;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Random;

import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.log4j.Logger;

import pls.PlsMetadata;
import pls.PlsSolution;
import pls.PlsUtil;
import pls.SolutionIdGenerator;
import pls.vrp.VrpSolvingExtraData;

public abstract class PlsMapper extends MapReduceBase implements Mapper<BytesWritable, BytesWritable, BytesWritable, BytesWritable> {

	private static final Logger LOG = Logger.getLogger(PlsMapper.class);
	
	public abstract Class<PlsSolution> getSolutionClass();
	
	public abstract Class<PlsRunner> getRunnerClass();
	
	public abstract Class<Writable> getHelperDataClass();
	
	@Override
	public void map(BytesWritable key, BytesWritable value, OutputCollector<BytesWritable, BytesWritable> output, Reporter reporter)
		throws IOException {
		
		LOG.info("Received map input with key \"" + new String(key.getBytes()) + "\"");
		LOG.info("Value hash = " + value.hashCode());
		
		long startTime = System.currentTimeMillis();
		long timeToFinish = PlsUtil.getEndTimeFromKey(key);
		
		Class<PlsSolution> solutionClass = getSolutionClass();
		Class<PlsRunner> plsRunnerClass = getRunnerClass();
		
		//read start solution
		LOG.info("value.getBytes().length: " + value.getBytes().length);
		ByteArrayInputStream bais = new ByteArrayInputStream(value.getBytes(), 0, value.getLength());
		DataInputStream dis = new DataInputStream(bais);
		PlsSolution sol;
		try {
			sol = solutionClass.newInstance();
			sol.buildFromStream(dis);
		} catch (Exception ex) {
			LOG.error("Failed to read initial solution, aborting", ex);
			return;
		}
		
		LOG.info("Initial solution cost: " + sol.getCost());
		
		//use host name to add some randomness in case multiple mappers are started at the same time
		Random rand = new Random(System.currentTimeMillis() + InetAddress.getLocalHost().getHostName().hashCode());
		PlsRunner runner;
		try {
			runner = plsRunnerClass.newInstance();
		} catch (Exception ex) {
			LOG.error("Problem building PlsRunner. abortin...", ex);
			return;
		}
		
		//read metadata
		PlsMetadata metadata = new PlsMetadata();
		metadata.readFields(dis);

		//read helper data if there is any
		if (dis.available() > 0) {
			LOG.info("bytes available after reading solution: " + dis.available());
			try {
				Writable helperData = getHelperDataClass().newInstance();
				helperData.readFields(dis);
				runner.setHelperData(helperData);
				((VrpSolvingExtraData)helperData).setMaxNeighborhoods(metadata.getExtraDataNumNeighbors());
				((VrpSolvingExtraData)helperData).setAddFirstNeighborhoods(metadata.getAddFirstNeighborhoods());
			} catch (Exception ex) {
				LOG.error("Trouble reading helper data, aborting...", ex);
				return;
			}
		}
		
		PlsSolution[] solutions = runner.run(sol, timeToFinish, rand);
		for (PlsSolution newSol : solutions) {
			newSol.setParentSolutionId(sol.getSolutionId());
		}
		for (PlsSolution newSol : solutions) {
			newSol.setSolutionId(SolutionIdGenerator.generateId());
		}
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);
		
		//write out metadata before solutions
		int offset = solutions.length * 4 * 2 + 4;
		if (solutions.length > 1) {
			dos.writeDouble(solutions[0].getCost());
			for (int i = 0; i < solutions.length; i++) {
				dos.writeInt(offset);
				dos.writeInt(solutions[i].serializedSize());
				offset += solutions[i].serializedSize();
			}
		}
		
		for (int i = 0; i < solutions.length; i++) {
			Writable extraData = runner.getExtraData();
			if (extraData != null) {
				extraData.write(dos);
			}
			solutions[i].writeToStream(dos);
		}
		output.collect(PlsUtil.SOLS_KEY, new BytesWritable(baos.toByteArray()));
		
		long endTime = System.currentTimeMillis();
		LOG.info("Total time: " + (endTime-startTime) + " ms");
	}
	
	public PlsSolution[] run(PlsRunner runner, PlsSolution initSol, long timeToFinish, Random rand) {
		final int numThreads = Runtime.getRuntime().availableProcessors();
		RunnerThread[] threads = new RunnerThread[numThreads];
		for (int i = 0; i < numThreads; i++) {
			threads[i] = new RunnerThread(runner, initSol, timeToFinish, rand);
			threads[i].start();
		}
		PlsSolution[] bestSols = null;
		for (int i = 0; i < numThreads; i++) {
			try {
				threads[i].join();
				PlsSolution[] sols = threads[i].getResults();
				if (bestSols == null || sols[0].getCost() < bestSols[0].getCost()) {
					bestSols = sols;
				}
			} catch (InterruptedException ex) {
				LOG.error("Interrupted while joining, excluding thread's result");
			}
		}
		
		return bestSols;
	}
	
	private class RunnerThread extends Thread {
		private PlsSolution[] ret;
		private PlsRunner runner;
		private PlsSolution initSol;
		private long timeToFinish;
		private Random rand;
		
		public RunnerThread(PlsRunner runner, PlsSolution initSol, long timeToFinish, Random rand) {
			this.runner = runner;
			this.initSol = initSol;
			this.rand = new Random(rand.nextLong());
			this.timeToFinish = timeToFinish;
		}
		
		public void run() {
			ret = runner.run(initSol, timeToFinish, rand);
		}
		
		public PlsSolution[] getResults() {
			return ret;
		}
	}
}
