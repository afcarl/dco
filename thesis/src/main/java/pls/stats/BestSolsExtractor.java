package pls.stats;

import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.log4j.Logger;

import pls.WritableSolution;


/**
 * For tracking how sols improve as a job runs.  Meant to supply information that can
 * be displayed by some other script.
 */
public class BestSolsExtractor {
	private static final Logger LOG = Logger.getLogger(BestSolsExtractor.class);
	
	public static void main(String[] args) throws Exception {
		String solutionClassName = args[0];
		Path baseDir = new Path("/users/sryza/testdir");
		int howManyBack = 0;
		if (args.length > 1) {
			howManyBack = -Integer.parseInt(args[1]);
		}
		if (args.length > 2) {
			baseDir = new Path(args[2]);
		}
		
		Configuration conf = new Configuration();
		FileSystem fs = FileSystem.get(conf);
		
		JobSolsFilesGatherer gatherer = new JobSolsFilesGatherer(fs);
		SolsOutputFileReader reader = new SolsOutputFileReader(fs);
		
		Path baseJobDir = gatherer.getLatestRunFolderPath(baseDir, howManyBack);
		List<Path> files = gatherer.gather(baseJobDir);

		//read best solutions for each run
		List<WritableSolution> bestSols = new ArrayList<WritableSolution>();
		List<Double> bestSolCosts = new ArrayList<Double>();
		for (Path file : files) {
			List<WritableSolution> sols = reader.getFileSolutions(file, conf, solutionClassName);
			WritableSolution bestSol = null;
			for (WritableSolution sol : sols) {
				if (bestSol == null || sol.getCost() < bestSol.getCost()) {
					bestSol = sol;
				}
			}
			bestSols.add(bestSol);
			bestSolCosts.add(bestSol.getCost());
		}
		
		//read run completion times
//		List<Integer> roundTimes = readRoundTimes(baseJobDir, fs);
//		List<Integer> roundFinishTimes = new ArrayList<Integer>();
//		roundFinishTimes.add(0);
//		for (Integer roundTime : roundTimes) {
//			int nextFinishTime = roundFinishTimes.get(roundFinishTimes.size()-1) + roundTime;
//			roundFinishTimes.add(nextFinishTime);
//		}
//		
//		if (roundTimes.size() != bestSolCosts.size()) {
//			LOG.info("Sizes don't match! " + roundTimes.size() + " != " + bestSolCosts.size());
//		}
		
		Path jobStatsPath = new Path(baseJobDir, "jobstats.stats");
		FSDataInputStream is = fs.open(jobStatsPath);
		String jobStats = is.readUTF();
		is.close();
		
		StringBuilder sb = new StringBuilder();
		sb.append("{");
		sb.append("\"bestSolCosts\":" + bestSolCosts);
		sb.append(",\n");
		sb.append("\"jobStats\":" + jobStats);
		sb.append("}");
		
		System.out.println(sb.toString());
	}
	
//	private static List<Integer> readRoundTimes(Path baseJobDir, FileSystem fs) throws IOException {
//		Path jobStatsPath = new Path(baseJobDir, "jobstats.stats");
//		FSDataInputStream is = fs.open(jobStatsPath);
//		String data = is.readUTF();
//		ObjectMapper mapper = new ObjectMapper();
//		Map<Object, Object> dataMap = mapper.readValue(data, Map.class);
//		List<Integer> roundTimes = (List<Integer>)dataMap.get("roundTimes");
//		return roundTimes;
//	}
}
