package pls;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.PriorityQueue;

import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;

import pls.tsp.TspSaSolution;

public class ChooserReducer extends MapReduceBase implements Reducer<Text, BytesWritable, IntWritable, BytesWritable> {
	
	private int k = -1;
	private int bestCostAlways = -1;
	
	
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
	public void reduce(Text key, Iterator<BytesWritable> values,
			OutputCollector<IntWritable, BytesWritable> output, Reporter reporter)
			throws IOException {
		
		if (key.equals("best")) {
			//first value is info regarding the problem and best solution
			BytesWritable auxInfo = values.next();
			ByteArrayInputStream bais = new ByteArrayInputStream(auxInfo.getBytes());
			DataInputStream dis = new DataInputStream(bais);
			k = dis.readInt();
			bestCostAlways = dis.readInt();
			return;
		} else if (k == -1) {
			//log something here
			//output some sort of error code
		}
		
		//find the best solution
		int bestCostThisRound = Integer.MAX_VALUE;
		SolutionData bestSolThisRound = null;
		ArrayList<SolutionData> solsThisRound = new ArrayList<SolutionData>();
		while (values.hasNext()) {
			BytesWritable bytes = values.next();
			ByteArrayInputStream bais = new ByteArrayInputStream(bytes.getBytes());
			DataInputStream dis = new DataInputStream(bais);
			int runsBestCost = dis.readInt();
			int bestSolOffset = dis.readInt();
			int bestSolLen = dis.readInt();
			int endSolOffset = dis.readInt();
			int endSolLen = dis.readInt();
			SolutionData solData = new SolutionData(runsBestCost, bytes, bestSolOffset, bestSolLen, endSolOffset, endSolLen);
			
			if (runsBestCost < bestCostThisRound) {
				bestCostThisRound = runsBestCost;
				bestSolThisRound = solData;
				solsThisRound.add(solData);
			}
		}
		//if run best cost improves on input solution
		
		//we always pass on the ending solution of a run, but we use the cost of its best solution to decide whether it moves on
		
		//if the best solution found this round is better than the global best, we set all solutions to the new global best
		
		//the master program carries out a read to find the best solution in the mean time
		
		//prepare inputs to next round
		
		//TODO: do the temperatures
		if (bestCostThisRound < bestCostAlways) {
			bestCostAlways = bestCostThisRound; //for passing on
			int nMappers = solsThisRound.size();
			//choose best k solutions
			solsThisRound = chooseKBest(solsThisRound, k);
			//first write out the k best
			for (SolutionData solution : solsThisRound) {
				BytesWritableSection solutionSection = new BytesWritableSection(solution.solutionBytes, 
						solution.endSolOffset, solution.endSolLen);
				output.collect(new IntWritable(solution.cost), solutionSection);
			}
			
			//then write out the rest of the bestSolutionAlways as many times as the difference
			int nBest = nMappers - k;
			BytesWritableSection bestSection = new BytesWritableSection(bestSolThisRound.solutionBytes,
					bestSolThisRound.bestSolOffset, bestSolThisRound.bestSolLen);
			for (int i = 0; i < nBest; i++) {
				output.collect(new IntWritable(bestCostThisRound), bestSection);
			}
		}
		
		//also write out the key for the best solution always
		
		//write out the inputs list
//		output.collect(arg0, arg1);
	}
	
	private ArrayList<SolutionData> chooseKBest(ArrayList<SolutionData> solDatas, int k) {
		PriorityQueue<SolutionData> maxHeap = new PriorityQueue<SolutionData>();
		for (SolutionData solData : solDatas) {
			if (solData.cost < maxHeap.peek().cost) {
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
	
	private class SolutionData implements Comparable<SolutionData> {
		public final int cost;
		public final int endSolOffset;
		public final int endSolLen;
		public final int bestSolOffset;
		public final int bestSolLen;
		public final BytesWritable solutionBytes;
		
		public SolutionData(int cost, BytesWritable solutionBytes, int bestSolOffset, int bestSolLen, 
				int endSolOffset, int endSolLen) {
			this.cost = cost;
			this.solutionBytes = solutionBytes;
			this.bestSolOffset = bestSolOffset;
			this.bestSolLen = bestSolLen;
			this.endSolOffset = endSolOffset;
			this.endSolLen = endSolLen;
		}
		
		@Override
		public int compareTo(SolutionData other) {
			return other.cost - this.cost; //TODO: other way around?
		}
	}
	
	private class BytesWritableSection extends BytesWritable implements Writable {
		private final BytesWritable wrapped;
		private final int offset;
		private final int len;
		
		public BytesWritableSection(BytesWritable wrapped, int offset, int len) {
			this.wrapped = wrapped;
			this.offset = offset;
			this.len = len;
		}
		
		@Override
		public void readFields(DataInput input) throws IOException {
			throw new IllegalStateException("this method should never be called");
		}
		
		@Override
		public void write(DataOutput output) throws IOException {
			byte[] bytes = wrapped.getBytes();
			output.write(bytes, offset, len);
		}
	}
}
