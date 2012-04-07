package pls;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.mapred.SequenceFileOutputFormat;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.hadoop.mapred.TextOutputFormat;
import org.apache.log4j.Logger;

import pls.stats.PlsJobStats;
import pls.tsp.Greedy;
import pls.tsp.TspLsCity;
import pls.tsp.TspSaSolution;
import pls.tsp.TspUtils;

public class PlsMaster {

	private static final Logger LOG = Logger.getLogger(PlsMaster.class);
	
	private boolean runLocal;
	
	public PlsMaster() {
		
	}
	
	public PlsMaster(boolean runLocal) {
		this.runLocal = runLocal;
	}
	
	public static void main(String[] args) throws IOException {
		int numTasks = Integer.parseInt(args[0]);
		int numRuns = Integer.parseInt(args[1]);
		File f = new File("../tsptests/eil101.258");
		double temp = 5.0;
		double scaler = .95;
		int timeMs = 60 * 1000;
		
		ArrayList<TspLsCity> citiesList = TspLsCityReader.read(f, Integer.MAX_VALUE);
		Greedy greedy = new Greedy();
		
		List<PlsSolution> startSolutions = new ArrayList<PlsSolution>(numTasks);
		double bestStartCost = Integer.MAX_VALUE;
		for (int i = 0; i < numTasks; i++) {
			if (i > 0) {
				Collections.shuffle(citiesList);
			}
			TspLsCity[] cities = greedy.computeGreedy(citiesList);
			TspSaSolution solution = new TspSaSolution(cities, TspUtils.tourDist(cities), temp, scaler);
			bestStartCost = Math.min(bestStartCost, solution.getCost());
			startSolutions.add(solution);
		}
		
		PlsMaster master = new PlsMaster();
		String dir = "/users/sryza/testdir/" + System.currentTimeMillis() + "/";
		LOG.info("results going to " + dir);
//		master.run(numRuns, startSolutions, bestStartCost, dir, );
	}
	
	public void run(int numRuns, List<PlsSolution> startSolutions, double bestCost, String dir, Class mapperClass,
			Class reducerClass, int roundTime, int k) throws IOException {
		//write out start solutions to HDFS
		Configuration conf = new Configuration();
		
		FileSystem fs = FileSystem.get(conf);
		Path dirPath = new Path(dir);
		fs.delete(dirPath, true);
		
		//write out initial input file
		Path initDirPath = new Path(dirPath, "0/");
		if (!fs.mkdirs(initDirPath)) {
			LOG.info("Failed to create directory: " + initDirPath);
		}
		
		Path initFilePath = new Path(initDirPath, "part-00000");
		
		long firstFinishTime = System.currentTimeMillis() + roundTime;
		//write out solutions
		SequenceFile.Writer writer = new SequenceFile.Writer(fs, conf, initFilePath, BytesWritable.class, BytesWritable.class);
		for (PlsSolution sol : startSolutions) {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			sol.writeToStream(new DataOutputStream(baos));
			BytesWritable solWritable = new BytesWritable(baos.toByteArray());
			writer.append(PlsUtil.getMapSolKey(firstFinishTime), solWritable);
		}
		//write out metadata
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		DataOutputStream dos = new DataOutputStream(baos);

		//int k = Math.max(1, (int)Math.round(startSolutions.size() / 2 + .5));
		LOG.info("k=" + k);
		
		dos.writeInt(k);
		dos.writeDouble(bestCost);
		dos.writeInt(roundTime);
		BytesWritable metadata = new BytesWritable(baos.toByteArray());
		writer.append(PlsUtil.METADATA_KEY, metadata);
		writer.close();
		
		PlsJobStats stats = new PlsJobStats();
		stats.setK(k);
		stats.setLsRunTime(roundTime);
		stats.setNumMappers(startSolutions.size());
		stats.setNumRounds(numRuns);
		//run the waves
		for (int i = 0; i < numRuns; i++) {
			Path inputPath = new Path(dirPath, i + "/");
			Path outputPath = new Path(dirPath, (i+1) + "/");
			long start = System.currentTimeMillis();
			LOG.info("About to run job " + i);
			runHadoopJob(inputPath, outputPath, startSolutions.size(), mapperClass, reducerClass);
			long end = System.currentTimeMillis();
			LOG.info("Took " + (end-start) + " ms");
			stats.reportRoundTime((int)(end-start));
		}
		writeStatsFile(dirPath, stats, fs);
	}

	/**
	 * Involves sending a solution (or location of a solution) to each node.
	 */
	private void runHadoopJob(Path inputPath, Path outputPath, int numMaps, Class mapperClass, Class reducerClass)
		throws IOException {
		JobConf conf = new JobConf();

		if (runLocal) {
			conf.set("mapred.job.tracker", "local");
		}
		
		conf.setOutputKeyClass(BytesWritable.class);
		conf.setOutputValueClass(BytesWritable.class);
		conf.setMapOutputKeyClass(BytesWritable.class);
		conf.setMapOutputValueClass(BytesWritable.class);
		
		conf.setJar("tspls.jar");
		
		conf.setMapperClass(mapperClass);
		conf.setReducerClass(reducerClass);
		
		conf.setSpeculativeExecution(false);
		
		conf.setInputFormat(SequenceFileInputFormat.class);
		conf.setOutputFormat(SequenceFileOutputFormat.class);
		
		conf.setNumMapTasks(numMaps);
		conf.setNumReduceTasks(1);
		
		FileInputFormat.addInputPath(conf, inputPath);
		FileOutputFormat.setOutputPath(conf, outputPath);
		
		JobClient.runJob(conf);
	}
	
	private void writeStatsFile(Path dir, PlsJobStats stats, FileSystem fs) throws IOException {
		Path statsPath = new Path(dir, "jobstats.stats");
		FSDataOutputStream os = fs.create(statsPath);
		String report = stats.makeReport();
		os.writeUTF(report);
		os.close();
	}
}
