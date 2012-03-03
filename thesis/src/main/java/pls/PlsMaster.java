package pls;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
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

import pls.tsp.Greedy;
import pls.tsp.TspLsCity;
import pls.tsp.TspSaSolution;
import pls.tsp.TspUtils;

public class PlsMaster {

	private static final Logger LOG = Logger.getLogger(PlsMaster.class);
	
	public static void main(String[] args) throws IOException {
		//TODO: these should be arguments
		int numTasks = 5;;
		File f = new File("../tsptests/eil51.258");
		double temp = 5.0;
		double scaler = .9;
		int numRuns = 5;
		
		ArrayList<TspLsCity> citiesList = TspLsCityReader.read(f, Integer.MAX_VALUE);
		Greedy greedy = new Greedy();
		
		List<TspSaSolution> startSolutions = new ArrayList<TspSaSolution>(numTasks);
		for (int i = 0; i < numTasks; i++) {
			if (i > 0) {
				Collections.shuffle(citiesList);
			}
			TspLsCity[] cities = greedy.computeGreedy(citiesList);
			TspSaSolution solution = new TspSaSolution(cities, TspUtils.tourDist(cities), temp, scaler);
			startSolutions.add(solution);
		}
		
		PlsMaster master = new PlsMaster();
		master.run(numRuns, startSolutions, "/users/sryza/testdir/");
	}
	
	public void run(int numRuns, List<TspSaSolution> startSolutions, String dir) throws IOException {
		//write out start solutions to HDFS
		Configuration conf = new Configuration();
		FileSystem fs = FileSystem.get(conf);
		Path dirPath = new Path(dir);
		
		//write out initial input file
		Path initDirPath = new Path(dirPath, "0");
		if (!fs.mkdirs(initDirPath)) {
			LOG.info("Failed to create directory: " + dir);
		}
		
		Path initFilePath = new Path(initDirPath, "sols");
		
		SequenceFile.Writer writer = new SequenceFile.Writer(fs, conf, initFilePath, BytesWritable.class, BytesWritable.class);
		BytesWritable key = new BytesWritable("rest".getBytes());
		for (TspSaSolution sol : startSolutions) {
			byte[] solBytes = sol.toBytes();
			writer.append(key, new BytesWritable(solBytes));
		}
		writer.close();
		
		//run the waves
		for (int i = 0; i < numRuns; i++) {
			Path inputPath = new Path(dirPath, i + "");
			Path outputPath = new Path(dirPath, (i+1) + "");
			long start = System.currentTimeMillis();
			LOG.info("About to run job " + i);
			runHadoopJob(inputPath, outputPath);
			long end = System.currentTimeMillis();
			LOG.info("Took " + (end-start) + " ms");
		}
	}

	/**
	 * Involves sending a solution (or location of a solution) to each node.
	 */
	private void runHadoopJob(Path inputPath, Path outputPath) throws IOException {
		JobConf conf = new JobConf();

		conf.setOutputKeyClass(Text.class);
		conf.setOutputValueClass(IntWritable.class);
		
		conf.setJar("tspls.jar");
		
		conf.setMapperClass(SaMapper.class);
		conf.setReducerClass(ChooserReducer.class);

		conf.setInputFormat(SequenceFileInputFormat.class);
		conf.setOutputFormat(SequenceFileOutputFormat.class);
		
		FileInputFormat.addInputPath(conf, inputPath);
		FileOutputFormat.setOutputPath(conf, outputPath);
		
		JobClient.runJob(conf);
	}
}
