package pls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.Random;

import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.log4j.Logger;

import pls.tsp.TspSaRunner;
import pls.tsp.TspSaSolution;

public abstract class PlsMapper extends MapReduceBase implements Mapper<BytesWritable, BytesWritable, BytesWritable, BytesWritable> {

	private static final Logger LOG = Logger.getLogger(PlsMapper.class);
	
	private static final int TIME = 60000;
	
	public abstract Class<PlsSolution> getSolutionClass();
	
	public abstract Class<PlsRunner> getRunnerClass();
	
	@Override
	public void map(BytesWritable key, BytesWritable value, OutputCollector<BytesWritable, BytesWritable> output, Reporter reporter)
		throws IOException {
		
		LOG.info("Received map input with key \"" + new String(key.getBytes()) + "\"");
		
		long startTime = System.currentTimeMillis();
		
		//pass metadata on through
		if (key.equals(PlsUtil.METADATA_KEY)) {
			output.collect(key, value);
			LOG.info("Passing on metadata");
			return;
		}
		
		Class<PlsSolution> solutionClass= getSolutionClass();
		Class<PlsRunner> plsRunnerClass = getRunnerClass();
		
		byte[] input = value.getBytes();
		ByteArrayInputStream bais = new ByteArrayInputStream(input);
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
//		TspSaRunner runner = new TspSaRunner(rand, stats);
			
//		PlsSolution[] solutions = run(runner, sol, rand);
		PlsSolution[] solutions = runner.run(sol, TIME, rand);
		for (PlsSolution newSol : solutions) {
			newSol.setParentSolutionId(sol.getSolutionId());
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
			solutions[i].writeToStream(dos);
		}
		output.collect(PlsUtil.SOLS_KEY, new BytesWritable(baos.toByteArray()));
		
		long endTime = System.currentTimeMillis();
		LOG.info("Total time: " + (endTime-startTime) + " ms");
	}
	
	public PlsSolution[] run(PlsRunner runner, PlsSolution initSol, Random rand) {
		final int numThreads = Runtime.getRuntime().availableProcessors();
		RunnerThread[] threads = new RunnerThread[numThreads];
		for (int i = 0; i < numThreads; i++) {
			threads[i] = new RunnerThread(runner, initSol, TIME, rand);
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
		private int timeMs;
		private Random rand;
		
		public RunnerThread(PlsRunner runner, PlsSolution initSol, int timeMs, Random rand) {
			this.runner = runner;
			this.initSol = initSol;
			rand = new Random(rand.nextLong());
		}
		
		public void run() {
			ret = runner.run(initSol, timeMs, rand);
		}
		
		public PlsSolution[] getResults() {
			return ret;
		}
	}
}
