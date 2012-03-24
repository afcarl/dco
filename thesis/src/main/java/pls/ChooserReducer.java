package pls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;

import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.log4j.Logger;

import pls.tsp.TspSaSolution;

public abstract class ChooserReducer extends MapReduceBase implements Reducer<BytesWritable, BytesWritable, BytesWritable, BytesWritable> {
	
	private static final Logger LOG = Logger.getLogger(ChooserReducer.class);
	
	private int k = -1;
	private double bestCostAlways = -1;
	
	/**
	 * The reduce inputs can take two forms:
	 * "best"->two BytesWritables
	 * 		this is the global best solution being passed through
	 * 		the first BytesWritable contains metadata like k and the global best cost
	 * 		the second BytesWritable is the serialized global best solution
	 * "rest"->many BytesWritables
	 * 		these are the solutions found in by each of the jobs
	 * 		each BytesWritable contains
	 * 			any metadata (cost of best solution of run, cost of ending solution of run)
	 * 			the ending solution of the run
	 * 				containing T
	 * 			the best solution found in the run
	 * 				containing T
	 */
	@Override
	public void reduce(BytesWritable key, Iterator<BytesWritable> values,
			OutputCollector<BytesWritable, BytesWritable> output, Reporter reporter)
			throws IOException {
		
		if (key.equals(PlsUtil.METADATA_KEY)) {
			//first value is info regarding the problem and best solution
			BytesWritable auxInfo = values.next();
			ByteArrayInputStream bais = new ByteArrayInputStream(auxInfo.getBytes());
			DataInputStream dis = new DataInputStream(bais);
			k = dis.readInt();
			bestCostAlways = dis.readDouble();
			LOG.info("Received metadata: k=" + k + ", bestCostAlways=" + bestCostAlways);
			return;
		} else if (k == -1) {
			LOG.info("Received solutions before metadata, aborting");
			return;
			//log something here
			//output some sort of error code
		}
		
		Class<SolutionData> solDataClass = getSolutionDataClass();
		
		double bestCostThisRound = Integer.MAX_VALUE;
		SolutionData bestSolThisRound = null;
		ArrayList<SolutionData> solsThisRound = new ArrayList<SolutionData>();
		//find the best solution
		try {
			while (values.hasNext()) {
				BytesWritable bytes = values.next();
				SolutionData solData = solDataClass.newInstance();
				solData.init(bytes);
				if (solData.getBestCost() < bestCostThisRound) {
					bestCostThisRound = solData.getBestCost();
					bestSolThisRound = solData;
				}
				solsThisRound.add(solData);
			}
		} catch (IOException ex) {
			LOG.error("Error reading data, this shouldn't happen, aborting...", ex);
			return;
		} catch (Exception ex) {
			LOG.error("Error interpreting SolutionData class, aborting...", ex);
			return;
		}
		
		LOG.info("Received " + solsThisRound.size() + " solution(s)");
		
		//prepare inputs to next round
		
		LOG.info("Best cost this round: " + bestCostThisRound);
		//TODO: do the temperatures
		if (bestCostThisRound < bestCostAlways) {
			bestCostAlways = bestCostThisRound; //for passing on
			int nMappers = solsThisRound.size();
			//choose best k solutions
			List<SolutionData> bestSols = chooseKBest(solsThisRound, k);
			BytesWritable val;
			//first write out the k best
			for (SolutionData solData : bestSols) {
				val = solData.getEndSolutionBytes();
				output.collect(PlsUtil.SOLS_KEY, val);
			}
			
			//then write out the rest of the bestSolutionAlways as many times as the difference
			int nBest = nMappers - k;
			val = bestSolThisRound.getBestSolutionBytes();
			for (int i = 0; i < nBest; i++) {
				output.collect(PlsUtil.SOLS_KEY, val);
			}
		} else { //just continue with what we've got
			LOG.info("No best cost improvement, still " + bestCostAlways);
			for (SolutionData solution : solsThisRound) {
				//TODO: should we need to copy here?
				BytesWritable val = solution.getEndSolutionBytes();
				output.collect(PlsUtil.SOLS_KEY, val);
			}
		}
		
		//write out the metadata key
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);
		dos.writeInt(k);
		dos.writeDouble(bestCostAlways);
		BytesWritable metadata = new BytesWritable(baos.toByteArray());
		output.collect(PlsUtil.METADATA_KEY, metadata);
	}
	
	/**
	 * Returns in order of highest cost first.
	 */
	protected ArrayList<SolutionData> chooseKBest(List<SolutionData> solDatas, int k) {
		PriorityQueue<SolutionData> maxHeap = new PriorityQueue<SolutionData>();
		for (SolutionData solData : solDatas) {
			if (maxHeap.size() < k || solData.getBestCost() < maxHeap.peek().getBestCost()) {
				if (maxHeap.size() >= k) {
					maxHeap.remove();
				}
				maxHeap.add(solData);
			}
		}
		
		ArrayList<SolutionData> kbest = new ArrayList<SolutionData>();
		while (!maxHeap.isEmpty()) {
			kbest.add(maxHeap.remove());
		}
		
		return kbest;
	}
	
	public abstract Class<SolutionData> getSolutionDataClass();
}
